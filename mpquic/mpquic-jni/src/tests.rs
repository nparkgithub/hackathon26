//! Host-side end-to-end test: run a server engine and a client engine over
//! loopback in the same process and verify payload echo.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};

use mio::Poll;

use crate::config::BridgeConfig;
use crate::engine::{self, Cmd};
use crate::output;

fn spawn_engine(cfg_json: serde_json::Value) -> (mpsc::Sender<Cmd>, Arc<AtomicBool>) {
    let cfg: BridgeConfig = serde_json::from_value(cfg_json).unwrap();
    let poll = Poll::new().unwrap();
    let (tx, rx) = mpsc::channel();
    let running = Arc::new(AtomicBool::new(true));
    let r = running.clone();
    std::thread::spawn(move || engine::run(cfg, poll, rx, r));
    (tx, running)
}

#[test]
fn end_to_end_echo() {
    output::init_logger("info");

    let assets = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../android/core/src/main/assets"
    );

    let (_server_tx, server_running) = spawn_engine(serde_json::json!({
        "role": "server",
        "listen": "127.0.0.1:14433",
        "cert_file": format!("{assets}/server.crt"),
        "key_file": format!("{assets}/server.key"),
        "enable_multipath": true,
        "multipath_algorithm": "minrtt",
        "congestion_control": "bbr",
        "log_level": "info",
        "echo": true,
    }));
    std::thread::sleep(Duration::from_millis(500));

    let (client_tx, client_running) = spawn_engine(serde_json::json!({
        "role": "client",
        "connect_to": "127.0.0.1:14433",
        "local_addresses": ["127.0.0.1"],
        "enable_multipath": true,
        "multipath_algorithm": "minrtt",
        "congestion_control": "bbr",
        "log_level": "info",
    }));

    // Wait for handshake, then send a payload.
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut all = String::new();
    let mut sent = false;
    let mut data_events = 0;
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(300));
        let drained = output::drain();
        if !drained.is_empty() {
            all.push_str(&drained);
            all.push('\n');
        }
        if !sent && all.contains("\"type\":\"connected\"") {
            client_tx.send(Cmd::Send(b"hello-mpquic".to_vec())).unwrap();
            sent = true;
        }
        data_events = all.matches("\"type\":\"data\"").count();
        // Server received + client received echo.
        if data_events >= 2 {
            break;
        }
    }

    client_running.store(false, Ordering::Relaxed);
    server_running.store(false, Ordering::Relaxed);
    std::thread::sleep(Duration::from_millis(300));

    assert!(
        all.contains("\"type\":\"connected\""),
        "no connection established; output:\n{all}"
    );
    assert!(sent, "never sent payload; output:\n{all}");
    assert!(
        data_events >= 2,
        "expected echo round-trip (>=2 data events), got {data_events}; output:\n{all}"
    );
    assert!(
        all.contains("hello-mpquic"),
        "payload preview missing; output:\n{all}"
    );
}
