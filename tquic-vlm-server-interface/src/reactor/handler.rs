//! `TransportHandler` for the (server-only) `Endpoint`. Mirrors
//! `tquic-jni`'s `ServerHandler`, including the `on_conn_established` (not
//! `on_conn_created`) placement for creating the `Http3Connection`:
//! opening the H3 control stream needs the peer's advertised
//! `initial_max_streams_uni`, which isn't negotiated yet at
//! `on_conn_created` time (that fires on the raw Initial packet, before
//! transport parameters are processed). See
//! `tquic-jni/src/runtime/driver.rs`'s `ServerHandler::on_conn_established`
//! doc comment for the full incident writeup that pinned this down --
//! calling it at `on_conn_created` fails every connection with
//! `TransportError(StreamLimitError)` on the control stream.

use crate::reactor::conn_state::ServerConnState;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use tquic::h3::connection::Http3Connection;
use tquic::h3::Http3Config;
use tquic::{Connection, TransportHandler};

pub type ServerStates = Rc<RefCell<HashMap<u64, ServerConnState>>>;

pub struct ServerHandler {
    pub states: ServerStates,
    pub h3_config: Rc<Http3Config>,
}

impl TransportHandler for ServerHandler {
    fn on_conn_created(&mut self, conn: &mut Connection) {
        if let Some(idx) = conn.index() {
            self.states.borrow_mut().insert(idx, ServerConnState::default());
        }
    }

    fn on_conn_established(&mut self, conn: &mut Connection) {
        let idx = match conn.index() {
            Some(i) => i,
            None => return,
        };
        let mut states = self.states.borrow_mut();
        let state = match states.get_mut(&idx) {
            Some(s) => s,
            None => return,
        };
        if state.h3.is_none() {
            match Http3Connection::new_with_quic_conn(conn, &self.h3_config) {
                Ok(h3) => state.h3 = Some(h3),
                Err(e) => log::warn!("tquic-vlm-server-interface: H3 setup failed for new connection: {e}"),
            }
        }
    }

    fn on_conn_closed(&mut self, conn: &mut Connection) {
        let idx = conn.index().unwrap_or(u64::MAX);
        self.states.borrow_mut().remove(&idx);
    }

    // Deliberately no-ops: request/response data is driven pull-style by
    // Reactor::drive_h3() via Http3Connection::poll(), called from the main
    // loop after process_connections() returns -- not from these callbacks.
    // No field on this struct is (or may become) anything that would make
    // calling into the VLM bridge from here safe/desirable: these fire
    // synchronously inside Endpoint::recv()/process_connections(), and
    // blocking there would stall ACK generation and loss detection for
    // every connection on the endpoint for as long as the call took.
    fn on_stream_created(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_stream_readable(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_stream_writable(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_stream_closed(&mut self, _conn: &mut Connection, _stream_id: u64) {}
    fn on_new_token(&mut self, _conn: &mut Connection, _token: Vec<u8>) {}
}
