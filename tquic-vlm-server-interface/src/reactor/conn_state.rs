//! Per-connection H3 state: the `Http3Connection` handle and, per stream,
//! buffered inbound body bytes plus any unflushed outbound response bytes.
//!
//! Unlike `tquic-jni`'s `InboundRequestState` (a `Mutex<VecDeque>` +
//! `Condvar` primitive built so a *different* thread can block-read from
//! it), nothing external ever reads this directly -- the reactor thread is
//! the only thing that touches it, so plain fields suffice. There is also
//! no generation-tagged handle registry here (contrast `tquic-jni`'s
//! `handle.rs`): that exists to survive many JNI-calling threads racing
//! double-close/use-after-free over an opaque `Long`, a problem this
//! single-threaded binary doesn't have.

use bytes::Bytes;
use std::collections::HashMap;
use tquic::h3::connection::Http3Connection;

#[derive(Default)]
pub struct PendingResponse {
    pub remaining: Bytes,
    pub fin: bool,
}

#[derive(Default)]
pub struct StreamState {
    pub body: Vec<u8>,
    /// Set once request headers have arrived and the HEADERS frame itself
    /// carried fin, OR once `Http3Event::Finished` fires -- i.e. "the whole
    /// request body is buffered, this stream is ready to act on."
    pub fin: bool,
    /// Set once this stream has been handed to the VLM bridge or responded
    /// to directly (rejection/error) -- prevents a late-arriving duplicate
    /// event from dispatching or responding twice.
    pub dispatched: bool,
    pub pending_write: Option<PendingResponse>,
}

#[derive(Default)]
pub struct ServerConnState {
    pub h3: Option<Http3Connection>,
    pub streams: HashMap<u64, StreamState>,
}
