use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct PhoneStatus {
    pub server: String,
    pub status: String,
    pub timestamp: u64,
    pub http_port: u16,
    pub ws_port: u16,
    pub js_running: bool,
    pub ws_clients: u32,
    pub camera_facing: i32,
}

/// Deploy compiled WASM bytes to the phone.
#[tauri::command]
pub async fn deploy_wasm(ip: String, wasm_bytes: Vec<u8>) -> Result<String, String> {
    let url = format!("http://{}:8080/api/wasm", ip);
    let client = reqwest::Client::new();

    let part = reqwest::multipart::Part::bytes(wasm_bytes)
        .file_name("module.wasm")
        .mime_str("application/wasm")
        .map_err(|e| e.to_string())?;

    let form = reqwest::multipart::Form::new().part("wasm", part);

    let resp = client
        .post(&url)
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("Failed to reach phone at {}: {}", ip, e))?;

    let body = resp.text().await.map_err(|e| e.to_string())?;
    Ok(body)
}

/// Fetch phone status.
#[tauri::command]
pub async fn get_phone_status(ip: String) -> Result<PhoneStatus, String> {
    let url = format!("http://{}:8080/api/status", ip);
    let resp = reqwest::get(&url)
        .await
        .map_err(|e| format!("Failed to get phone status: {}", e))?;

    let status: PhoneStatus = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse status: {}", e))?;

    Ok(status)
}
