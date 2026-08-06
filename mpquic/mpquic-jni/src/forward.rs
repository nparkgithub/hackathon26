//! Blocking HTTP forward for `answer_mode: "forward"` -- POSTs a tunneled
//! request's body verbatim to a configured backend URL and returns the raw
//! response, unexamined. No JSON typing, no field extraction, no
//! packaging/repackaging of any kind: the caller (e.g.
//! `tquic-vlm-server-interface`) is responsible for tunneling a body already
//! shaped exactly as the backend expects, and for reading whatever comes
//! back.
//!
//! Runs on its own worker thread per request (see `engine.rs`) -- never on
//! the mio reactor thread, since this blocks for as long as the backend
//! takes to answer.

use std::io::Read;
use std::time::Duration;

pub struct ForwardResponse {
    pub status: u16,
    pub content_type: String,
    pub body: Vec<u8>,
}

/// POSTs `body` to `url` verbatim with the given `content_type`, and returns
/// the backend's raw response (status + content-type + body) unexamined.
/// `Err` covers only transport-level failure (unreachable, timeout) --
/// a non-2xx HTTP status from the backend is still `Ok`, since relaying that
/// status verbatim to the tunnel's caller is itself "no repackaging".
pub fn forward(
    url: &str,
    content_type: &str,
    body: Vec<u8>,
    timeout: Duration,
) -> Result<ForwardResponse, String> {
    let result = ureq::post(url)
        .set("content-type", content_type)
        .timeout(timeout)
        .send_bytes(&body);

    let response = match result {
        Ok(resp) => resp,
        Err(ureq::Error::Status(_, resp)) => resp,
        Err(ureq::Error::Transport(e)) => {
            return Err(format!("could not reach {url}: {e}"));
        }
    };

    let status = response.status();
    let content_type = response.content_type().to_string();
    let mut buf = Vec::new();
    response
        .into_reader()
        .read_to_end(&mut buf)
        .map_err(|e| format!("reading response from {url}: {e}"))?;
    Ok(ForwardResponse { status, content_type, body: buf })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forwards_body_verbatim_and_relays_response_verbatim() {
        let mut server = mockito::Server::new();
        let raw_response = br#"{"id":"chatcmpl-1","choices":[{"message":{"content":"a dog"}}]}"#;
        let _m = server
            .mock("POST", "/chat/completions")
            .match_header("content-type", "application/json")
            .match_body(mockito::Matcher::Exact(
                r#"{"model":"m","messages":[]}"#.to_string(),
            ))
            .with_status(200)
            .with_header("content-type", "application/json")
            .with_body(raw_response)
            .create();

        let url = format!("{}/chat/completions", server.url());
        let body = br#"{"model":"m","messages":[]}"#.to_vec();
        let result = forward(&url, "application/json", body, Duration::from_secs(5)).unwrap();

        assert_eq!(result.status, 200);
        assert_eq!(result.content_type, "application/json");
        // Byte-exact, not just "parses the same" -- the whole point of
        // forward mode is that nothing repackages this in either direction.
        assert_eq!(result.body, raw_response);
    }

    #[test]
    fn non_200_status_is_still_ok_and_relayed() {
        let mut server = mockito::Server::new();
        let _m = server
            .mock("POST", "/chat/completions")
            .with_status(500)
            .with_body("backend exploded")
            .create();

        let url = format!("{}/chat/completions", server.url());
        let result = forward(&url, "application/json", b"{}".to_vec(), Duration::from_secs(5)).unwrap();
        assert_eq!(result.status, 500);
        assert_eq!(result.body, b"backend exploded");
    }

    #[test]
    fn unreachable_backend_is_an_error() {
        let result = forward(
            "http://127.0.0.1:1/chat/completions",
            "application/json",
            b"{}".to_vec(),
            Duration::from_millis(500),
        );
        assert!(result.is_err());
    }
}
