//! The wire protocol for one inbound request body: two length-prefixed
//! frames back to back, image then text.
//!
//! ```text
//! [1 byte type=0x01][8-byte big-endian length N][N bytes: JPEG image]
//! [1 byte type=0x02][8-byte big-endian length M][M bytes: UTF-8 text prompt]
//! ```
//!
//! This is a fixed two-frame protocol, not an extensible one: trailing
//! bytes after both frames are rejected rather than silently ignored, since
//! that's much more likely to indicate a client-side bug than a forward-
//! compatible extension nobody asked for.

use crate::error::FrameError;

pub const FRAME_TYPE_IMAGE: u8 = 0x01;
pub const FRAME_TYPE_TEXT: u8 = 0x02;

const HEADER_LEN: usize = 9; // 1 type byte + 8-byte big-endian length

struct Cursor<'a> {
    body: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn remaining(&self) -> usize {
        self.body.len() - self.pos
    }

    /// Reads one frame's header + payload, validating `expected` type and
    /// bounds-checking the claimed length against the remaining bytes
    /// *before* slicing -- a truncated or lying length fails fast here
    /// rather than attempting an out-of-bounds slice or an unbounded
    /// allocation.
    fn read_frame(&mut self, expected: u8, max_frame_bytes: usize) -> Result<&'a [u8], FrameError> {
        if self.remaining() < HEADER_LEN {
            return Err(FrameError::Truncated { need: HEADER_LEN - self.remaining(), have: self.remaining() });
        }
        let got = self.body[self.pos];
        if got != expected {
            return Err(FrameError::WrongType { expected, got });
        }
        let len_bytes: [u8; 8] = self.body[self.pos + 1..self.pos + HEADER_LEN].try_into().unwrap();
        let len = u64::from_be_bytes(len_bytes);
        self.pos += HEADER_LEN;

        if len > max_frame_bytes as u64 {
            return Err(FrameError::TooLarge { len, max: max_frame_bytes });
        }
        if len > self.remaining() as u64 {
            return Err(FrameError::LengthOverflow { len, remaining: self.remaining() });
        }
        let len = len as usize;
        let payload = &self.body[self.pos..self.pos + len];
        self.pos += len;
        Ok(payload)
    }
}

/// Parses a full request body into (jpeg bytes, prompt text). Pure and
/// synchronous -- called once per request, on the reactor thread, only
/// after the whole body has been buffered (see `reactor/conn_state.rs`).
pub fn read_frames(body: &[u8], max_frame_bytes: usize) -> Result<(Vec<u8>, String), FrameError> {
    let mut cur = Cursor { body, pos: 0 };
    let image = cur.read_frame(FRAME_TYPE_IMAGE, max_frame_bytes)?.to_vec();
    let text = cur.read_frame(FRAME_TYPE_TEXT, max_frame_bytes)?;
    let prompt = std::str::from_utf8(text)?.to_string();

    if cur.remaining() > 0 {
        return Err(FrameError::TrailingBytes(cur.remaining()));
    }
    Ok((image, prompt))
}

