//! Standalone H3 test client for smoke-testing `tquic-vlm-server-interface` without a
//! phone. Opens one client-role `tquic::Endpoint`, connects, sends
//! `frames::write_frames(image, prompt)` as a single POST body, waits for
//! the response, prints status + body, and exits non-zero on anything but
//! 200.
//!
//! `verify_peer=false`, matching the server's self-signed demo cert and the
//! existing Android demo's own "quick and insecure" TLS posture -- this is
//! a smoke-test tool, not a template for a real deployment's TLS config.

use clap::Parser;
use std::cell::RefCell;
use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket as StdUdpSocket};
use std::rc::Rc;
use std::str::FromStr;
use std::time::{Duration, Instant};
use tquic::h3::connection::Http3Connection;
use tquic::h3::{Header, Http3Config, Http3Error, Http3Event, NameValue};
use tquic::{
    CongestionControlAlgorithm, Config, Connection, Endpoint, PacketInfo, PacketSendHandler, TlsConfig,
    TransportHandler,
};
use tquic_vlm_server_interface::frames;

#[derive(Parser, Debug)]
#[command(name = "tquic-vlm-test-client", about = "Smoke-test client for tquic-vlm-server-interface")]
struct Args {
    #[arg(long, default_value = "127.0.0.1")]
    host: String,
    #[arg(long, default_value_t = 19500)]
    port: u16,
    #[arg(long, default_value = "/v1/infer")]
    infer_path: String,
    #[arg(long, default_value = "h3")]
    alpn: String,
    /// SNI only -- verify_peer=false means this is never checked against a
    /// certificate SAN.
    #[arg(long, default_value = "tquic-vlm-server-interface")]
    server_name: String,
    #[arg(long)]
    image: std::path::PathBuf,
    #[arg(long)]
    prompt: String,
    #[arg(long, default_value_t = 30_000)]
    idle_timeout_ms: u64,
    #[arg(long, default_value_t = 10_000)]
    connect_timeout_ms: u64,
    #[arg(long, default_value_t = 20_000)]
    overall_timeout_ms: u64,
}

struct ClientConn {
    established: bool,
    h3: Option<Http3Connection>,
}

type ClientStates = Rc<RefCell<HashMap<u64, ClientConn>>>;

struct ClientHandler {
    states: ClientStates,
    h3_config: Rc<Http3Config>,
}

impl TransportHandler for ClientHandler {
    fn on_conn_created(&mut self, conn: &mut Connection) {
        if let Some(idx) = conn.index() {
            self.states.borrow_mut().insert(idx, ClientConn { established: false, h3: None });
        }
    }

    fn on_conn_established(&mut self, conn: &mut Connection) {
        let idx = match conn.index() {
            Some(i) => i,
            None => return,
        };
        let mut states = self.states.borrow_mut();
        if let Some(s) = states.get_mut(&idx) {
            s.established = true;
            if s.h3.is_none() {
                match Http3Connection::new_with_quic_conn(conn, &self.h3_config) {
                    Ok(h3) => s.h3 = Some(h3),
                    Err(e) => eprintln!("tquic-vlm-test-client: H3 setup failed: {e}"),
                }
            }
        }
    }

    fn on_conn_closed(&mut self, conn: &mut Connection) {
        let idx = conn.index().unwrap_or(u64::MAX);
        self.states.borrow_mut().remove(&idx);
    }

