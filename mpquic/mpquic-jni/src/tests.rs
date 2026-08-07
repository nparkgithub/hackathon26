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
    std::thread::spawn(move || engine::run(cfg, poll, rx, r, None));
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
    std::thread::sleep(Duration::from_millis(1500));
    output::drain();

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

/// With two working paths (two loopback sockets) and the redundant
/// scheduler, the send_complete summary must report real, per-path bytes —
/// this is the per-path *accounting* the apps display, not a claim about
/// the scheduler's exact duplication ratio. tquic's redundant scheduler
/// weights by each path's RTT estimate, so while the second path is still
/// cold (its first RTT sample can be tens of ms vs. sub-ms once warm) it
/// legitimately carries a smaller share — that's real scheduler behavior,
/// not an accounting bug, so the threshold here only checks "meaningfully
/// nonzero on every path", not "roughly equal split".
#[test]
fn two_path_redundant_reports_bytes_on_each_path() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    let (_server_tx, server_running) = spawn_server("127.0.0.1:14436");
    std::thread::sleep(Duration::from_millis(500));
    let (client_tx, client_running) = spawn_engine(serde_json::json!({
        "role": "client",
        "connect_to": "127.0.0.1:14436",
        // Two sockets on the same IP -> two distinct 4-tuples, both valid.
        "local_addresses": ["127.0.0.1", "127.0.0.1"],
        "enable_multipath": true,
        "multipath_algorithm": "redundant",
        "congestion_control": "bbr",
        "log_level": "info",
    }));

    const PAYLOAD: usize = 200_000;
    // Every path must carry at least this many bytes to count as "the
    // per-path accounting is really tracking that path", well below what
    // even a heavily RTT-skewed redundant split would leave it with.
    const MIN_PATH_BYTES: u64 = 2_000;

    let deadline = Instant::now() + Duration::from_secs(20);
    let mut all = String::new();
    let mut sent = false;
    let mut cursor = 0usize; // where to resume scanning for the next summary
    let mut good_summary = None;
    let mut last_summary = None;
    // Retry the send if a path came back (near-)empty: under heavy parallel
    // test load the second path can still be cold when the first send goes
    // out, skewing that round; a second send after both paths have settled
    // resolves it without a fragile fixed pre-send sleep.
    while Instant::now() < deadline && good_summary.is_none() {
        std::thread::sleep(Duration::from_millis(300));
        all.push_str(&output::drain());
        all.push('\u{1E}');
        if !sent && all.contains("\"type\":\"connected\"") && all.contains("\"type\":\"path_added\"")
        {
            client_tx.send(Cmd::Send(vec![0xAB; PAYLOAD])).unwrap();
            sent = true;
        }
        if !sent {
            continue;
        }
        let Some(rel_pos) = all[cursor..].find("\"type\":\"send_complete\"") else {
            continue;
        };
        // Recover the start of this record (the preceding record separator).
        let abs_pos = cursor + rel_pos;
        let rec_start = all[..abs_pos].rfind('\u{1E}').map(|p| p + 1).unwrap_or(0);
        let rec_end = all[abs_pos..]
            .find('\u{1E}')
            .map(|p| abs_pos + p)
            .unwrap_or(all.len());
        let rec = &all[rec_start..rec_end];
        cursor = rec_end;
        let Ok(v) = serde_json::from_str::<serde_json::Value>(rec.trim_start_matches("E|")) else {
            continue;
        };
        let weak_path = v["paths"]
            .as_array()
            .map(|paths| {
                paths
                    .iter()
                    .any(|p| p["bytes_sent"].as_u64().unwrap_or(0) < MIN_PATH_BYTES)
            })
            .unwrap_or(true);
        last_summary = Some(v.clone());
        if weak_path {
            client_tx.send(Cmd::Send(vec![0xAB; PAYLOAD])).unwrap();
        } else {
            good_summary = Some(v);
        }
    }

    client_running.store(false, Ordering::Relaxed);
    server_running.store(false, Ordering::Relaxed);
    std::thread::sleep(Duration::from_millis(1500));
    output::drain();

    let summary = good_summary
        .or(last_summary)
        .unwrap_or_else(|| panic!("no send_complete; output:\n{all}"));
    let paths = summary["paths"].as_array().unwrap();
    assert!(paths.len() >= 2, "expected 2 paths, got {paths:?}");
    for p in paths {
        let bytes = p["bytes_sent"].as_u64().unwrap();
        let pkts = p["pkts_sent"].as_u64().unwrap();
        assert!(
            bytes >= MIN_PATH_BYTES && pkts > 0,
            "path carried too little after retry ({bytes} B / {pkts} pkts): {p}\nfull output:\n{all}"
        );
    }
}

