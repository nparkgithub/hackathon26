//! Local HTTP/3 listener that tunnels requests over the MPQUIC connection.
//!
//! Layout when the client app enables "H3 RX":
//!
//! ```text
//! external h3 client --HTTP/3--> [local h3 endpoint] --relay frame-->
//!     MPQUIC tunnel stream --> peer engine (server app) --> response
//!     --relay frame--> local h3 endpoint --HTTP/3 response--> h3 client
//! ```
//!
//! The local endpoint is a real QUIC/HTTP3 server (ALPN "h3"), so any
//! standard HTTP/3 client works, including large JPEG bodies. Requests and
//! responses cross the tunnel as length-prefixed frames on their own
//! bidirectional QUIC stream — one stream per request, which keeps large
//! image uploads from head-of-line blocking each other and lets the reply
//! be matched to its request without extra bookkeeping.

use std::cell::RefCell;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::rc::Rc;

use bytes::Bytes;
use log::{error, info};
use mio::Registry;
use tquic::h3::connection::Http3Connection;
use tquic::h3::{Header, Http3Config, Http3Event, NameValue};
use tquic::{Config, Connection, Endpoint, TlsConfig, TransportHandler};

use crate::socket::QuicSocket;

/// Relay frame magic ("H3RQ" request, "H3RS" response).
pub const MAGIC_REQ: &[u8; 4] = b"H3RQ";
pub const MAGIC_RES: &[u8; 4] = b"H3RS";

/// A request or response carried across the tunnel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayFrame {
    pub is_request: bool,
    /// `:method :path content-type ...` — only the fields we relay.
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl RelayFrame {
    /// Wire format: magic | u32 header_len | u32 body_len | headers | body,
    /// headers being `name\x1Fvalue\x1E...`. Sizes are big-endian.
    pub fn encode(&self) -> Vec<u8> {
        let hdr: String = self
            .headers
            .iter()
            .map(|(n, v)| format!("{n}\u{1F}{v}"))
            .collect::<Vec<_>>()
            .join("\u{1E}");
        let hdr = hdr.as_bytes();
        let mut out = Vec::with_capacity(12 + hdr.len() + self.body.len());
        out.extend_from_slice(if self.is_request { MAGIC_REQ } else { MAGIC_RES });
        out.extend_from_slice(&(hdr.len() as u32).to_be_bytes());
        out.extend_from_slice(&(self.body.len() as u32).to_be_bytes());
        out.extend_from_slice(hdr);
        out.extend_from_slice(&self.body);
        out
    }

    /// Parse one frame from the front of `buf`; returns the frame and how
    /// many bytes it consumed, or None if more data is needed.
    pub fn parse(buf: &[u8]) -> Option<(RelayFrame, usize)> {
        if buf.len() < 12 {
            return None;
        }
        let is_request = match &buf[0..4] {
            m if m == MAGIC_REQ => true,
            m if m == MAGIC_RES => false,
            _ => return None,
        };
        let hlen = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]) as usize;
        let blen = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]) as usize;
        let total = 12 + hlen + blen;
        if buf.len() < total {
            return None;
        }
        let headers = std::str::from_utf8(&buf[12..12 + hlen])
            .ok()?
            .split('\u{1E}')
            .filter(|s| !s.is_empty())
            .filter_map(|kv| {
                let mut it = kv.splitn(2, '\u{1F}');
                Some((it.next()?.to_string(), it.next()?.to_string()))
            })
            .collect();
        Some((
            RelayFrame {
                is_request,
                headers,
                body: buf[12 + hlen..total].to_vec(),
            },
            total,
        ))
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }

    /// Convert relayed headers into tquic h3 headers.
    pub fn to_h3_headers(&self) -> Vec<Header> {
        self.headers
            .iter()
            .map(|(n, v)| Header::new(n.as_bytes(), v.as_bytes()))
            .collect()
    }
}