    fn on_stream_created(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_stream_readable(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_stream_writable(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_stream_closed(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_new_token(&mut self, _conn: &mut Connection, _token: Vec<u8>) {}
}

struct ClientSocket {
    sock: mio::net::UdpSocket,
}

impl PacketSendHandler for ClientSocket {
    fn on_packets_send(&self, pkts: &[(Vec<u8>, PacketInfo)]) -> tquic::Result<usize> {
        let mut sent = 0usize;
        for (buf, info) in pkts {
            match self.sock.send_to(buf, info.dst) {
                Ok(_) => sent += 1,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => {
                    eprintln!("tquic-vlm-test-client: send_to({}) failed: {e}", info.dst);
                    break;
                }
            }
        }
        Ok(sent)
    }
}

fn build_client_config(args: &Args) -> Config {
    let protos: Vec<Vec<u8>> = args.alpn.split(',').map(|s| s.trim().as_bytes().to_vec()).collect();
    let mut tls = TlsConfig::new_client_config(protos, false).expect("tquic-vlm-test-client: client TLS config");
    tls.set_verify(false);
    let mut cfg = Config::new().expect("tquic-vlm-test-client: tquic::Config::new");
    cfg.set_max_idle_timeout(args.idle_timeout_ms);
    cfg.set_max_handshake_timeout(args.connect_timeout_ms);
    cfg.set_initial_rtt(100);
    cfg.set_congestion_control_algorithm(CongestionControlAlgorithm::from_str("bbr").unwrap());
    cfg.enable_pacing(true);
    cfg.enable_dplpmtud(true);
    cfg.set_tls_config(tls);
    cfg
}

fn fail(msg: impl std::fmt::Display) -> ! {
    eprintln!("tquic-vlm-test-client: {msg}");
    std::process::exit(1);
}

fn main() {
    let args = Args::parse();
    let jpeg = std::fs::read(&args.image).unwrap_or_else(|e| fail(format!("failed to read --image {}: {e}", args.image.display())));
    let body = frames::write_frames(&jpeg, &args.prompt);

    let remote: SocketAddr = format!("{}:{}", args.host, args.port).parse().unwrap_or_else(|e| fail(format!("invalid --host/--port: {e}")));

    let mut poll = mio::Poll::new().unwrap_or_else(|e| fail(format!("mio::Poll::new: {e}")));
    let std_sock = StdUdpSocket::bind("0.0.0.0:0").unwrap_or_else(|e| fail(format!("bind local socket: {e}")));
    std_sock.set_nonblocking(true).unwrap_or_else(|e| fail(format!("set_nonblocking: {e}")));
    let local_addr = std_sock.local_addr().unwrap_or_else(|e| fail(format!("local_addr: {e}")));
    let mut mio_sock = mio::net::UdpSocket::from_std(std_sock);
    poll.registry()
        .register(&mut mio_sock, mio::Token(0), mio::Interest::READABLE)
        .unwrap_or_else(|e| fail(format!("register socket: {e}")));
    let socket = Rc::new(ClientSocket { sock: mio_sock });

    let states: ClientStates = Rc::new(RefCell::new(HashMap::new()));
    let h3_config = Rc::new(Http3Config::new().unwrap_or_else(|e| fail(format!("Http3Config::new: {e}"))));
    let handler = Box::new(ClientHandler { states: states.clone(), h3_config });
    let packet_sender: Rc<dyn PacketSendHandler> = socket.clone();
    let base_config = Box::new(Config::new().unwrap_or_else(|e| fail(format!("tquic::Config::new: {e}"))));
    let mut endpoint = Endpoint::new(base_config, false, handler, packet_sender);

    let connect_config = build_client_config(&args);
    let conn_idx = endpoint
        .connect(local_addr, remote, Some(args.server_name.as_str()), None, None, Some(&connect_config))
        .unwrap_or_else(|e| fail(format!("connect() failed: {e}")));

    let deadline = Instant::now() + Duration::from_millis(args.overall_timeout_ms);
    let mut stream_id: Option<u64> = None;
    let mut request_sent = false;
    let mut status: Option<u16> = None;
    let mut response_body = Vec::new();
    let mut finished = false;
    let mut scratch = vec![0u8; 65536];
    let mut events = mio::Events::with_capacity(16);

    loop {
        if Instant::now() >= deadline {
            fail("timed out waiting for a response");
        }

        let _ = endpoint.process_connections();

        loop {
            let mut states_ref = states.borrow_mut();
            let state = match states_ref.get_mut(&conn_idx) {
                Some(s) => s,
                None => break,
            };
            let conn = match endpoint.conn_get_mut(conn_idx) {
                Some(c) => c,
                None => break,
            };

            if !request_sent && state.established && state.h3.is_some() {
                let h3 = state.h3.as_mut().unwrap();
                match h3.stream_new(conn) {
                    Ok(sid) => {
                        let headers = [
                            Header::new(b":method", b"POST"),
                            Header::new(b":scheme", b"https"),
                            Header::new(b":authority", args.server_name.as_bytes()),
                            Header::new(b":path", args.infer_path.as_bytes()),
                        ];
                        if let Err(e) = h3.send_headers(conn, sid, &headers, false) {
                            fail(format!("send_headers failed: {e}"));
                        }
                        if let Err(e) = h3.send_body(conn, sid, bytes::Bytes::from(body.clone()), true) {
                            fail(format!("send_body failed: {e}"));
                        }
                        stream_id = Some(sid);
                        request_sent = true;
                    }
                    Err(e) => fail(format!("stream_new failed: {e}")),
                }
            }

            if state.h3.is_none() {
                break;
            }
            match state.h3.as_mut().unwrap().poll(conn) {
                Ok((sid, Http3Event::Headers { headers, fin })) if Some(sid) == stream_id => {
                    status = headers
                        .iter()
                        .find(|h| h.name() == b":status")
                        .and_then(|h| std::str::from_utf8(h.value()).ok())
                        .and_then(|s| s.parse::<u16>().ok());
                    if fin {
                        finished = true;
                    }
                }
                Ok((sid, Http3Event::Data)) if Some(sid) == stream_id => loop {
                    match state.h3.as_mut().unwrap().recv_body(conn, sid, &mut scratch) {
                        Ok(0) => break,
                        Ok(n) => response_body.extend_from_slice(&scratch[..n]),
                        Err(Http3Error::Done) => break,
                        Err(e) => fail(format!("recv_body failed: {e}")),
                    }
                },
                Ok((sid, Http3Event::Finished)) if Some(sid) == stream_id => {
                    finished = true;
                }
                Ok(_) => {}
                Err(Http3Error::Done) => break,
                Err(e) => fail(format!("H3 poll error: {e}")),
            }
        }

        if finished {
            break;
        }

        let timeout = endpoint
            .timeout()
            .map(|t| t.clamp(Duration::from_millis(1), Duration::from_millis(200)))
            .unwrap_or(Duration::from_millis(200));
        events.clear();
        if let Err(e) = poll.poll(&mut events, Some(timeout)) {
            if e.kind() != std::io::ErrorKind::Interrupted {
                eprintln!("tquic-vlm-test-client: mio poll failed: {e}");
            }
        }
        for ev in events.iter() {
            if ev.token() == mio::Token(0) {
                let mut buf = [0u8; 65535];
                loop {
                    match socket.sock.recv_from(&mut buf) {
                        Ok((n, src)) => {
                            let info = PacketInfo { src, dst: local_addr, time: Instant::now() };
                            let _ = endpoint.recv(&mut buf[..n], &info);
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                        Err(_) => break,
                    }
                }
            }
        }
        endpoint.on_timeout(Instant::now());
    }

    let status = status.unwrap_or(0);
    println!("status={status}");
    println!("{}", String::from_utf8_lossy(&response_body));
    std::process::exit(if status == 200 { 0 } else { 1 });
}
