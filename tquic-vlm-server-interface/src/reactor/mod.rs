//! The reactor: a single thread owning the one server-only `tquic::Endpoint`
//! this binary ever creates, run directly on `main()`'s thread (unlike
//! `tquic-jni`, which lazily spawns a dedicated reactor thread because JNI
//! entry points fire from arbitrary Java threads at arbitrary times --
//! `main()` here is the one deterministic entry point, so there's no
//! `OnceLock<CmdSender>`/`Cmd` enum/`Reply<T>` control-plane plumbing at
//! all). The only cross-thread channel in this binary is the
//! VLM-worker -> reactor result path (see `vlm_bridge.rs`).
//!
//! `tquic::Endpoint` is `!Send` -- see the `assert_not_impl_any!` below.

pub mod conn_state;
pub mod handler;
pub mod socket;
pub mod vlm_bridge;

use crate::cli::ValidatedArgs;
use crate::error::RequestError;
use crate::frames;
use crate::vlm_client::VlmConfig;
use conn_state::{PendingResponse, ServerConnState};
use handler::{ServerHandler, ServerStates};
use socket::ServerSocket;
use vlm_bridge::VlmJobResult;

use bytes::Bytes;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tquic::h3::{Header, Http3Config, Http3Error, Http3Event, NameValue};
use tquic::{Config, Connection, Endpoint, PacketInfo, PacketSendHandler};

static_assertions::assert_not_impl_any!(Endpoint: Send);

const SOCKET_TOKEN: mio::Token = mio::Token(0);
const WAKER_TOKEN: mio::Token = mio::Token(1);
const POLL_MIN: Duration = Duration::from_millis(1);
const POLL_MAX: Duration = Duration::from_millis(1000);

pub struct Reactor {
    poll: mio::Poll,
    socket: Rc<ServerSocket>,
    endpoint: Endpoint,
    states: ServerStates,
    scratch: Vec<u8>,

    infer_path: String,
    max_body_bytes: usize,
    max_inflight_vlm: usize,
    inflight_vlm: usize,
    vlm_cfg: VlmConfig,

    vlm_tx: Sender<VlmJobResult>,
    vlm_rx: Receiver<VlmJobResult>,
    waker: Arc<mio::Waker>,
}

impl Reactor {
    pub fn new(v: &ValidatedArgs, config: Box<Config>) -> std::io::Result<Self> {
        let poll = mio::Poll::new()?;
        let waker = Arc::new(mio::Waker::new(poll.registry(), WAKER_TOKEN)?);
        let socket = Rc::new(ServerSocket::bind(poll.registry(), SOCKET_TOKEN, v.args.bind)?);

        let states: ServerStates = Rc::new(RefCell::new(HashMap::new()));
        let h3_config =
            Rc::new(Http3Config::new().expect("tquic-vlm-server-interface: default Http3Config::new() cannot fail"));
        let handler = Box::new(ServerHandler { states: states.clone(), h3_config });
        let packet_sender: Rc<dyn PacketSendHandler> = socket.clone();
        let endpoint = Endpoint::new(config, true, handler, packet_sender);

        let (vlm_tx, vlm_rx) = mpsc::channel();

        Ok(Reactor {
            poll,
            socket,
            endpoint,
            states,
            scratch: vec![0u8; 65536],
            infer_path: v.args.infer_path.clone(),
            max_body_bytes: v.args.max_body_bytes,
            max_inflight_vlm: v.args.max_inflight_vlm,
            inflight_vlm: 0,
            vlm_cfg: VlmConfig {
                base_url: v.args.vlm_base_url.clone(),
                model: v.args.vlm_model.clone(),
                timeout: v.vlm_timeout(),
            },
            vlm_tx,
            vlm_rx,
            waker,
        })
    }

