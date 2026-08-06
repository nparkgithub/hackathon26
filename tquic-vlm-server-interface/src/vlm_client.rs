//! The hop from this server to the VLM/LLM backend: a plain, blocking HTTP
//! POST of a standard OpenAI-compatible vision chat-completion request.
//!
//! This function has no knowledge of QUIC/H3 at all -- it *is* the
//! "swappable, non-Rust-specific interface" the backend needs: any language
//! can serve a plain `/v1/chat/completions` endpoint, so the VLM app is
//! never required to speak Rust or link against this crate.

use crate::error::VlmError;
use base64::Engine;
use serde::Deserialize;
use serde_json::json;
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct VlmConfig {
    /// No trailing slash, e.g. "http://127.0.0.1:8080/v1".
    pub base_url: String,
    pub model: String,
    pub timeout: Duration,
}

#[derive(Deserialize)]
struct ChatCompletionResponse {
    #[serde(default)]
    choices: Vec<Choice>,
}

#[derive(Deserialize)]
struct Choice {
    message: Message,
}

#[derive(Deserialize)]
struct Message {
    content: Option<String>,
}

/// Shared HTTP call: POSTs `body` to `{cfg.base_url}/chat/completions` and
/// maps ureq's status/transport error split onto `VlmError`. Used by both
/// `infer` (server-constructed request) and `infer_raw` (client-supplied,
/// forwarded verbatim) -- everything past "get a response back" differs
/// between the two (parsed-and-extracted vs. relayed-as-is), but the call
/// itself and its error handling don't.
fn post_chat_completions(cfg: &VlmConfig, body: serde_json::Value) -> Result<ureq::Response, VlmError> {
    let url = format!("{}/chat/completions", cfg.base_url.trim_end_matches('/'));
    let result = ureq::post(&url).timeout(cfg.timeout).send_json(body);

    match result {
        Ok(resp) => Ok(resp),
        Err(ureq::Error::Status(status, resp)) => {
            let body_text = resp.into_string().unwrap_or_default();
            Err(VlmError::HttpStatus { status, body: body_text })
        }
        Err(e @ ureq::Error::Transport(_)) => {
            let msg = e.to_string();
            if msg.to_lowercase().contains("timed out") || msg.to_lowercase().contains("timeout") {
                Err(VlmError::Timeout(cfg.timeout))
            } else {
                Err(VlmError::Transport { url, source: Box::new(e) })
            }
        }
    }
}

/// Calls `{cfg.base_url}/chat/completions` with the image + prompt, returns
/// the model's answer text. Runs synchronously -- callers dispatch this
/// onto its own worker thread (see `reactor/vlm_bridge.rs`) so a slow VLM
/// response never blocks the QUIC reactor.
pub fn infer(cfg: &VlmConfig, jpeg: &[u8], prompt: &str) -> Result<String, VlmError> {
    let b64 = base64::engine::general_purpose::STANDARD.encode(jpeg);
    let data_url = format!("data:image/jpeg;base64,{b64}");
    let body = json!({
        "model": cfg.model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": data_url}},
            ],
        }],
    });

    let response = post_chat_completions(cfg, body)?;
    let text = response.into_string().map_err(|_| VlmError::MissingContent)?;
    let parsed: ChatCompletionResponse = serde_json::from_str(&text)?;
    parsed
        .choices
        .into_iter()
        .next()
        .and_then(|c| c.message.content)
        .filter(|s| !s.is_empty())
        .ok_or(VlmError::MissingContent)
}

