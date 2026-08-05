//! Host-side end-to-end tests: run a server engine and a client engine over
//! loopback in the same process and verify payload echo (IPv4 and IPv6).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

use mio::Poll;

use crate::config::BridgeConfig;
use crate::engine::{self, Cmd};
use crate::output;

/// The output queue is a process-wide global, so tests that read it must not
/// run concurrently.
static TEST_LOCK: Mutex<()> = Mutex::new(());

fn assets_dir() -> &'static str {
    concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../android/core/src/main/assets"
    )
}

fn spawn_engine(cfg_json: serde_json::Value) -> (mpsc::Sender<Cmd>, Arc<AtomicBool>) {
    let cfg: BridgeConfig = serde_json::from_value(cfg_json).unwrap();
    let poll = Poll::new().unwrap();
    let (tx, rx) = mpsc::channel();
    let running = Arc::new(AtomicBool::new(true));
    let r = running.clone();
    std::thread::spawn(move || engine::run(cfg, poll, rx, r));
    (tx, running)
}

fn spawn_server(listen: &str) -> (mpsc::Sender<Cmd>, Arc<AtomicBool>) {
    let assets = assets_dir();
    spawn_engine(serde_json::json!({
        "role": "server",
        "listen": listen,
        "cert_file": format!("{assets}/server.crt"),
        "key_file": format!("{assets}/server.key"),
        "enable_multipath": true,
        "multipath_algorithm": "minrtt",
        "congestion_control": "bbr",
        "log_level": "info",
        "echo": true,
    }))
}

fn spawn_client(connect_to: &str, locals: &[&str]) -> (mpsc::Sender<Cmd>, Arc<AtomicBool>) {
    spawn_engine(serde_json::json!({
        "role": "client",
        "connect_to": connect_to,
        "local_addresses": locals,
        "enable_multipath": true,
        "multipath_algorithm": "minrtt",
        "congestion_control": "bbr",
        "log_level": "info",
    }))
}

/// Drive a client+server pair through handshake and a payload echo, assert
/// the round-trip happened, and return everything the engines emitted.
fn echo_roundtrip(listen: &str, connect_to: &str, locals: &[&str]) -> String {
    let (_server_tx, server_running) = spawn_server(listen);
    std::thread::sleep(Duration::from_millis(500));

    let (client_tx, client_running) = spawn_client(connect_to, locals);

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
        // Server received + client received echo, and the client emitted its
        // post-transfer per-path summary.
        if data_events >= 2 && all.contains("\"type\":\"send_complete\"") {
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
    assert!(
        all.contains("\"type\":\"send_complete\""),
        "send_complete summary missing; output:\n{all}"
    );
    all
}

#[test]
fn end_to_end_echo() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    echo_roundtrip("127.0.0.1:14433", "127.0.0.1:14433", &["127.0.0.1"]);
}

#[test]
fn end_to_end_echo_ipv6() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    echo_roundtrip("[::1]:14434", "[::1]:14434", &["::1"]);
}

/// A mixed v4+v6 local address list (as produced by the wlan/rmnet auto-fill)
/// must still connect to a v4 server: the v6 entry is skipped with a
/// `path_skipped` event instead of failing the whole engine.
#[test]
fn mixed_family_locals_are_filtered() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    let all = echo_roundtrip("127.0.0.1:14435", "127.0.0.1:14435", &["::1", "127.0.0.1"]);
    assert!(
        all.contains("\"type\":\"path_skipped\""),
        "expected a path_skipped event for the ::1 local; output:\n{all}"
    );
    assert!(
        all.contains("address family differs"),
        "expected family-mismatch reason; output:\n{all}"
    );
}