    pub fn run(mut self) -> ! {
        log::info!(
            "tquic-vlm-server-interface: listening on {} (infer-path={}, vlm-base-url={})",
            self.socket.local_addr(),
            self.infer_path,
            self.vlm_cfg.base_url,
        );
        let mut events = mio::Events::with_capacity(128);
        loop {
            self.drain_vlm_results();

            let _ = self.endpoint.process_connections();
            self.drive_h3();
            self.flush_pending_writes();

            // tquic's Endpoint::timeout() can return a few-hundred-
            // microsecond value whenever there's queued/pending work --
            // polling with that raw would spin the CPU at kHz. Clamp to a
            // sane range; on an idle endpoint this caps wakeups at ~1/sec.
            let timeout = self.endpoint.timeout().map(|t| t.clamp(POLL_MIN, POLL_MAX)).unwrap_or(POLL_MAX);

            events.clear();
            if let Err(e) = self.poll.poll(&mut events, Some(timeout)) {
                if e.kind() != std::io::ErrorKind::Interrupted {
                    log::error!("tquic-vlm-server-interface: mio poll failed: {e}");
                }
                continue;
            }

            for ev in events.iter() {
                if ev.token() == SOCKET_TOKEN {
                    self.recv_all();
                }
                // WAKER_TOKEN needs no per-event handling -- its only job is
                // making poll() return promptly; drain_vlm_results() at the
                // top of the next loop turn does the actual work.
            }

            self.endpoint.on_timeout(Instant::now());
        }
    }

