//! The QUIC engine: a dedicated thread running a mio poll loop that drives a
//! tquic Endpoint in either client or server role. Application payload is
//! opaque bytes on QUIC streams — no application protocol is imposed.

use std::cell::RefCell;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, Sender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use bytes::Bytes;
use log::{error, info, warn};
use mio::{Events, Poll, Token, Waker};
use serde_json::json;
use tquic::{Config, Connection, Endpoint, PacketInfo, TlsConfig, TransportHandler};

use crate::config::BridgeConfig;
use crate::output;
use crate::socket::QuicSocket;

const WAKER_TOKEN: Token = Token(usize::MAX - 1);
const MAX_BUF_SIZE: usize = 65536;
const STATS_INTERVAL: Duration = Duration::from_secs(1);

pub enum Cmd {
    /// Send opaque bytes to the peer (client: on its connection; server:
    /// broadcast to all established connections).
    Send(Vec<u8>),
    Close,
}

pub struct EngineHandle {
    pub cmd_tx: Sender<Cmd>,
    pub waker: Arc<Waker>,
    pub running: Arc<AtomicBool>,
    pub join: Option<JoinHandle<()>>,
}

pub static ENGINE: Mutex<Option<EngineHandle>> = Mutex::new(None);

/// State shared between the transport callbacks and the poll loop.
#[derive(Default)]
struct Shared {
    is_server: bool,
    echo: bool,
    remote: Option<SocketAddr>,
    /// Extra client local addresses to add as paths after handshake.
    extra_paths: Vec<SocketAddr>,
    /// Established connection indexes.
    conns: Vec<u64>,
    /// Outgoing stream per connection index.
    out_stream: HashMap<u64, u64>,
    /// Data not yet accepted by stream_write, keyed by (conn, stream).
    pending: HashMap<(u64, u64), Vec<u8>>,
    total_rx: u64,
    total_tx: u64,
}

struct Handler {
    shared: Rc<RefCell<Shared>>,
    buf: Vec<u8>,
}

impl Handler {
    fn flush_pending(shared: &mut Shared, conn: &mut Connection, stream_id: u64) {
        let index = conn.index().unwrap_or(u64::MAX);
        let key = (index, stream_id);
        if let Some(mut data) = shared.pending.remove(&key) {
            match conn.stream_write(stream_id, Bytes::from(data.clone()), false) {
                Ok(n) => {
                    shared.total_tx += n as u64;
                    if n < data.len() {
                        data.drain(..n);
                        shared.pending.insert(key, data);
                    }
                }
                Err(tquic::Error::Done) => {
                    shared.pending.insert(key, data);
                }
                Err(e) => {
                    error!("stream_write failed: {e:?}");
                }
            }
        }
    }
}

impl TransportHandler for Handler {
    fn on_conn_created(&mut self, conn: &mut Connection) {
        info!("{} connection created", conn.trace_id());
    }

    fn on_conn_established(&mut self, conn: &mut Connection) {
        let mut shared = self.shared.borrow_mut();
        let index = conn.index().unwrap_or(u64::MAX);
        shared.conns.push(index);
        output::push_event(
            json!({
                "type": "connected",
                "trace_id": conn.trace_id(),
                "multipath": conn.is_multipath(),
            })
            .to_string(),
        );

        // Client: add the extra multipath paths now that the handshake is done.
        if !shared.is_server {
            if let Some(remote) = shared.remote {
                for local in shared.extra_paths.clone() {
                    match conn.add_path(local, remote) {
                        Ok(_) => {
                            info!("{} added path {} -> {}", conn.trace_id(), local, remote);
                            output::push_event(
                                json!({
                                    "type": "path_added",
                                    "local": local.to_string(),
                                    "remote": remote.to_string(),
                                })
                                .to_string(),
                            );
                        }
                        Err(e) => {
                            error!(
                                "{} failed to add path {} -> {}: {e}",
                                conn.trace_id(),
                                local,
                                remote
                            );
                            output::push_event(
                                json!({
                                    "type": "path_failed",
                                    "local": local.to_string(),
                                    "remote": remote.to_string(),
                                    "error": e.to_string(),
                                })
                                .to_string(),
                            );
                        }
                    }
                }
            }
        }
    }

