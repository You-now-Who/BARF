use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;

#[derive(Debug, Deserialize)]
pub struct PairingRequest {
    pub pair_key: String,
    pub phone_ip: String,
    pub phone_port: u16,
}

#[derive(Debug, Serialize)]
struct PairingResponse {
    status: String,
    desktop_name: String,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    status: String,
    error: String,
}

#[derive(Clone)]
pub struct PairingState {
    pub expected_key: Arc<Mutex<String>>,
    pub paired_phone_ip: Arc<Mutex<Option<String>>>,
    pub paired_phone_port: Arc<Mutex<u16>>,
}

impl PairingState {
    pub fn new() -> Self {
        Self {
            expected_key: Arc::new(Mutex::new(String::new())),
            paired_phone_ip: Arc::new(Mutex::new(None)),
            paired_phone_port: Arc::new(Mutex::new(8080)),
        }
    }

    pub async fn set_expected_key(&self, key: String) {
        let mut k = self.expected_key.lock().await;
        *k = key;
    }

    pub async fn get_paired_ip(&self) -> Option<String> {
        let ip = self.paired_phone_ip.lock().await;
        ip.clone()
    }
}

pub async fn start_pairing_server(
    port: u16,
    state: PairingState,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{}", port)).await?;
    println!("[pairing] Listening on port {}", port);

    tokio::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((stream, addr)) => {
                    let state = state.clone();
                    tokio::spawn(async move {
                        if let Err(e) = handle_connection(stream, state).await {
                            println!("[pairing] Error from {}: {}", addr, e);
                        }
                    });
                }
                Err(e) => {
                    eprintln!("[pairing] Accept error: {}", e);
                }
            }
        }
    });

    Ok(())
}

async fn handle_connection(
    mut stream: tokio::net::TcpStream,
    state: PairingState,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};

    let mut reader = BufReader::new(&mut stream);
    let mut request_line = String::new();
    reader.read_line(&mut request_line).await?;
    let request_line = request_line.trim().to_string();

    // Read headers
    let mut content_length: usize = 0;
    loop {
        let mut header = String::new();
        reader.read_line(&mut header).await?;
        if header.trim().is_empty() {
            break;
        }
        let lower = header.to_lowercase();
        if let Some(rest) = lower.strip_prefix("content-length:") {
            content_length = rest.trim().parse().unwrap_or(0);
        }
    }

    // Read body
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader.read_exact(&mut body).await?;
    }

    let (status_line, response_body) = if request_line.starts_with("POST /api/phone-here") {
        match serde_json::from_slice::<PairingRequest>(&body) {
            Ok(req) => {
                let expected = state.expected_key.lock().await;
                if *expected == req.pair_key {
                    {
                        let mut ip = state.paired_phone_ip.lock().await;
                        *ip = Some(req.phone_ip.clone());
                        let mut port = state.paired_phone_port.lock().await;
                        *port = req.phone_port;
                    }
                    println!("[pairing] Paired with phone at {}:{}", req.phone_ip, req.phone_port);
                    let resp = PairingResponse {
                        status: "accepted".to_string(),
                        desktop_name: "BARF Console".to_string(),
                    };
                    (
                        "200 OK".to_string(),
                        serde_json::to_string(&resp).unwrap_or_default(),
                    )
                } else {
                    println!("[pairing] Rejected pairing: key mismatch");
                    let resp = ErrorResponse {
                        status: "rejected".to_string(),
                        error: "invalid pair_key".to_string(),
                    };
                    (
                        "401 Unauthorized".to_string(),
                        serde_json::to_string(&resp).unwrap_or_default(),
                    )
                }
            }
            Err(e) => {
                let resp = ErrorResponse {
                    status: "error".to_string(),
                    error: format!("invalid JSON: {}", e),
                };
                (
                    "400 Bad Request".to_string(),
                    serde_json::to_string(&resp).unwrap_or_default(),
                )
            }
        }
    } else {
        ("404 Not Found".to_string(), String::new())
    };

    let http_response = format!(
        "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status_line,
        response_body.len(),
        response_body
    );
    stream.write_all(http_response.as_bytes()).await?;
    stream.flush().await?;

    Ok(())
}
