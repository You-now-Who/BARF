use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::{mpsc, RwLock};
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SerialMessage {
    pub type_: String,
    pub data: Option<String>,
    pub connected: Option<bool>,
    pub timestamp: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SerialCommand {
    pub action: String,
    pub data: Option<String>,
}

pub struct SerialMonitor {
    phone_ws_url: String,
    tx: Option<mpsc::UnboundedSender<String>>,
    connected: Arc<RwLock<bool>>,
}

impl SerialMonitor {
    pub fn new(phone_ws_url: String) -> Self {
        Self {
            phone_ws_url,
            tx: None,
            connected: Arc::new(RwLock::new(false)),
        }
    }

    pub async fn connect(&mut self, event_tx: mpsc::UnboundedSender<Value>) -> Result<(), String> {
        let url = format!("{}/api/serial", self.phone_ws_url);

        let (ws_stream, _) = connect_async(&url)
            .await
            .map_err(|e| format!("Failed to connect to phone serial WebSocket: {}", e))?;

        let (mut write, mut read) = ws_stream.split();

        let (tx, mut rx) = mpsc::unbounded_channel::<String>();
        self.tx = Some(tx);

        // Spawn task to forward outgoing messages
        tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if let Err(e) = write.send(Message::Text(msg.into())).await {
                    eprintln!("Error sending to phone: {}", e);
                    break;
                }
            }
        });

        // Spawn task to receive incoming messages
        let connected = self.connected.clone();
        tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(Message::Text(text)) => {
                        if let Ok(serial_msg) = serde_json::from_str::<SerialMessage>(&text) {
                            match serial_msg.type_.as_str() {
                                "serial_rx" => {
                                    if let Some(data) = &serial_msg.data {
                                        let _ = event_tx.send(json!({
                                            "type": "serial_rx",
                                            "data": data,
                                            "timestamp": serial_msg.timestamp
                                        }));
                                    }
                                }
                                "serial_status" => {
                                    if let Some(connected_status) = serial_msg.connected {
                                        *connected.write().await = connected_status;
                                        let _ = event_tx.send(json!({
                                            "type": "serial_status",
                                            "connected": connected_status
                                        }));
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                    Ok(Message::Close(_)) => {
                        *connected.write().await = false;
                        let _ = event_tx.send(json!({
                            "type": "serial_status",
                            "connected": false
                        }));
                        break;
                    }
                    Err(e) => {
                        eprintln!("Error reading from phone serial WebSocket: {}", e);
                        *connected.write().await = false;
                        break;
                    }
                    _ => {}
                }
            }
        });

        Ok(())
    }

    pub fn send(&self, data: &str) -> Result<(), String> {
        if let Some(tx) = &self.tx {
            let cmd = json!({
                "action": "write",
                "data": data
            });
            tx.send(cmd.to_string())
                .map_err(|e| format!("Failed to send command: {}", e))
        } else {
            Err("Not connected".into())
        }
    }

    pub fn is_connected(&self) -> bool {
        *self.connected.try_read().unwrap_or_default()
    }
}