/// Keep-alive must hold a connection open well past the idle timeout, and
/// the connection must still carry data afterwards. Uses a deliberately
/// tiny idle timeout so the test stays fast.
#[test]
fn keepalive_holds_connection_past_idle_timeout() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    let assets = assets_dir();
    let (_server_tx, server_running) = spawn_engine(serde_json::json!({
        "role": "server",
        "listen": "127.0.0.1:14439",
        "cert_file": format!("{assets}/server.crt"),
        "key_file": format!("{assets}/server.key"),
        "enable_multipath": true,
        "log_level": "info",
        "echo": true,
        "idle_timeout_ms": 3000,
        "keepalive_ms": 500,
    }));
    std::thread::sleep(Duration::from_millis(500));
    let (client_tx, client_running) = spawn_engine(serde_json::json!({
        "role": "client",
        "connect_to": "127.0.0.1:14439",
        "local_addresses": ["127.0.0.1"],
        "enable_multipath": true,
        "log_level": "info",
        "idle_timeout_ms": 3000,
        "keepalive_ms": 500,
    }));

    // Wait for the handshake, then stay completely silent for several times
    // the idle timeout.
    let mut all = String::new();
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
        if all.contains("\"type\":\"connected\"") {
            break;
        }
    }
    assert!(
        all.contains("\"type\":\"connected\""),
        "never connected; output:\n{all}"
    );

    let quiet_start = all.len();
    std::thread::sleep(Duration::from_secs(9)); // 3x the idle timeout
    all.push_str(&output::drain());
    let during_quiet = &all[quiet_start..];
    assert!(
        !during_quiet.contains("idle timeout") && !during_quiet.contains("\"disconnected\""),
        "connection dropped while idle despite keep-alive; output:\n{during_quiet}"
    );

    // ...and it still works.
    client_tx.send(Cmd::Send(b"after-idle".to_vec())).unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut echoes = 0;
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
        echoes = all.matches("after-idle").count();
        if echoes >= 2 {
            break;
        }
    }

    client_running.store(false, Ordering::Relaxed);
    server_running.store(false, Ordering::Relaxed);
    // Let the engines finish and clear the shared output queue, so their
    // trailing events can't leak into whichever test runs next.
    std::thread::sleep(Duration::from_millis(1500));
    output::drain();

    assert!(
        echoes >= 2,
        "no echo round-trip after the idle period ({echoes} sightings); output:\n{all}"
    );
}

