//! JNI bridge between the Android apps (com.mpquic.core.TquicBridge) and the
//! TQUIC engine. One engine (client or server) runs per process.
//!
//! The engine modules are public so non-Android frontends (e.g. the Linux
//! CLI under ../linux) can reuse them; only the Java_* exports below are
//! Android-specific.

pub mod config;
pub mod engine;
pub mod forward;
pub mod h3relay;
pub mod output;
pub mod socket;
#[cfg(test)]
mod tests;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use mio::{Poll, Waker};

use config::BridgeConfig;
use engine::{Cmd, EngineHandle, ENGINE};

fn start_engine(config_json: &str) -> Result<(), String> {
    let cfg: BridgeConfig =
        serde_json::from_str(config_json).map_err(|e| format!("bad config JSON: {e}"))?;

    output::init_logger(&cfg.log_level);

    let mut guard = ENGINE.lock().unwrap();
    if let Some(handle) = guard.take() {
        // Stop any previous engine first.
        handle.running.store(false, Ordering::Relaxed);
        let _ = handle.waker.wake();
        drop(handle.cmd_tx);
        if let Some(join) = handle.join {
            let _ = join.join();
        }
    }

    let poll = Poll::new().map_err(|e| e.to_string())?;
    let waker = Arc::new(
        Waker::new(poll.registry(), engine::waker_token()).map_err(|e| e.to_string())?,
    );
    let (cmd_tx, cmd_rx) = mpsc::channel::<Cmd>();
    let running = Arc::new(AtomicBool::new(true));

    let running2 = running.clone();
    let join = std::thread::Builder::new()
        .name("mpquic-engine".into())
        .spawn(move || engine::run(cfg, poll, cmd_rx, running2))
        .map_err(|e| e.to_string())?;

    *guard = Some(EngineHandle {
        cmd_tx,
        waker,
        running,
        join: Some(join),
    });
    Ok(())
}

fn stop_engine() {
    let mut guard = ENGINE.lock().unwrap();
    if let Some(handle) = guard.take() {
        handle.running.store(false, Ordering::Relaxed);
        let _ = handle.cmd_tx.send(Cmd::Close);
        let _ = handle.waker.wake();
        if let Some(join) = handle.join {
            let _ = join.join();
        }
    }
}

fn send_data(data: Vec<u8>) -> Result<(), String> {
    let guard = ENGINE.lock().unwrap();
    match guard.as_ref() {
        Some(handle) => {
            handle
                .cmd_tx
                .send(Cmd::Send(data))
                .map_err(|e| e.to_string())?;
            handle.waker.wake().map_err(|e| e.to_string())?;
            Ok(())
        }
        None => Err("engine not running".into()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mpquic_core_TquicBridge_nativeStart(
    mut env: JNIEnv,
    _cls: JClass,
    config: JString,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cfg: String = match env.get_string(&config) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        match start_engine(&cfg) {
            Ok(()) => 0,
            Err(e) => {
                output::push(format!("L|E|start failed: {e}"));
                -2
            }
        }
    }));
    result.unwrap_or(-3)
}

#[no_mangle]
pub extern "system" fn Java_com_mpquic_core_TquicBridge_nativeStop(
    _env: JNIEnv,
    _cls: JClass,
) {
    let _ = catch_unwind(stop_engine);
}

#[no_mangle]
pub extern "system" fn Java_com_mpquic_core_TquicBridge_nativeSend(
    mut env: JNIEnv,
    _cls: JClass,
    data: JByteArray,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return -1,
        };
        match send_data(bytes) {
            Ok(()) => 0,
            Err(e) => {
                output::push(format!("L|E|send failed: {e}"));
                -2
            }
        }
    }));
    result.unwrap_or(-3)
}

fn engine_cmd(cmd: Cmd) -> Result<(), String> {
    let guard = ENGINE.lock().unwrap();
    match guard.as_ref() {
        Some(handle) => {
            handle.cmd_tx.send(cmd).map_err(|e| e.to_string())?;
            handle.waker.wake().map_err(|e| e.to_string())?;
            Ok(())
        }
        None => Err("engine not running".into()),
    }
}

/// Start the local HTTP/3 listener whose requests are tunneled over MPQUIC.
#[no_mangle]
pub extern "system" fn Java_com_mpquic_core_TquicBridge_nativeH3Listen(
    mut env: JNIEnv,
    _cls: JClass,
    port: jint,
    cert: JString,
    key: JString,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let cert: String = match env.get_string(&cert) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let key: String = match env.get_string(&key) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        if !(1..=65535).contains(&port) {
            output::push(format!("L|E|invalid h3 port {port}"));
            return -1;
        }
        match engine_cmd(Cmd::H3Listen {
            port: port as u16,
            cert,
            key,
            idle_timeout_ms: crate::h3relay::DEFAULT_IDLE_TIMEOUT_MS,
        }) {
            Ok(()) => 0,
            Err(e) => {
                output::push(format!("L|E|h3 listen failed: {e}"));
                -2
            }
        }
    }));
    result.unwrap_or(-3)
}

#[no_mangle]
pub extern "system" fn Java_com_mpquic_core_TquicBridge_nativeH3Stop(
    _env: JNIEnv,
    _cls: JClass,
) -> jint {
    let result = catch_unwind(|| match engine_cmd(Cmd::H3Stop) {
        Ok(()) => 0,
        Err(_) => -2,
    });
    result.unwrap_or(-3)
}

#[no_mangle]
pub extern "system" fn Java_com_mpquic_core_TquicBridge_nativePoll(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let joined = catch_unwind(output::drain).unwrap_or_default();
    match env.new_string(joined) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