    fn on_conn_closed(&mut self, conn: &mut Connection) {
        let index = conn.index().unwrap_or(u64::MAX);
        let mut shared = self.shared.borrow_mut();
        shared.conns.retain(|&i| i != index);
        output::push_event(
            json!({
                "type": "disconnected",
                "trace_id": conn.trace_id(),
            })
            .to_string(),
        );
    }

    fn on_stream_created(&mut self, conn: &mut Connection, stream_id: u64) {
        info!("{} stream {} created", conn.trace_id(), stream_id);
    }

    fn on_stream_readable(&mut self, conn: &mut Connection, stream_id: u64) {
        let mut shared = self.shared.borrow_mut();
        loop {
            match conn.stream_read(stream_id, &mut self.buf) {
                Ok((n, fin)) => {
                    shared.total_rx += n as u64;
                    if n > 0 {
                        let preview: String = String::from_utf8_lossy(&self.buf[..n.min(64)])
                            .chars()
                            .filter(|c| !c.is_control())
                            .collect();
                        output::push_event(
                            json!({
                                "type": "data",
                                "stream": stream_id,
                                "bytes": n,
                                "fin": fin,
                                "preview": preview,
                            })
                            .to_string(),
                        );

                        // Server echoes payload back on the same stream.
                        if shared.is_server && shared.echo {
                            let index = conn.index().unwrap_or(u64::MAX);
                            let key = (index, stream_id);
                            shared
                                .pending
                                .entry(key)
                                .or_default()
                                .extend_from_slice(&self.buf[..n]);
                            Self::flush_pending(&mut shared, conn, stream_id);
                        }
                    }
                    if fin {
                        break;
                    }
                }
                Err(tquic::Error::Done) => break,
                Err(e) => {
                    error!("{} stream {} read error: {e:?}", conn.trace_id(), stream_id);
                    break;
                }
            }
        }
    }

    fn on_stream_writable(&mut self, conn: &mut Connection, stream_id: u64) {
        let mut shared = self.shared.borrow_mut();
        Self::flush_pending(&mut shared, conn, stream_id);
    }

    fn on_stream_closed(&mut self, conn: &mut Connection, stream_id: u64) {
        info!("{} stream {} closed", conn.trace_id(), stream_id);
    }

    fn on_new_token(&mut self, _conn: &mut Connection, _token: Vec<u8>) {}
}

fn build_quic_config(cfg: &BridgeConfig, is_server: bool) -> Result<Config, String> {
    let mut config = Config::new().map_err(|e| e.to_string())?;
    config.set_max_idle_timeout(cfg.idle_timeout_ms);
    config.set_initial_max_streams_bidi(64);
    config.set_recv_udp_payload_size(65527);
    config.set_congestion_control_algorithm(cfg.congestion_algor());
    config.enable_multipath(cfg.enable_multipath);
    config.set_multipath_algorithm(cfg.multipath_algor());
    // Multipath requires more than one active connection ID on each side.
    config.set_active_connection_id_limit(8);

    let tls_config = if is_server {
        let cert = cfg
            .cert_file
            .as_deref()
            .ok_or("server requires cert_file")?;
        let key = cfg.key_file.as_deref().ok_or("server requires key_file")?;
        TlsConfig::new_server_config(cert, key, cfg.alpn_bytes(), true)
            .map_err(|e| format!("TLS server config: {e}"))?
    } else {
        TlsConfig::new_client_config(cfg.alpn_bytes(), false)
            .map_err(|e| format!("TLS client config: {e}"))?
    };
    config.set_tls_config(tls_config);
    Ok(config)
}