/// Full HTTP/3 relay path: an external HTTP/3 client POSTs a JPEG-sized
/// body to the client engine's local h3 listener; the request is tunneled
/// over MPQUIC to the server engine, which answers; the response comes back
/// out of the local listener to the HTTP/3 client.
#[test]
fn http3_relay_jpeg_roundtrip() {
    use std::io::Write;

    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    let assets = assets_dir();
    let (_server_tx, server_running) = spawn_server("127.0.0.1:14437");
    std::thread::sleep(Duration::from_millis(500));
    let (client_tx, client_running) = spawn_client("127.0.0.1:14437", &["127.0.0.1"]);

    // Wait for the tunnel, then start the local h3 listener.
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut all = String::new();
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
        if all.contains("\"type\":\"connected\"") {
            break;
        }
    }
    assert!(
        all.contains("\"type\":\"connected\""),
        "tunnel never connected; output:\n{all}"
    );
    client_tx
        .send(Cmd::H3Listen {
            port: 14438,
            cert: format!("{assets}/server.crt"),
            key: format!("{assets}/server.key"),
            idle_timeout_ms: crate::h3relay::DEFAULT_IDLE_TIMEOUT_MS,
        })
        .unwrap();

    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
        if all.contains("\"type\":\"h3_listening\"") {
            break;
        }
    }
    assert!(
        all.contains("\"type\":\"h3_listening\""),
        "h3 listener did not start; output:\n{all}"
    );

    // A ~2 MB "JPEG": SOI marker plus filler, so the size is realistic.
    let mut jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0];
    jpeg.extend((0..2 * 1024 * 1024).map(|i| (i % 251) as u8));
    let jpeg_len = jpeg.len();

    let (tx, rx) = mpsc::channel::<Result<(u64, usize), String>>();
    let body = jpeg.clone();
    std::thread::spawn(move || {
        let _ = tx.send(h3_client_post("127.0.0.1:14438", "/upload.jpg", &body));
    });

    let result = rx
        .recv_timeout(Duration::from_secs(30))
        .unwrap_or_else(|e| Err(format!("h3 client did not finish: {e}")));

    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
    }
    client_running.store(false, Ordering::Relaxed);
    server_running.store(false, Ordering::Relaxed);
    std::thread::sleep(Duration::from_millis(300));

    let _ = std::io::stdout().flush();
    let (status, resp_len) = result.unwrap_or_else(|e| panic!("{e}\nengine output:\n{all}"));
    assert_eq!(status, 200, "unexpected status; output:\n{all}");
    assert_eq!(
        resp_len, jpeg_len,
        "echoed JPEG size mismatch; output:\n{all}"
    );
    assert!(
        all.contains("\"type\":\"h3_request\""),
        "no h3_request event; output:\n{all}"
    );
    assert!(
        all.contains("\"type\":\"h3_response\""),
        "no h3_response event; output:\n{all}"
    );
}

/// The local HTTP/3 listener's own connections (an external h3 client, held
/// open between uploads) were not covered by the tunnel keepalive fix and
/// would silently idle out on their own separate timer. With a deliberately
/// tiny h3 idle timeout and the engine's keepalive enabled, one HTTP/3
/// connection must survive several times that timeout and still serve a
/// second request.
#[test]
fn h3_listener_keepalive_holds_connection_past_idle_timeout() {
    let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    output::init_logger("info");
    output::drain();

    let assets = assets_dir();
    let (_server_tx, server_running) = spawn_server("127.0.0.1:14441");
    std::thread::sleep(Duration::from_millis(500));
    let (client_tx, client_running) = spawn_engine(serde_json::json!({
        "role": "client",
        "connect_to": "127.0.0.1:14441",
        "local_addresses": ["127.0.0.1"],
        "enable_multipath": true,
        "log_level": "info",
        "keepalive_ms": 300,
    }));

    let mut all = String::new();
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
        if all.contains("\"type\":\"connected\"") {
            break;
        }
    }
    assert!(
        all.contains("\"type\":\"connected\""),
        "tunnel never connected; output:\n{all}"
    );

    client_tx
        .send(Cmd::H3Listen {
            port: 14442,
            cert: format!("{assets}/server.crt"),
            key: format!("{assets}/server.key"),
            // Far below the 6s gap the client leaves between requests below
            // — only the keepalive ping can be keeping this alive.
            idle_timeout_ms: 1500,
        })
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
        if all.contains("\"type\":\"h3_listening\"") {
            break;
        }
    }
    assert!(
        all.contains("\"type\":\"h3_listening\""),
        "h3 listener did not start; output:\n{all}"
    );

    let body1 = b"first-request".to_vec();
    let body2 = b"second-request-after-idle-gap".to_vec();
    let result = h3_client_two_posts_with_gap(
        "127.0.0.1:14442",
        "/a",
        &body1,
        "/b",
        &body2,
        Duration::from_secs(6), // 4x the h3 listener's idle timeout
    );

    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(200));
        all.push_str(&output::drain());
    }
    client_running.store(false, Ordering::Relaxed);
    server_running.store(false, Ordering::Relaxed);
    std::thread::sleep(Duration::from_millis(1500));
    output::drain();

    let (r1, r2) = result.unwrap_or_else(|e| panic!("{e}\nengine output:\n{all}"));
    assert_eq!(r1, (200, body1.len()), "first request; output:\n{all}");
    assert_eq!(
        r2,
        (200, body2.len()),
        "second request after the idle gap (this is the keepalive check); output:\n{all}"
    );
}

