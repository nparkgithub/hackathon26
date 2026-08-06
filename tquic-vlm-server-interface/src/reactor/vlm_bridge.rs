//! The VLM-worker -> reactor result channel: the one piece of cross-thread
//! communication in this binary. Everything else runs on the single
//! reactor thread; a real VLM call can take many seconds, so it's
//! dispatched to its own worker thread and reported back here via `mpsc` +
//! a `mio::Waker` -- the same primitive `tquic-jni`'s `waker.rs` uses to
//! make `poll()` return immediately instead of waiting out its timeout,
//! just one-directional (nothing blocks waiting on the result; the reactor
//! drains it opportunistically each loop turn).

use crate::error::VlmError;
use crate::vlm_client::{self, VlmConfig};
use std::sync::mpsc::Sender;
use std::sync::Arc;

pub struct VlmJobResult {
    pub conn_idx: u64,
    pub stream_id: u64,
    pub outcome: Result<String, VlmError>,
}

/// Spawns one worker thread to call the VLM backend and report the result.
/// Fire-and-forget from the caller's perspective.
pub fn spawn(
    conn_idx: u64,
    stream_id: u64,
    jpeg: Vec<u8>,
    prompt: String,
    cfg: VlmConfig,
    tx: Sender<VlmJobResult>,
    waker: Arc<mio::Waker>,
) {
    std::thread::spawn(move || {
        let outcome = vlm_client::infer(&cfg, &jpeg, &prompt);
        // If the reactor is gone (process shutting down), the send fails
        // silently -- there's no one left to notify, same rationale as
        // tquic-jni's CmdSender::send.
        if tx.send(VlmJobResult { conn_idx, stream_id, outcome }).is_ok() {
            let _ = waker.wake();
        }
    });
}