fn emit_stats(endpoint: &mut Endpoint, shared: &Rc<RefCell<Shared>>) {
    let (conns, total_rx, total_tx) = {
        let s = shared.borrow();
        (s.conns.clone(), s.total_rx, s.total_tx)
    };
    for index in conns {
        let conn = match endpoint.conn_get_mut(index) {
            Some(c) => c,
            None => continue,
        };
        let tuples: Vec<_> = conn.paths_iter().collect();
        let mut paths = Vec::new();
        for t in tuples {
            if let Ok(ps) = conn.get_path_stats(t.local, t.remote) {
                paths.push(json!({
                    "local": t.local.to_string(),
                    "remote": t.remote.to_string(),
                    "srtt_us": ps.srtt,
                    "min_rtt_us": ps.min_rtt,
                    "cwnd": ps.final_cwnd,
                    "sent_pkts": ps.sent_count,
                    "recv_pkts": ps.recv_count,
                    "sent_bytes": ps.sent_bytes,
                    "recv_bytes": ps.recv_bytes,
                    "lost_pkts": ps.lost_count,
                }));
            }
        }
        let stats = conn.stats();
        output::push_event(
            json!({
                "type": "stats",
                "conn_index": index,
                "multipath": conn.is_multipath(),
                "sent_pkts": stats.sent_count,
                "recv_pkts": stats.recv_count,
                "sent_bytes": stats.sent_bytes,
                "recv_bytes": stats.recv_bytes,
                "lost_pkts": stats.lost_count,
                "app_rx_bytes": total_rx,
                "app_tx_bytes": total_tx,
                "paths": paths,
            })
            .to_string(),
        );
    }
}

fn parse_addr(s: &str) -> Result<SocketAddr, String> {
    s.parse::<SocketAddr>()
        .map_err(|e| format!("invalid address '{s}': {e}"))
}

pub fn run(cfg: BridgeConfig, poll: Poll, cmd_rx: Receiver<Cmd>, running: Arc<AtomicBool>) {
    if let Err(e) = run_inner(cfg, poll, cmd_rx, running) {
        error!("engine stopped with error: {e}");
        output::push_event(json!({"type": "error", "message": e}).to_string());
    }
    output::push_event(json!({"type": "stopped"}).to_string());
}

