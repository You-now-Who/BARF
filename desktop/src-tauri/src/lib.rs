pub mod compile;
pub mod phone_bridge;

use std::sync::Mutex;
use tauri::State;

/// Application-wide state shared across Tauri commands.
pub struct AppState {
    pub phone_ip: Mutex<String>,
}

#[tauri::command]
async fn get_status(state: State<'_, AppState>) -> Result<String, String> {
    let ip = state.phone_ip.lock().map_err(|e| e.to_string())?;
    Ok(format!("Connected to phone at {}", ip))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(AppState {
            phone_ip: Mutex::new("192.168.1.100".to_string()),
        })
        .invoke_handler(tauri::generate_handler![
            get_status,
            compile::compile_wasm,
            phone_bridge::deploy_wasm,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