/// Forwards `body` (already OpenAI-shaped -- built by the *client*, not
/// this server) to `{cfg.base_url}/chat/completions` verbatim: no field
/// rewriting, not even `model`. Returns the VLM backend's raw response body
/// text, unparsed, for verbatim relay back to the phone (it already
/// understands the OpenAI response shape, since it built the request).
pub fn infer_raw(cfg: &VlmConfig, body: serde_json::Value) -> Result<String, VlmError> {
    let response = post_chat_completions(cfg, body)?;
    response.into_string().map_err(|_| VlmError::MissingContent)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(base_url: String) -> VlmConfig {
        VlmConfig { base_url, model: "test-model".to_string(), timeout: Duration::from_secs(5) }
    }

    #[test]
    fn success() {
        let mut server = mockito::Server::new();
        let _m = server
            .mock("POST", "/chat/completions")
            .with_status(200)
            .with_header("content-type", "application/json")
            .with_body(r#"{"choices":[{"message":{"content":"a cat"}}]}"#)
            .create();

        let result = infer(&cfg(server.url()), b"\xff\xd8\xff\xd9", "what is this?").unwrap();
        assert_eq!(result, "a cat");
    }

    #[test]
    fn non_200_status() {
        let mut server = mockito::Server::new();
        let _m = server.mock("POST", "/chat/completions").with_status(500).with_body("boom").create();

        match infer(&cfg(server.url()), b"", "hi") {
            Err(VlmError::HttpStatus { status: 500, body }) => assert_eq!(body, "boom"),
            other => panic!("expected HttpStatus(500), got {other:?}"),
        }
    }

    #[test]
    fn malformed_json() {
        let mut server = mockito::Server::new();
        let _m = server.mock("POST", "/chat/completions").with_status(200).with_body("not json").create();

        match infer(&cfg(server.url()), b"", "hi") {
            Err(VlmError::Decode(_)) => {}
            other => panic!("expected Decode, got {other:?}"),
        }
    }

    #[test]
    fn missing_content() {
        let mut server = mockito::Server::new();
        let _m = server
            .mock("POST", "/chat/completions")
            .with_status(200)
            .with_body(r#"{"choices":[{"message":{"content":null}}]}"#)
            .create();

        match infer(&cfg(server.url()), b"", "hi") {
            Err(VlmError::MissingContent) => {}
            other => panic!("expected MissingContent, got {other:?}"),
        }
    }

    #[test]
    fn empty_choices() {
        let mut server = mockito::Server::new();
        let _m = server.mock("POST", "/chat/completions").with_status(200).with_body(r#"{"choices":[]}"#).create();

        match infer(&cfg(server.url()), b"", "hi") {
            Err(VlmError::MissingContent) => {}
            other => panic!("expected MissingContent, got {other:?}"),
        }
    }

    #[test]
    fn unreachable_backend_is_an_error() {
        // Port 1 is a privileged/typically-closed port -- connecting should
        // fail fast rather than hang, without depending on exact ureq error
        // classification (Transport vs Timeout both count as "failed").
        let bad_cfg = VlmConfig {
            base_url: "http://127.0.0.1:1".to_string(),
            model: "test-model".to_string(),
            timeout: Duration::from_millis(500),
        };
        assert!(infer(&bad_cfg, b"", "hi").is_err());
    }

    #[test]
    fn infer_raw_relays_response_verbatim() {
        let mut server = mockito::Server::new();
        let raw_response = r#"{"id":"chatcmpl-1","choices":[{"message":{"content":"a dog"}}],"usage":{"total_tokens":5}}"#;
        let _m = server
            .mock("POST", "/chat/completions")
            .with_status(200)
            .with_header("content-type", "application/json")
            .with_body(raw_response)
            .create();

        let client_body = json!({"model": "whatever-the-client-said", "messages": []});
        let result = infer_raw(&cfg(server.url()), client_body).unwrap();
        // Not just "doesn't error" -- the whole point of passthrough is byte
        // fidelity, unlike infer()'s extract-just-the-content behavior.
        assert_eq!(result, raw_response);
    }

    #[test]
    fn infer_raw_forwards_client_body_unmodified() {
        let mut server = mockito::Server::new();
        let _m = server
            .mock("POST", "/chat/completions")
            .match_body(mockito::Matcher::Json(json!({"model": "client-chosen-model", "messages": []})))
            .with_status(200)
            .with_body(r#"{"choices":[]}"#)
            .create();

        // cfg's own model is "test-model" -- if infer_raw rewrote it, this
        // mock's exact-body match would fail and mockito would 501.
        let client_body = json!({"model": "client-chosen-model", "messages": []});
        infer_raw(&cfg(server.url()), client_body).unwrap();
    }

    #[test]
    fn infer_raw_non_200_status() {
        let mut server = mockito::Server::new();
        let _m = server.mock("POST", "/chat/completions").with_status(500).with_body("boom").create();

        match infer_raw(&cfg(server.url()), json!({"model": "m", "messages": []})) {
            Err(VlmError::HttpStatus { status: 500, body }) => assert_eq!(body, "boom"),
            other => panic!("expected HttpStatus(500), got {other:?}"),
        }
    }

    #[test]
    fn infer_raw_unreachable_backend_is_an_error() {
        let bad_cfg = VlmConfig {
            base_url: "http://127.0.0.1:1".to_string(),
            model: "test-model".to_string(),
            timeout: Duration::from_millis(500),
        };
        assert!(infer_raw(&bad_cfg, json!({"model": "m", "messages": []})).is_err());
    }
}