fn run_inner(
    cfg: BridgeConfig,
    mut poll: Poll,
    cmd_rx: Receiver<Cmd>,
    running: Arc<AtomicBool>,
) -> Result<(), String> {
    let is_server = cfg.role == "server";
    let config = build_quic_config(&cfg, is_server)?;

    let registry = poll.registry();
    let shared = Rc::new(RefCell::new(Shared {
        is_server,
        echo: cfg.echo,
        ..Default::default()
    }));

    // Bind sockets.
    let (sock, remote) = if is_server {
        let listen = cfg.listen.as_deref().unwrap_or("0.0.0.0:4433");
        let listen = parse_addr(listen)?;
        let sock = QuicSocket::new(&listen, registry).map_err(|e| format!("bind {listen}: {e}"))?;
        output::push_event(
            json!({"type": "listening", "addr": sock.local_addr().to_string()}).to_string(),
        );
        (sock, None)
    } else {
        let remote = parse_addr(cfg.connect_to.as_deref().ok_or("missing connect_to")?)?;
        let mut locals: Vec<IpAddr> = cfg
            .local_addresses
            .iter()
            .filter(|s| !s.trim().is_empty())
            .map(|s| {
                s.trim()
                    .parse::<IpAddr>()
                    .map_err(|e| format!("invalid local IP '{s}': {e}"))
            })
            .collect::<Result<_, _>>()?;

        // A UDP socket bound to one address family cannot reach a remote in
        // the other, so drop mismatched entries — the UI hands us a mixed
        // v4+v6 list harvested from the wlan/rmnet_data interfaces.
        locals.retain(|ip| {
            let family_ok = ip.is_ipv4() == remote.is_ipv4();
            if !family_ok {
                warn!("skipping local address {ip}: address family differs from server {remote}");
                output::push_event(
                    json!({
                        "type": "path_skipped",
                        "local": ip.to_string(),
                        "reason": format!("address family differs from server {remote}"),
                    })
                    .to_string(),
                );
            }
            family_ok
        });

        let first = match locals.first() {
            Some(ip) => SocketAddr::new(*ip, 0),
            None => match remote.is_ipv4() {
                true => SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
                false => SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0),
            },
        };
        let mut sock =
            QuicSocket::new(&first, registry).map_err(|e| format!("bind {first}: {e}"))?;

        let mut extra = Vec::new();
        for ip in locals.iter().skip(1) {
            let addr = sock
                .add(&SocketAddr::new(*ip, 0), registry)
                .map_err(|e| format!("bind {ip}: {e}"))?;
            extra.push(addr);
        }
        shared.borrow_mut().extra_paths = extra;
        shared.borrow_mut().remote = Some(remote);
        (sock, Some(remote))
    };

    let sock = Rc::new(sock);
    let handler = Handler {
        shared: shared.clone(),
        buf: vec![0u8; MAX_BUF_SIZE],
    };
    let mut endpoint = Endpoint::new(
        Box::new(config),
        is_server,
        Box::new(handler),
        sock.clone(),
    );

    if let Some(remote) = remote {
        endpoint
            .connect(sock.local_addr(), remote, Some("mpquic"), None, None, None)
            .map_err(|e| format!("connect: {e}"))?;
    }

    let mut events = Events::with_capacity(1024);
    let mut recv_buf = vec![0u8; MAX_BUF_SIZE];
    let mut last_stats = Instant::now();

    loop {
        endpoint
            .process_connections()
            .map_err(|e| format!("process_connections: {e}"))?;

        // Handle commands from the app.
        loop {
            match cmd_rx.try_recv() {
                Ok(Cmd::Send(data)) => {
                    let conns = shared.borrow().conns.clone();
                    if conns.is_empty() {
                        output::push_event(
                            json!({"type": "error", "message": "not connected yet"}).to_string(),
                        );
                        continue;
                    }
                    for index in conns {
                        if let Some(conn) = endpoint.conn_get_mut(index) {
                            let mut s = shared.borrow_mut();
                            let stream_id = match s.out_stream.get(&index) {
                                Some(id) => *id,
                                None => match conn.stream_bidi_new(0, true) {
                                    Ok(id) => {
                                        s.out_stream.insert(index, id);
                                        id
                                    }
                                    Err(e) => {
                                        error!("stream_bidi_new: {e:?}");
                                        continue;
                                    }
                                },
                            };
                            let key = (index, stream_id);
                            s.pending.entry(key).or_default().extend_from_slice(&data);
                            Handler::flush_pending(&mut s, conn, stream_id);
                        }
                    }
                }
                Ok(Cmd::Close) => {
                    running.store(false, Ordering::Relaxed);
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    running.store(false, Ordering::Relaxed);
                    break;
                }
            }
        }

        if !running.load(Ordering::Relaxed) {
            endpoint.close(true);
            return Ok(());
        }

        let timeout = endpoint
            .timeout()
            .map(|t| t.min(STATS_INTERVAL))
            .unwrap_or(STATS_INTERVAL);
        // EINTR is routine on Android (GC/freezer signals) — retry, not die.
        if let Err(e) = poll.poll(&mut events, Some(timeout)) {
            if e.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(format!("poll: {e}"));
        }

        for event in events.iter() {
            let token = event.token();
            if token == WAKER_TOKEN || !event.is_readable() {
                continue;
            }
            // Drain the socket.
            loop {
                let (len, local, peer) = match sock.recv_from(&mut recv_buf, token) {
                    Ok(v) => v,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(e) => return Err(format!("socket recv: {e}")),
                };
                let pkt_info = PacketInfo {
                    src: peer,
                    dst: local,
                    time: Instant::now(),
                };
                if let Err(e) = endpoint.recv(&mut recv_buf[..len], &pkt_info) {
                    error!("endpoint recv failed: {e:?}");
                }
            }
        }

        endpoint.on_timeout(Instant::now());

        if last_stats.elapsed() >= STATS_INTERVAL {
            emit_stats(&mut endpoint, &shared);
            last_stats = Instant::now();
        }
    }
}

pub fn waker_token() -> Token {
    WAKER_TOKEN
}