/// Inverse of `read_frames` -- used by the test client and by these unit
/// tests for a round trip. Not used by the server itself.
pub fn write_frames(jpeg: &[u8], prompt: &str) -> Vec<u8> {
    let text = prompt.as_bytes();
    let mut out = Vec::with_capacity(HEADER_LEN * 2 + jpeg.len() + text.len());
    out.push(FRAME_TYPE_IMAGE);
    out.extend_from_slice(&(jpeg.len() as u64).to_be_bytes());
    out.extend_from_slice(jpeg);
    out.push(FRAME_TYPE_TEXT);
    out.extend_from_slice(&(text.len() as u64).to_be_bytes());
    out.extend_from_slice(text);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const MAX: usize = 32 * 1024 * 1024;

    #[test]
    fn round_trip() {
        let jpeg = vec![0xFFu8, 0xD8, 0xFF, 0xD9]; // minimal JPEG SOI/EOI marker bytes
        let prompt = "what is this?";
        let body = write_frames(&jpeg, prompt);
        let (got_jpeg, got_prompt) = read_frames(&body, MAX).unwrap();
        assert_eq!(got_jpeg, jpeg);
        assert_eq!(got_prompt, prompt);
    }

    #[test]
    fn round_trip_empty_frames() {
        // Framing only validates structure, not JPEG well-formedness or
        // prompt non-emptiness -- those are the VLM backend's concern.
        let body = write_frames(&[], "");
        let (jpeg, prompt) = read_frames(&body, MAX).unwrap();
        assert!(jpeg.is_empty());
        assert!(prompt.is_empty());
    }

    #[test]
    fn truncated_header() {
        let body = vec![FRAME_TYPE_IMAGE, 0, 0, 0]; // shorter than a 9-byte header
        match read_frames(&body, MAX) {
            Err(FrameError::Truncated { .. }) => {}
            other => panic!("expected Truncated, got {other:?}"),
        }
    }

    #[test]
    fn truncated_payload() {
        let mut body = vec![FRAME_TYPE_IMAGE];
        body.extend_from_slice(&100u64.to_be_bytes()); // claims 100 bytes
        body.extend_from_slice(&[1, 2, 3]); // only 3 present
        match read_frames(&body, MAX) {
            Err(FrameError::LengthOverflow { len: 100, remaining: 3 }) => {}
            other => panic!("expected LengthOverflow, got {other:?}"),
        }
    }

    #[test]
    fn wrong_type_first_frame() {
        let mut body = vec![FRAME_TYPE_TEXT]; // should be FRAME_TYPE_IMAGE
        body.extend_from_slice(&0u64.to_be_bytes());
        match read_frames(&body, MAX) {
            Err(FrameError::WrongType { expected: FRAME_TYPE_IMAGE, got: FRAME_TYPE_TEXT }) => {}
            other => panic!("expected WrongType, got {other:?}"),
        }
    }

    #[test]
    fn wrong_type_second_frame() {
        let mut body = vec![FRAME_TYPE_IMAGE];
        body.extend_from_slice(&0u64.to_be_bytes());
        body.push(FRAME_TYPE_IMAGE); // should be FRAME_TYPE_TEXT
        body.extend_from_slice(&0u64.to_be_bytes());
        match read_frames(&body, MAX) {
            Err(FrameError::WrongType { expected: FRAME_TYPE_TEXT, got: FRAME_TYPE_IMAGE }) => {}
            other => panic!("expected WrongType, got {other:?}"),
        }
    }

    #[test]
    fn length_exceeds_max_frame_bytes() {
        let mut body = vec![FRAME_TYPE_IMAGE];
        body.extend_from_slice(&1000u64.to_be_bytes());
        body.extend_from_slice(&vec![0u8; 1000]);
        match read_frames(&body, 100) {
            Err(FrameError::TooLarge { len: 1000, max: 100 }) => {}
            other => panic!("expected TooLarge, got {other:?}"),
        }
    }

    #[test]
    fn trailing_bytes_rejected() {
        let mut body = write_frames(b"jpg", "hi");
        body.extend_from_slice(&[0xAA, 0xBB]);
        match read_frames(&body, MAX) {
            Err(FrameError::TrailingBytes(2)) => {}
            other => panic!("expected TrailingBytes(2), got {other:?}"),
        }
    }

    #[test]
    fn invalid_utf8_prompt() {
        let mut body = vec![FRAME_TYPE_IMAGE];
        body.extend_from_slice(&0u64.to_be_bytes());
        body.push(FRAME_TYPE_TEXT);
        body.extend_from_slice(&2u64.to_be_bytes());
        body.extend_from_slice(&[0xFF, 0xFE]); // not valid UTF-8
        match read_frames(&body, MAX) {
            Err(FrameError::InvalidUtf8(_)) => {}
            other => panic!("expected InvalidUtf8, got {other:?}"),
        }
    }
}
