use std::process::Command;

/// Compile C++ source to WASM using clang.
/// Returns the compiled .wasm bytes, or an error message.
#[tauri::command]
pub fn compile_wasm(source: String) -> Result<Vec<u8>, String> {
    let tmp_dir = std::env::temp_dir().join("barf_wasm");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;

    let src_path = tmp_dir.join("source.cpp");
    let out_path = tmp_dir.join("out.wasm");
    std::fs::write(&src_path, &source).map_err(|e| e.to_string())?;

    let status = Command::new("clang")
        .args([
            "--target=wasm32",
            "-O3",
            "-nostdlib",
            "-Wl,--no-entry",
            "-Wl,--export=setup",
            "-Wl,--export=on_frame",
            "-o",
        ])
        .arg(&out_path)
        .arg(&src_path)
        .status()
        .map_err(|e| format!("clang not found on PATH: {}", e))?;

    if !status.success() {
        return Err("WASM compilation failed".to_string());
    }

    let bytes = std::fs::read(&out_path).map_err(|e| e.to_string())?;
    Ok(bytes)
}

/// Compile Arduino sketch for ESP32 using arduino-cli.
/// Returns the compiled .bin path, or an error message.
#[tauri::command]
pub fn compile_esp32(source: String) -> Result<String, String> {
    let tmp_dir = std::env::temp_dir().join("barf_esp32");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;

    let sketch_path = tmp_dir.join("sketch.ino");
    std::fs::write(&sketch_path, &source).map_err(|e| e.to_string())?;

    let status = Command::new("arduino-cli")
        .args([
            "compile",
            "--fqbn",
            "esp32:esp32:esp32",
        ])
        .arg(&tmp_dir)
        .status()
        .map_err(|e| format!("arduino-cli not found on PATH: {}", e))?;

    if !status.success() {
        return Err("ESP32 compilation failed".to_string());
    }

    Ok(tmp_dir.join("sketch.ino.bin").to_string_lossy().to_string())
}
