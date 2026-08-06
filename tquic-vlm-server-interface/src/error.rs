//! Error types for the frame parser and the VLM bridge, plus the mapping
//! onto H3 response status codes (see the table in the top-level plan this
//! crate was built from). Keeping error responses in the same status+text
//! shape as a success response needs no changes to the existing Android
//! demo's status-code branching (`TquicDemoController.kt`: `200 -> Ok`,
//! `else -> Failed(...)`).

use std::fmt;

#[derive(Debug, thiserror::Error)]
pub enum FrameError {
    #[error("invalid request JSON: {0}")]
    InvalidJson(#[from] serde_json::Error),
    #[error("invalid base64 in \"jpeg\" field: {0}")]
    InvalidBase64(#[from] base64::DecodeError),
    #[error("request body matched neither {{\"jpeg\",\"prompt\"}} nor {{\"model\",\"messages\"}}")]
    UnrecognizedShape,
}

#[derive(Debug, thiserror::Error)]
pub enum VlmError {
    #[error("could not reach VLM backend at {url}: {source}")]
    Transport { url: String, source: Box<ureq::Error> },
    #[error("VLM backend returned HTTP {status}: {body}")]
    HttpStatus { status: u16, body: String },
    #[error("VLM backend response was not valid JSON: {0}")]
    Decode(#[from] serde_json::Error),
    #[error("VLM backend response had no choices[0].message.content")]
    MissingContent,
    #[error("VLM backend request timed out after {0:?}")]
    Timeout(std::time::Duration),
}

/// Everything that can go wrong while handling one inbound request, mapped
/// to an H3 status code + short human-readable body text. Constructed at
/// the point of failure and carried across the VLM-worker -> reactor
/// channel unchanged (see `reactor/vlm_bridge.rs`).
#[derive(Debug)]
pub enum RequestError {
    Frame(FrameError),
    BodyTooLarge { max: usize },
    NotFound,
    MethodNotAllowed,
    Busy,
    Vlm(VlmError),
}

impl RequestError {
    pub fn status(&self) -> u16 {
        match self {
            RequestError::Frame(_) => 400,
            RequestError::BodyTooLarge { .. } => 413,
            RequestError::NotFound => 404,
            RequestError::MethodNotAllowed => 405,
            RequestError::Busy => 503,
            RequestError::Vlm(VlmError::Transport { .. }) => 502,
            RequestError::Vlm(VlmError::Timeout(_)) => 504,
            RequestError::Vlm(VlmError::HttpStatus { .. }) => 502,
            RequestError::Vlm(VlmError::Decode(_)) => 502,
            RequestError::Vlm(VlmError::MissingContent) => 502,
        }
    }
}

impl fmt::Display for RequestError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RequestError::Frame(e) => write!(f, "bad request: {e}"),
            RequestError::BodyTooLarge { max } => write!(f, "payload too large (max {max} bytes)"),
            RequestError::NotFound => write!(f, "not found"),
            RequestError::MethodNotAllowed => write!(f, "method not allowed"),
            RequestError::Busy => write!(f, "server busy, try again"),
            RequestError::Vlm(e) => write!(f, "vlm backend error: {e}"),
        }
    }
}

impl From<FrameError> for RequestError {
    fn from(e: FrameError) -> Self {
        RequestError::Frame(e)
    }
}

impl From<VlmError> for RequestError {
    fn from(e: VlmError) -> Self {
        RequestError::Vlm(e)
    }
}