/// Accumulates one in-flight HTTP/3 request on the local listener.
#[derive(Default)]
pub struct PendingRequest {
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

/// Shared state of the local HTTP/3 endpoint: its per-connection HTTP/3
/// state machines, in-flight requests, and the queue of complete requests
/// the engine still has to tunnel.
#[derive(Default)]
pub struct H3Shared {
    pub h3_conns: HashMap<u64, Http3Connection>,
    pub pending: HashMap<(u64, u64), PendingRequest>,
    /// (conn_index, stream_id, request) ready to relay.
    pub completed: Vec<(u64, u64, RelayFrame)>,
}

struct H3Handler {
    shared: Rc<RefCell<H3Shared>>,
    buf: Vec<u8>,
}

impl H3Handler {
    fn drain_events(&mut self, conn: &mut Connection) {
        let index = conn.index().unwrap_or(u64::MAX);
        let mut shared = self.shared.borrow_mut();
        let Some(mut h3) = shared.h3_conns.remove(&index) else {
            return;
        };
        loop {
            match h3.poll(conn) {
                Ok((stream_id, Http3Event::Headers { headers, .. })) => {
                    let hdrs = headers
                        .iter()
                        .map(|h| {
                            (
                                String::from_utf8_lossy(h.name()).to_string(),
                                String::from_utf8_lossy(h.value()).to_string(),
                            )
                        })
                        .collect();
                    shared.pending.insert(
                        (index, stream_id),
                        PendingRequest {
                            headers: hdrs,
                            body: Vec::new(),
                        },
                    );
                }
                Ok((stream_id, Http3Event::Data)) => {
                    while let Ok(read) = h3.recv_body(conn, stream_id, &mut self.buf) {
                        if read == 0 {
                            break;
                        }
                        if let Some(p) = shared.pending.get_mut(&(index, stream_id)) {
                            p.body.extend_from_slice(&self.buf[..read]);
                        }
                    }
                }
                Ok((stream_id, Http3Event::Finished)) => {
                    if let Some(p) = shared.pending.remove(&(index, stream_id)) {
                        info!(
                            "h3 listener: request complete on stream {stream_id}, {} B body",
                            p.body.len()
                        );
                        shared.completed.push((
                            index,
                            stream_id,
                            RelayFrame {
                                is_request: true,
                                headers: p.headers,
                                body: p.body,
                            },
                        ));
                    }
                }
                Ok((_, Http3Event::Reset(_))) => {}
                Ok((_, Http3Event::PriorityUpdate)) => {}
                Ok((_, Http3Event::GoAway)) => {}
                Err(tquic::h3::Http3Error::Done) => break,
                Err(e) => {
                    error!("h3 listener: {e:?}");
                    break;
                }
            }
        }
        shared.h3_conns.insert(index, h3);
    }
}

impl TransportHandler for H3Handler {
    fn on_conn_created(&mut self, conn: &mut Connection) {
        info!("h3 listener: connection {} created", conn.trace_id());
    }

    fn on_conn_established(&mut self, conn: &mut Connection) {
        let index = conn.index().unwrap_or(u64::MAX);
        match Http3Config::new().and_then(|cfg| Http3Connection::new_with_quic_conn(conn, &cfg)) {
            Ok(h3) => {
                self.shared.borrow_mut().h3_conns.insert(index, h3);
                info!("h3 listener: HTTP/3 connection ready ({})", conn.trace_id());
            }
            Err(e) => error!("h3 listener: cannot start HTTP/3: {e:?}"),
        }
    }

    fn on_conn_closed(&mut self, conn: &mut Connection) {
        let index = conn.index().unwrap_or(u64::MAX);
        let mut shared = self.shared.borrow_mut();
        shared.h3_conns.remove(&index);
        shared.pending.retain(|(c, _), _| *c != index);
    }

    fn on_stream_created(&mut self, _conn: &mut Connection, _stream_id: u64) {}

    fn on_stream_readable(&mut self, conn: &mut Connection, _stream_id: u64) {
        self.drain_events(conn);
    }

    fn on_stream_writable(&mut self, _conn: &mut Connection, _stream_id: u64) {}

    fn on_stream_closed(&mut self, _conn: &mut Connection, _stream_id: u64) {}

    fn on_new_token(&mut self, _conn: &mut Connection, _token: Vec<u8>) {}
}

/// The local HTTP/3 server endpoint an external client talks to.
pub struct H3Listener {
    pub endpoint: Endpoint,
    pub sock: Rc<QuicSocket>,
    pub shared: Rc<RefCell<H3Shared>>,
    pub port: u16,
}

impl H3Listener {
    pub fn new(
        port: u16,
        cert: &str,
        key: &str,
        registry: &Registry,
        token_base: usize,
    ) -> Result<Self, String> {
        let mut config = Config::new().map_err(|e| e.to_string())?;
        // Generous: an external client may hold the connection open between
        // uploads, and a slow multi-MB POST must not trip the timer.
        config.set_max_idle_timeout(300_000);
        config.set_initial_max_streams_bidi(128);
        config.set_recv_udp_payload_size(65527);
        // Large JPEG bodies: allow generous stream/connection flow control.
        config.set_initial_max_data(64 * 1024 * 1024);
        config.set_initial_max_stream_data_bidi_remote(32 * 1024 * 1024);
        config.set_initial_max_stream_data_bidi_local(32 * 1024 * 1024);
        config.set_initial_max_stream_data_uni(1024 * 1024);
        config.set_initial_max_streams_uni(16);
        let tls = TlsConfig::new_server_config(cert, key, vec![b"h3".to_vec()], true)
            .map_err(|e| format!("h3 TLS config: {e}"))?;
        config.set_tls_config(tls);

        let listen: SocketAddr = format!("0.0.0.0:{port}")
            .parse()
            .map_err(|e| format!("bad h3 port {port}: {e}"))?;
        let sock = QuicSocket::new_with_base(&listen, registry, token_base)
            .map_err(|e| format!("h3 bind {listen}: {e}"))?;
        let sock = Rc::new(sock);

        let shared = Rc::new(RefCell::new(H3Shared::default()));
        let handler = H3Handler {
            shared: shared.clone(),
            buf: vec![0u8; 65536],
        };
        let endpoint = Endpoint::new(Box::new(config), true, Box::new(handler), sock.clone());

        Ok(Self {
            endpoint,
            sock,
            shared,
            port,
        })
    }