/// Like [`h3_client_post`] but keeps one HTTP/3 connection open across two
/// requests separated by `idle_gap`, to prove the connection survives idle
/// periods rather than just a single fast round trip.
fn h3_client_two_posts_with_gap(
    server: &str,
    path1: &str,
    body1: &[u8],
    path2: &str,
    body2: &[u8],
    idle_gap: Duration,
) -> Result<((u64, usize), (u64, usize)), String> {
    use std::cell::RefCell;
    use std::net::{SocketAddr, UdpSocket};
    use std::rc::Rc;

    use tquic::h3::connection::Http3Connection;
    use tquic::h3::{Header, Http3Config, Http3Event, NameValue};
    use tquic::{Config, Connection, Endpoint, PacketInfo, TlsConfig, TransportHandler};

    #[derive(Default)]
    struct State {
        established: Vec<u64>,
        // Reset before each request; filled in once that request finishes.
        status: Option<u64>,
        body_len: usize,
        done: bool,
    }

    struct H3Client {
        state: Rc<RefCell<State>>,
        h3: Rc<RefCell<Option<Http3Connection>>>,
        buf: Vec<u8>,
    }

    impl TransportHandler for H3Client {
        fn on_conn_created(&mut self, _c: &mut Connection) {}
        fn on_conn_established(&mut self, conn: &mut Connection) {
            let cfg = Http3Config::new().unwrap();
            let h3 = Http3Connection::new_with_quic_conn(conn, &cfg).unwrap();
            *self.h3.borrow_mut() = Some(h3);
            self.state
                .borrow_mut()
                .established
                .push(conn.index().unwrap_or(0));
        }
        fn on_conn_closed(&mut self, _c: &mut Connection) {}
        fn on_stream_created(&mut self, _c: &mut Connection, _s: u64) {}
        fn on_stream_readable(&mut self, conn: &mut Connection, _s: u64) {
            let mut h3_ref = self.h3.borrow_mut();
            let Some(h3) = h3_ref.as_mut() else { return };
            loop {
                match h3.poll(conn) {
                    Ok((_sid, Http3Event::Headers { headers, .. })) => {
                        for h in &headers {
                            if h.name() == b":status" {
                                self.state.borrow_mut().status =
                                    String::from_utf8_lossy(h.value()).parse::<u64>().ok();
                            }
                        }
                    }
                    Ok((sid, Http3Event::Data)) => {
                        while let Ok(n) = h3.recv_body(conn, sid, &mut self.buf) {
                            if n == 0 {
                                break;
                            }
                            self.state.borrow_mut().body_len += n;
                        }
                    }
                    Ok((_sid, Http3Event::Finished)) => {
                        self.state.borrow_mut().done = true;
                    }
                    Err(tquic::h3::Http3Error::Done) => break,
                    Err(_) => break,
                    _ => {}
                }
            }
        }
        fn on_stream_writable(&mut self, _c: &mut Connection, _s: u64) {}
        fn on_stream_closed(&mut self, _c: &mut Connection, _s: u64) {}
        fn on_new_token(&mut self, _c: &mut Connection, _t: Vec<u8>) {}
    }

    struct Sock(UdpSocket);
    impl tquic::PacketSendHandler for Sock {
        fn on_packets_send(&self, pkts: &[(Vec<u8>, PacketInfo)]) -> tquic::Result<usize> {
            let mut n = 0;
            for (pkt, info) in pkts {
                let _ = self.0.send_to(pkt, info.dst);
                n += 1;
            }
            Ok(n)
        }
    }

    let remote: SocketAddr = server.parse().map_err(|e| format!("addr: {e}"))?;
    let socket = UdpSocket::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_millis(50)))
        .map_err(|e| e.to_string())?;
    let local = socket.local_addr().map_err(|e| e.to_string())?;

    let mut config = Config::new().map_err(|e| e.to_string())?;
    // Comfortably longer than idle_gap so only the *server's* idle timeout
    // (and its keepalive) is under test here.
    config.set_max_idle_timeout(60_000);
    config.set_initial_max_streams_bidi(16);
    config.set_recv_udp_payload_size(65527);
    config.set_initial_max_data(64 * 1024 * 1024);
    config.set_initial_max_stream_data_bidi_local(32 * 1024 * 1024);
    config.set_initial_max_stream_data_bidi_remote(32 * 1024 * 1024);
    config.set_initial_max_stream_data_uni(1024 * 1024);
    config.set_initial_max_streams_uni(16);
    let tls = TlsConfig::new_client_config(vec![b"h3".to_vec()], false)
        .map_err(|e| format!("tls: {e}"))?;
    config.set_tls_config(tls);

    let state = Rc::new(RefCell::new(State::default()));
    let h3 = Rc::new(RefCell::new(None));
    let handler = H3Client {
        state: state.clone(),
        h3: h3.clone(),
        buf: vec![0u8; 65536],
    };
    let sock = Rc::new(Sock(socket.try_clone().map_err(|e| e.to_string())?));
    let mut endpoint = Endpoint::new(Box::new(config), false, Box::new(handler), sock);
    endpoint
        .connect(local, remote, Some("mpquic"), None, None, None)
        .map_err(|e| format!("connect: {e}"))?;

    fn send_post(
        endpoint: &mut Endpoint,
        h3: &Rc<RefCell<Option<Http3Connection>>>,
        idx: u64,
        path: &str,
        body: &[u8],
    ) -> Result<(), String> {
        let mut h3c_ref = h3.borrow_mut();
        let h3c = h3c_ref.as_mut().ok_or("h3 not ready")?;
        let conn = endpoint.conn_get_mut(idx).ok_or("conn gone")?;
        let stream_id = h3c.stream_new(conn).map_err(|e| format!("stream: {e:?}"))?;
        let headers = vec![
            Header::new(b":method", b"POST"),
            Header::new(b":scheme", b"https"),
            Header::new(b":authority", b"mpquic"),
            Header::new(b":path", path.as_bytes()),
            Header::new(b"content-type", b"application/octet-stream"),
        ];
        h3c.send_headers(conn, stream_id, &headers, false)
            .map_err(|e| format!("send_headers: {e:?}"))?;
        let payload = bytes::Bytes::from(body.to_vec());
        let mut off = 0usize;
        while off < payload.len() {
            match h3c.send_body(conn, stream_id, payload.slice(off..), true) {
                Ok(0) | Err(tquic::h3::Http3Error::Done) => break,
                Ok(n) => off += n,
                Err(e) => return Err(format!("send_body: {e:?}")),
            }
        }
        Ok(())
    }

    let mut buf = vec![0u8; 65536];
    let mut idx = None;
    let mut sent1 = false;
    let mut result1 = None;
    let mut gap_until: Option<Instant> = None;
    let mut sent2 = false;
    let mut result2 = None;

    let overall_deadline = Instant::now() + Duration::from_secs(20) + idle_gap;
    while Instant::now() < overall_deadline {
        endpoint.process_connections().map_err(|e| e.to_string())?;

        if idx.is_none() {
            idx = state.borrow().established.first().copied();
        }
        if let Some(i) = idx {
            if !sent1 {
                send_post(&mut endpoint, &h3, i, path1, body1)?;
                sent1 = true;
            } else if result1.is_none() {
                let s = state.borrow();
                if s.done {
                    result1 = Some((s.status.unwrap_or(0), s.body_len));
                    drop(s);
                    gap_until = Some(Instant::now() + idle_gap);
                    *state.borrow_mut() = State {
                        established: vec![i],
                        ..Default::default()
                    };
                }
            } else if !sent2 {
                if Instant::now() >= gap_until.unwrap() {
                    send_post(&mut endpoint, &h3, i, path2, body2)?;
                    sent2 = true;
                }
            } else if result2.is_none() {
                let s = state.borrow();
                if s.done {
                    result2 = Some((s.status.unwrap_or(0), s.body_len));
                }
            } else {
                break;
            }
        }

        match socket.recv_from(&mut buf) {
            Ok((len, peer)) => {
                let info = PacketInfo {
                    src: peer,
                    dst: local,
                    time: Instant::now(),
                };
                let _ = endpoint.recv(&mut buf[..len], &info);
            }
            Err(_) => {}
        }
        endpoint.on_timeout(Instant::now());
    }

    match (result1, result2) {
        (Some(r1), Some(r2)) => Ok((r1, r2)),
        (None, _) => Err("first request never completed".into()),
        (Some(_), None) => Err("second request never completed (connection likely dropped during the idle gap)".into()),
    }
}

