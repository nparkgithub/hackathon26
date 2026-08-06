//! The wire protocol for one inbound request body: a JSON document in one
//! of two shapes, distinguished by which keys are present (content
//! sniffing, not a separate endpoint or a discriminator field):
//!
//! - `{"jpeg": "<base64>", "prompt": "<text>"}` -- the server builds the
//!   OpenAI-shaped chat-completion request itself (`vlm_client::infer`).
//! - `{"model": ..., "messages": [...]}` -- already OpenAI-shaped; forwarded
//!   to the VLM backend verbatim, unexamined further (`vlm_client::infer_raw`).
//!
//! A body matching neither shape is rejected (`FrameError::UnrecognizedShape`)
//! rather than guessed at.

use crate::error::FrameError;
use base64::Engine;

#[derive(Debug)]
pub enum ParsedRequest {
    Simple { jpeg: Vec<u8>, prompt: String },
    OpenAiPassthrough(serde_json::Value),
}

/// Parses a full request body. Pure and synchronous -- called once per
/// request, on the reactor thread, only after the whole body has been
/// buffered (see `reactor/conn_state.rs`).
///
/// Deliberately not a `#[serde(untagged)]` enum: that would make any valid
/// JSON object silently match a catch-all `Value` variant, losing the
/// explicit "neither shape recognized -> 400" rejection below.
pub fn read_request(body: &[u8]) -> Result<ParsedRequest, FrameError> {
    let value: serde_json::Value = serde_json::from_slice(body)?;
    let obj = value.as_object().ok_or(FrameError::UnrecognizedShape)?;

    if obj.contains_key("jpeg") && obj.contains_key("prompt") {
        let jpeg_b64 = obj["jpeg"].as_str().ok_or(FrameError::UnrecognizedShape)?;
        let jpeg = base64::engine::general_purpose::STANDARD.decode(jpeg_b64)?;
        let prompt = obj["prompt"].as_str().ok_or(FrameError::UnrecognizedShape)?.to_string();
        return Ok(ParsedRequest::Simple { jpeg, prompt });
    }
    if obj.contains_key("model") && obj.contains_key("messages") {
        return Ok(ParsedRequest::OpenAiPassthrough(value));
    }
    Err(FrameError::UnrecognizedShape)
}

/// Inverse of the `Simple` half of `read_request` -- used by the test
/// client and by these unit tests for a round trip. Not used by the
/// server itself.
pub fn write_request_simple(jpeg: &[u8], prompt: &str) -> Vec<u8> {
    let b64 = base64::engine::general_purpose::STANDARD.encode(jpeg);
    serde_json::to_vec(&serde_json::json!({ "jpeg": b64, "prompt": prompt }))
        .expect("simple request always serializes")
}

/// Inverse of the `OpenAiPassthrough` half of `read_request` -- used by the
/// test client and by these unit tests for a round trip. Not used by the
/// server itself.
pub fn write_request_raw(value: &serde_json::Value) -> Vec<u8> {
    serde_json::to_vec(value).expect("a serde_json::Value always serializes")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_simple() {
        let jpeg = vec![0xFFu8, 0xD8, 0xFF, 0xD9]; // minimal JPEG SOI/EOI marker bytes
        let prompt = "what is this?";
        let body = write_request_simple(&jpeg, prompt);
        match read_request(&body).unwrap() {
            ParsedRequest::Simple { jpeg: got_jpeg, prompt: got_prompt } => {
                assert_eq!(got_jpeg, jpeg);
                assert_eq!(got_prompt, prompt);
            }
            ParsedRequest::OpenAiPassthrough(_) => panic!("expected Simple"),
        }
    }

    #[test]
    fn round_trip_simple_empty_fields() {
        // Parsing only validates structure, not JPEG well-formedness or
        // prompt non-emptiness -- those are the VLM backend's concern.
        let body = write_request_simple(&[], "");
        match read_request(&body).unwrap() {
            ParsedRequest::Simple { jpeg, prompt } => {
                assert!(jpeg.is_empty());
                assert!(prompt.is_empty());
            }
            ParsedRequest::OpenAiPassthrough(_) => panic!("expected Simple"),
        }
    }

    #[test]
    fn round_trip_openai_passthrough() {
        let value = serde_json::json!({
            "model": "qwen3-vl:8b",
            "messages": [{"role": "user", "content": [{"type": "text", "text": "hi"}]}],
        });
        let body = write_request_raw(&value);
        match read_request(&body).unwrap() {
            ParsedRequest::OpenAiPassthrough(got) => assert_eq!(got, value),
            ParsedRequest::Simple { .. } => panic!("expected OpenAiPassthrough"),
        }
    }

    #[test]
    fn simple_shape_checked_before_passthrough() {
        // A body with all four keys present matches Simple, since that
        // check runs first -- documents the precedence, not just asserts it.
        let body = br#"{"jpeg":"aGk=","prompt":"hi","model":"m","messages":[]}"#;
        match read_request(body).unwrap() {
            ParsedRequest::Simple { .. } => {}
            ParsedRequest::OpenAiPassthrough(_) => panic!("expected Simple to take precedence"),
        }
    }

    #[test]
    fn malformed_json() {
        match read_request(b"not json") {
            Err(FrameError::InvalidJson(_)) => {}
            other => panic!("expected InvalidJson, got {other:?}"),
        }
    }

    #[test]
    fn invalid_base64_jpeg() {
        let body = br#"{"jpeg":"not valid base64!!","prompt":"hi"}"#;
        match read_request(body) {
            Err(FrameError::InvalidBase64(_)) => {}
            other => panic!("expected InvalidBase64, got {other:?}"),
        }
    }

    #[test]
    fn unrecognized_shape() {
        let body = br#"{"foo":"bar"}"#;
        match read_request(body) {
            Err(FrameError::UnrecognizedShape) => {}
            other => panic!("expected UnrecognizedShape, got {other:?}"),
        }
    }

    #[test]
    fn non_object_json_is_unrecognized_shape() {
        match read_request(b"[1,2,3]") {
            Err(FrameError::UnrecognizedShape) => {}
            other => panic!("expected UnrecognizedShape, got {other:?}"),
        }
    }

    #[test]
    fn non_string_prompt_is_unrecognized_shape() {
        let body = br#"{"jpeg":"aGk=","prompt":42}"#;
        match read_request(body) {
            Err(FrameError::UnrecognizedShape) => {}
            other => panic!("expected UnrecognizedShape, got {other:?}"),
        }
    }
}