    /// Send a relayed response back to the external HTTP/3 client.
    pub fn send_response(&mut self, conn_index: u64, stream_id: u64, frame: &RelayFrame) {
        let Some(conn) = self.endpoint.conn_get_mut(conn_index) else {
            error!("h3 listener: connection {conn_index} gone, dropping response");
            return;
        };
        let mut shared = self.shared.borrow_mut();
        let Some(mut h3) = shared.h3_conns.remove(&conn_index) else {
            return;
        };
        let mut headers: Vec<Header> = frame.to_h3_headers();
        if frame.header(":status").is_none() {
            headers.insert(0, Header::new(b":status", b"200"));
        }
        if let Err(e) = h3.send_headers(conn, stream_id, &headers, frame.body.is_empty()) {
            error!("h3 listener: send_headers: {e:?}");
        } else if !frame.body.is_empty() {
            let body = Bytes::from(frame.body.clone());
            let mut sent = 0usize;
            while sent < body.len() {
                match h3.send_body(conn, stream_id, body.slice(sent..), true) {
                    Ok(0) | Err(tquic::h3::Http3Error::Done) => break,
                    Ok(n) => sent += n,
                    Err(e) => {
                        error!("h3 listener: send_body: {e:?}");
                        break;
                    }
                }
            }
            info!("h3 listener: responded {sent}/{} B on stream {stream_id}", body.len());
        }
        shared.h3_conns.insert(conn_index, h3);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(body: Vec<u8>) -> RelayFrame {
        RelayFrame {
            is_request: true,
            headers: vec![
                (":method".into(), "POST".into()),
                (":path".into(), "/upload.jpg".into()),
                ("content-type".into(), "image/jpeg".into()),
            ],
            body,
        }
    }

    #[test]
    fn roundtrip_encode_parse() {
        let f = sample(vec![0xFF, 0xD8, 0xFF, 0xE0, 0x00]);
        let bytes = f.encode();
        let (got, used) = RelayFrame::parse(&bytes).expect("parse");
        assert_eq!(used, bytes.len());
        assert_eq!(got, f);
        assert_eq!(got.header(":path"), Some("/upload.jpg"));
    }

    #[test]
    fn parse_needs_full_frame() {
        let bytes = sample(vec![1, 2, 3, 4, 5, 6, 7, 8]).encode();
        for cut in [0, 5, 11, bytes.len() - 1] {
            assert!(RelayFrame::parse(&bytes[..cut]).is_none(), "cut {cut}");
        }
        assert!(RelayFrame::parse(&bytes).is_some());
    }

    #[test]
    fn parses_frames_back_to_back_and_large_bodies() {
        // A JPEG-sized body plus a following frame in the same buffer.
        let big = sample(vec![0xAB; 2 * 1024 * 1024]);
        let small = RelayFrame {
            is_request: false,
            headers: vec![(":status".into(), "200".into())],
            body: b"ok".to_vec(),
        };
        let mut buf = big.encode();
        buf.extend_from_slice(&small.encode());

        let (f1, used1) = RelayFrame::parse(&buf).expect("first");
        assert_eq!(f1.body.len(), 2 * 1024 * 1024);
        let (f2, used2) = RelayFrame::parse(&buf[used1..]).expect("second");
        assert!(!f2.is_request);
        assert_eq!(f2.header(":status"), Some("200"));
        assert_eq!(used1 + used2, buf.len());
    }

    #[test]
    fn rejects_foreign_bytes() {
        assert!(RelayFrame::parse(b"NOPE\0\0\0\0\0\0\0\0").is_none());
    }
}