/// Minimal HTTP/3 client used by the relay test: POSTs `body` and returns
/// (status, response body length).
fn h3_client_post(server: &str, path: &str, body: &[u8]) -> Result<(u64, usize), String> {
    use std::cell::RefCell;
    use std::net::{SocketAddr, UdpSocket};
    use std::rc::Rc;

    use tquic::h3::connection::Http3Connection;
    use tquic::h3::{Header, Http3Config, Http3Event};
    use tquic::{Config, Connection, Endpoint, PacketInfo, TlsConfig, TransportHandler};

    #[derive(Default)]
    struct State {
        established: Vec<u64>,
        status: Option<u64>,
        body_len: usize,
        done: bool,
    }

    struct H3Client {
        state: Rc<RefCell<State>>,
        h3: Rc<RefCell<Option<Http3Connection>>>,
        buf: Vec<u8>,
    }

    impl TransportHandler for H3Client {
        fn on_conn_created(&mut self, _c: &mut Connection) {}
        fn on_conn_established(&mut self, conn: &mut Connection) {
            let cfg = Http3Config::new().unwrap();
            let h3 = Http3Connection::new_with_quic_conn(conn, &cfg).unwrap();
            *self.h3.borrow_mut() = Some(h3);
            self.state
                .borrow_mut()
                .established
                .push(conn.index().unwrap_or(0));
        }
        fn on_conn_closed(&mut self, _c: &mut Connection) {
            self.state.borrow_mut().done = true;
        }
        fn on_stream_created(&mut self, _c: &mut Connection, _s: u64) {}
        fn on_stream_readable(&mut self, conn: &mut Connection, _s: u64) {
            let mut h3_ref = self.h3.borrow_mut();
            let Some(h3) = h3_ref.as_mut() else { return };
            loop {
                match h3.poll(conn) {
                    Ok((_sid, Http3Event::Headers { headers, .. })) => {
                        for h in &headers {
                            use tquic::h3::NameValue;
                            if h.name() == b":status" {
                                self.state.borrow_mut().status = String::from_utf8_lossy(h.value())
                                    .parse::<u64>()
                                    .ok();
                            }
                        }
                    }
                    Ok((sid, Http3Event::Data)) => {
                        while let Ok(n) = h3.recv_body(conn, sid, &mut self.buf) {
                            if n == 0 {
                                break;
                            }
                            self.state.borrow_mut().body_len += n;
                        }
                    }
                    Ok((_sid, Http3Event::Finished)) => {
                        self.state.borrow_mut().done = true;
                    }
                    Err(tquic::h3::Http3Error::Done) => break,
                    Err(_) => break,
                    _ => {}
                }
            }
        }
        fn on_stream_writable(&mut self, _c: &mut Connection, _s: u64) {}
        fn on_stream_closed(&mut self, _c: &mut Connection, _s: u64) {}
        fn on_new_token(&mut self, _c: &mut Connection, _t: Vec<u8>) {}
    }

    struct Sock(UdpSocket);
    impl tquic::PacketSendHandler for Sock {
        fn on_packets_send(&self, pkts: &[(Vec<u8>, PacketInfo)]) -> tquic::Result<usize> {
            let mut n = 0;
            for (pkt, info) in pkts {
                let _ = self.0.send_to(pkt, info.dst);
                n += 1;
            }
            Ok(n)
        }
    }

    let remote: SocketAddr = server.parse().map_err(|e| format!("addr: {e}"))?;
    let socket = UdpSocket::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_millis(50)))
        .map_err(|e| e.to_string())?;
    let local = socket.local_addr().map_err(|e| e.to_string())?;

    let mut config = Config::new().map_err(|e| e.to_string())?;
    config.set_max_idle_timeout(30_000);
    config.set_initial_max_streams_bidi(16);
    config.set_recv_udp_payload_size(65527);
    config.set_initial_max_data(64 * 1024 * 1024);
    config.set_initial_max_stream_data_bidi_local(32 * 1024 * 1024);
    config.set_initial_max_stream_data_bidi_remote(32 * 1024 * 1024);
    config.set_initial_max_stream_data_uni(1024 * 1024);
    config.set_initial_max_streams_uni(16);
    let tls = TlsConfig::new_client_config(vec![b"h3".to_vec()], false)
        .map_err(|e| format!("tls: {e}"))?;
    config.set_tls_config(tls);

    let state = Rc::new(RefCell::new(State::default()));
    let h3 = Rc::new(RefCell::new(None));
    let handler = H3Client {
        state: state.clone(),
        h3: h3.clone(),
        buf: vec![0u8; 65536],
    };
    let sock = Rc::new(Sock(socket.try_clone().map_err(|e| e.to_string())?));
    let mut endpoint = Endpoint::new(Box::new(config), false, Box::new(handler), sock);
    endpoint
        .connect(local, remote, Some("mpquic"), None, None, None)
        .map_err(|e| format!("connect: {e}"))?;

    let mut buf = vec![0u8; 65536];
    let mut sent = false;
    let deadline = Instant::now() + Duration::from_secs(25);
    while Instant::now() < deadline {
        endpoint.process_connections().map_err(|e| e.to_string())?;

        if !sent {
            let idx = state.borrow().established.first().copied();
            if let (Some(idx), Some(h3c)) = (idx, h3.borrow_mut().as_mut()) {
                if let Some(conn) = endpoint.conn_get_mut(idx) {
                    let stream_id = h3c.stream_new(conn).map_err(|e| format!("stream: {e:?}"))?;
                    let headers = vec![
                        Header::new(b":method", b"POST"),
                        Header::new(b":scheme", b"https"),
                        Header::new(b":authority", b"mpquic"),
                        Header::new(b":path", path.as_bytes()),
                        Header::new(b"content-type", b"image/jpeg"),
                    ];
                    h3c.send_headers(conn, stream_id, &headers, false)
                        .map_err(|e| format!("send_headers: {e:?}"))?;
                    let payload = bytes::Bytes::from(body.to_vec());
                    let mut off = 0usize;
                    while off < payload.len() {
                        match h3c.send_body(conn, stream_id, payload.slice(off..), true) {
                            Ok(0) | Err(tquic::h3::Http3Error::Done) => break,
                            Ok(n) => off += n,
                            Err(e) => return Err(format!("send_body: {e:?}")),
                        }
                    }
                    if off >= payload.len() {
                        sent = true;
                    }
                }
            }
        }

        if state.borrow().done && state.borrow().status.is_some() {
            break;
        }

        match socket.recv_from(&mut buf) {
            Ok((len, peer)) => {
                let info = PacketInfo {
                    src: peer,
                    dst: local,
                    time: Instant::now(),
                };
                let _ = endpoint.recv(&mut buf[..len], &info);
            }
            Err(_) => {}
        }
        endpoint.on_timeout(Instant::now());
    }

    let s = state.borrow();
    match s.status {
        Some(status) => Ok((status, s.body_len)),
        None => Err("no HTTP/3 response received".into()),
    }
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