    fn recv_all(&mut self) {
        let mut buf = [0u8; 65535];
        loop {
            match self.socket.recv_from(&mut buf) {
                Ok((n, src, dst)) => {
                    let info = PacketInfo { src, dst, time: Instant::now() };
                    if let Err(e) = self.endpoint.recv(&mut buf[..n], &info) {
                        // A malformed or unroutable datagram must never
                        // kill the reactor loop.
                        log::trace!("tquic-vlm-server-interface: endpoint.recv from {src} failed: {e}");
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => {
                    log::warn!("tquic-vlm-server-interface: recv_from failed: {e}");
                    break;
                }
            }
        }
    }

    fn drain_vlm_results(&mut self) {
        while let Ok(result) = self.vlm_rx.try_recv() {
            self.inflight_vlm = self.inflight_vlm.saturating_sub(1);
            let conn = match self.endpoint.conn_get_mut(result.conn_idx) {
                Some(c) => c,
                None => {
                    log::debug!(
                        "tquic-vlm-server-interface: dropping VLM result for closed connection {}",
                        result.conn_idx
                    );
                    continue;
                }
            };
            let mut states = self.states.borrow_mut();
            let state = match states.get_mut(&result.conn_idx) {
                Some(s) => s,
                None => continue,
            };
            match result.outcome {
                Ok((text, content_type)) => {
                    queue_response(state, conn, result.stream_id, 200, content_type.as_bytes(), text.into_bytes())
                }
                Err(e) => {
                    let req_err = RequestError::from(e);
                    queue_response(
                        state,
                        conn,
                        result.stream_id,
                        req_err.status(),
                        b"text/plain",
                        req_err.to_string().into_bytes(),
                    );
                }
            }
        }
    }

    /// Polls each connection's H3 events pull-style (mirroring
    /// `tquic-jni`'s `drive_server_h3`) and, once a request's body is fully
    /// buffered, hands it to `dispatch_ready`.
    fn drive_h3(&mut self) {
        let conn_indices: Vec<u64> = self.states.borrow().keys().copied().collect();
        for idx in conn_indices {
            let conn = match self.endpoint.conn_get_mut(idx) {
                Some(c) => c,
                None => continue,
            };
            loop {
                let mut states = self.states.borrow_mut();
                let state = match states.get_mut(&idx) {
                    Some(s) => s,
                    None => break,
                };
                if state.h3.is_none() {
                    break;
                }
                let event = state.h3.as_mut().unwrap().poll(conn);
                match event {
                    Ok((stream_id, Http3Event::Headers { headers, fin })) => {
                        let (method, path) = split_pseudo(&headers);
                        state.streams.entry(stream_id).or_default();
                        if method != "POST" {
                            queue_response(state, conn, stream_id, 405, b"text/plain", b"method not allowed".to_vec());
                            mark_dispatched(state, stream_id);
                        } else if path != self.infer_path {
                            queue_response(state, conn, stream_id, 404, b"text/plain", b"not found".to_vec());
                            mark_dispatched(state, stream_id);
                        } else if fin {
                            if let Some(s) = state.streams.get_mut(&stream_id) {
                                s.fin = true;
                            }
                        }
                    }
                    Ok((stream_id, Http3Event::Data)) => {
                        let already_dispatched = state.streams.get(&stream_id).map(|s| s.dispatched).unwrap_or(false);
                        if already_dispatched {
                            // Already responded/rejected -- stop caring
                            // about the rest of the request body instead of
                            // draining it for no reason.
                            let _ = conn.stream_shutdown(stream_id, tquic::Shutdown::Read, 0);
                        } else {
                            pump_body(state, conn, stream_id, &mut self.scratch, self.max_body_bytes);
                        }
                    }
                    Ok((stream_id, Http3Event::Finished)) => {
                        if let Some(s) = state.streams.get_mut(&stream_id) {
                            s.fin = true;
                        }
                    }
                    Ok((stream_id, Http3Event::Reset(code))) => {
                        log::debug!("tquic-vlm-server-interface: stream {stream_id} reset by peer, code {code}");
                        state.streams.remove(&stream_id);
                    }
                    Ok((_, Http3Event::GoAway)) | Ok((_, Http3Event::PriorityUpdate)) => {}
                    Err(Http3Error::Done) => break,
                    Err(e) => {
                        log::debug!("tquic-vlm-server-interface: H3 poll error on connection {idx}: {e}");
                        state.h3 = None;
                        break;
                    }
                }
            }
            // `conn`'s last use was inside the loop above, so its borrow of
            // `self.endpoint` ends here -- touching other `self` fields in
            // `dispatch_ready` (vlm_tx/waker/inflight counter) is fine.
            self.dispatch_ready(idx);
        }
    }

    /// Scans one connection's streams for a fully-buffered, not-yet-
    /// dispatched request, parses its frames, and either responds
    /// immediately (parse error / over capacity) or spawns a VLM worker.
    fn dispatch_ready(&mut self, idx: u64) {
        let mut states = self.states.borrow_mut();
        let state = match states.get_mut(&idx) {
            Some(s) => s,
            None => return,
        };
        let ready: Vec<u64> = state.streams.iter().filter(|(_, s)| s.fin && !s.dispatched).map(|(id, _)| *id).collect();

        for stream_id in ready {
            let body = {
                let s = state.streams.get_mut(&stream_id).unwrap();
                s.dispatched = true;
                std::mem::take(&mut s.body)
            };
            match frames::read_request(&body) {
                Ok(request) => {
                    if self.inflight_vlm >= self.max_inflight_vlm {
                        if let Some(conn) = self.endpoint.conn_get_mut(idx) {
                            queue_response(state, conn, stream_id, 503, b"text/plain", b"server busy, try again".to_vec());
                        }
                        continue;
                    }
                    self.inflight_vlm += 1;
                    vlm_bridge::spawn(
                        idx,
                        stream_id,
                        request,
                        self.vlm_cfg.clone(),
                        self.vlm_tx.clone(),
                        self.waker.clone(),
                    );
                }
                Err(e) => {
                    if let Some(conn) = self.endpoint.conn_get_mut(idx) {
                        let req_err = RequestError::from(e);
                        queue_response(
                            state,
                            conn,
                            stream_id,
                            req_err.status(),
                            b"text/plain",
                            req_err.to_string().into_bytes(),
                        );
                    }
                }
            }
        }
    }

    /// Retries any response bytes that a previous `send_body` couldn't
    /// accept in full (QUIC flow-control bound -- see `try_flush_stream`'s
    /// doc comment). Run once per reactor turn; this binary skips wiring
    /// `TransportHandler::on_stream_writable` for the same retry (it's a
    /// no-op in `tquic-jni` too) since scanning here every ~1s-bounded loop
    /// turn is simpler and correct enough for this workload -- a stuck
    /// write is retried well within any reasonable client timeout.
    fn flush_pending_writes(&mut self) {
        let pairs: Vec<(u64, u64)> = self
            .states
            .borrow()
            .iter()
            .flat_map(|(idx, state)| {
                state.streams.iter().filter(|(_, s)| s.pending_write.is_some()).map(move |(sid, _)| (*idx, *sid))
            })
            .collect();
        for (idx, stream_id) in pairs {
            let conn = match self.endpoint.conn_get_mut(idx) {
                Some(c) => c,
                None => continue,
            };
            let mut states = self.states.borrow_mut();
            if let Some(state) = states.get_mut(&idx) {
                try_flush_stream(state, conn, stream_id);
            }
        }
    }
}

fn mark_dispatched(state: &mut ServerConnState, stream_id: u64) {
    if let Some(s) = state.streams.get_mut(&stream_id) {
        s.dispatched = true;
    }
}

fn split_pseudo(headers: &[Header]) -> (String, String) {
    let mut method = String::new();
    let mut path = String::new();
    for h in headers {
        match h.name() {
            b":method" => method = String::from_utf8_lossy(h.value()).into_owned(),
            b":path" => path = String::from_utf8_lossy(h.value()).into_owned(),
            _ => {}
        }
    }
    (method, path)
}

/// Reads request-body bytes off the stream, accumulating into
/// `StreamState::body`. If the running total exceeds `max_body_bytes`,
/// stops buffering and responds `413` immediately (this is the one place
/// `conn` is needed alongside `state` for an error response outside
/// `dispatch_ready`, since the over-budget stream never reaches a clean
/// `fin` the normal way).
fn pump_body(state: &mut ServerConnState, conn: &mut Connection, stream_id: u64, scratch: &mut [u8], max_body_bytes: usize) {
    let h3 = match state.h3.as_mut() {
        Some(h) => h,
        None => return,
    };
    loop {
        match h3.recv_body(conn, stream_id, scratch) {
            Ok(0) => break,
            Ok(n) => {
                let over_budget = state
                    .streams
                    .get(&stream_id)
                    .map(|s| s.body.len() + n > max_body_bytes)
                    .unwrap_or(false);
                if over_budget {
                    if let Some(s) = state.streams.get_mut(&stream_id) {
                        s.body.clear();
                        s.dispatched = true;
                    }
                    queue_response(state, conn, stream_id, 413, b"text/plain", b"payload too large".to_vec());
                    return;
                }
                if let Some(s) = state.streams.get_mut(&stream_id) {
                    s.body.extend_from_slice(&scratch[..n]);
                }
            }
            Err(Http3Error::Done) => break,
            Err(e) => {
                log::debug!("tquic-vlm-server-interface: recv_body failed for stream {stream_id}: {e}");
                state.streams.remove(&stream_id);
                return;
            }
        }
    }
    if conn.stream_finished(stream_id) {
        if let Some(s) = state.streams.get_mut(&stream_id) {
            s.fin = true;
        }
    }
}

/// Sends response headers (status 200/4xx/5xx + `content-type`) once, queues
/// the body, and makes a first flush attempt. `content_type` varies for a
/// 200: `text/plain` for the `Simple`-request extracted-answer path,
/// `application/json` for the `OpenAiPassthrough` relayed-verbatim path
/// (see `vlm_bridge::VlmJobResult`) -- every non-200 path keeps `text/plain`.
fn queue_response(
    state: &mut ServerConnState,
    conn: &mut Connection,
    stream_id: u64,
    status: u16,
    content_type: &[u8],
    body: Vec<u8>,
) {
    let h3 = match state.h3.as_mut() {
        Some(h) => h,
        None => return,
    };
    let headers = [Header::new(b":status", status.to_string().as_bytes()), Header::new(b"content-type", content_type)];
    if let Err(e) = h3.send_headers(conn, stream_id, &headers, false) {
        log::debug!("tquic-vlm-server-interface: send_headers failed for stream {stream_id}: {e}");
        state.streams.remove(&stream_id);
        return;
    }
    if let Some(s) = state.streams.get_mut(&stream_id) {
        s.pending_write = Some(PendingResponse { remaining: Bytes::from(body), fin: true });
    }
    try_flush_stream(state, conn, stream_id);
}

/// `Http3Connection::send_body` returns bytes *actually accepted* (bounded
/// by QUIC flow control), not all-or-nothing. There's no external caller to
/// retry on our behalf (contrast `tquic-jni`, which forwards this count
/// back to Kotlin), so this tracks and retries any remainder itself:
/// called right after `queue_response` queues a new response, and once per
/// reactor turn from `Reactor::flush_pending_writes` for anything still
/// outstanding.
fn try_flush_stream(state: &mut ServerConnState, conn: &mut Connection, stream_id: u64) {
    let h3 = match state.h3.as_mut() {
        Some(h) => h,
        None => return,
    };
    let done = {
        let s = match state.streams.get_mut(&stream_id) {
            Some(s) => s,
            None => return,
        };
        let pending = match s.pending_write.as_mut() {
            Some(p) => p,
            None => return,
        };
        match h3.send_body(conn, stream_id, pending.remaining.clone(), pending.fin) {
            Ok(n) if n >= pending.remaining.len() => true,
            Ok(n) => {
                pending.remaining = pending.remaining.slice(n..);
                false
            }
            // No flow-control window right now -- leave it queued, retry later.
            Err(Http3Error::Done) => false,
            Err(e) => {
                log::debug!("tquic-vlm-server-interface: send_body failed for stream {stream_id}: {e}");
                true // drop it -- the peer almost certainly reset the stream
            }
        }
    };
    if done {
        state.streams.remove(&stream_id);
    }
}
