//! Bridge configuration passed from the Kotlin side as JSON.

use serde::Deserialize;
use tquic::{CongestionControlAlgorithm, MultipathAlgorithm};

fn default_alpn() -> Vec<String> {
    vec!["hq-interop".to_string()]
}

fn default_true() -> bool {
    true
}

fn default_idle_timeout() -> u64 {
    // 5 minutes: long enough that a connection survives think-time between
    // manual sends. Keep-alive pings (below) normally prevent it firing at
    // all; this is the backstop for when the peer really is gone.
    300_000
}

fn default_keepalive() -> u64 {
    // PING interval while the connection is otherwise idle. Must be well
    // under the idle timeout on both ends; 0 disables it.
    15_000
}

fn default_forward_timeout() -> u64 {
    // Generous: a real VLM/LLM backend call can be slow. Matches
    // tquic-vlm-server-interface's own --vlm-timeout-ms default.
    120_000
}

/// Resolved answer behavior for a tunneled HTTP/3 request, server role only.
/// See `BridgeConfig::answer_mode`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum AnswerMode {
    /// Echo the request body back verbatim (the crate's original demo behavior).
    #[default]
    Echo,
    /// Reply with a short "received N bytes" acknowledgement, not the payload.
    Ack,
    /// POST the request body verbatim to `forward_url` and relay the
    /// response back verbatim -- no packaging/repackaging of any kind. The
    /// caller is responsible for tunneling a body already shaped exactly as
    /// the backend expects.
    Forward,
}

#[derive(Deserialize, Debug, Clone)]
pub struct BridgeConfig {
    /// "client" or "server"
    pub role: String,

    /// Server address to connect to (client) e.g. "192.168.1.5:4433"
    pub connect_to: Option<String>,

    /// Second remote address, opposite address family from `connect_to`
    /// (client role only). A local path whose only real address is the
    /// other family (e.g. a phone's rmnet interface, often IPv6-only via
    /// carrier 464xlat) is paired with this address instead of
    /// `connect_to` when the two are dialed to the same logical server.
    /// Optional -- omitted, every path uses `connect_to` as before.
    pub connect_to_alt: Option<String>,

    /// Address to listen on (server) e.g. "0.0.0.0:4433"
    pub listen: Option<String>,

    /// Local IP addresses for the client's multipath paths. The first one is
    /// used for the initial path; the rest are added after the handshake.
    #[serde(default)]
    pub local_addresses: Vec<String>,

    #[serde(default)]
    pub enable_multipath: bool,

    /// minrtt | redundant | roundrobin
    #[serde(default)]
    pub multipath_algorithm: String,

    /// cubic | bbr | bbr3 | copa
    #[serde(default)]
    pub congestion_control: String,

    /// off | error | warn | info | debug | trace
    #[serde(default)]
    pub log_level: String,

    /// ALPN protocol names. Arbitrary strings are fine as long as both apps
    /// use the same value; the payload itself is protocol-agnostic bytes.
    #[serde(default = "default_alpn")]
    pub alpn: Vec<String>,

    /// TLS certificate/key file paths (server only).
    pub cert_file: Option<String>,
    pub key_file: Option<String>,

    /// Echo received stream data back to the peer (server only). Also the
    /// legacy fallback for `answer_mode` on a tunneled HTTP/3 request when
    /// `answer_mode` itself is absent -- see `BridgeConfig::answer_mode()`.
    #[serde(default = "default_true")]
    pub echo: bool,

    /// "echo" | "ack" | "forward", server role, tunneled HTTP/3 requests
    /// only (the plain non-H3 stream echo above is unaffected and always
    /// follows `echo`). Absent by default, in which case behavior falls
    /// back to `echo` for full backward compatibility with existing
    /// callers (the Android app, mpquic-cli) that only ever set `echo`.
    pub answer_mode: Option<String>,

    /// Required when `answer_mode == "forward"`: URL to POST a tunneled
    /// request's body to verbatim, e.g.
    /// "http://127.0.0.1:11434/v1/chat/completions".
    pub forward_url: Option<String>,

    /// Per-call timeout for `answer_mode: "forward"`.
    #[serde(default = "default_forward_timeout")]
    pub forward_timeout_ms: u64,

    #[serde(default = "default_idle_timeout")]
    pub idle_timeout_ms: u64,

    /// Send a QUIC PING every this many ms while idle so the connection
    /// stays up between transfers. 0 disables keep-alive.
    #[serde(default = "default_keepalive")]
    pub keepalive_ms: u64,
}

impl BridgeConfig {
    pub fn multipath_algor(&self) -> MultipathAlgorithm {
        match self.multipath_algorithm.to_lowercase().as_str() {
            "redundant" => MultipathAlgorithm::Redundant,
            "roundrobin" | "round_robin" => MultipathAlgorithm::RoundRobin,
            _ => MultipathAlgorithm::MinRtt,
        }
    }

    pub fn congestion_algor(&self) -> CongestionControlAlgorithm {
        match self.congestion_control.to_lowercase().as_str() {
            "cubic" => CongestionControlAlgorithm::Cubic,
            "bbr3" => CongestionControlAlgorithm::Bbr3,
            "copa" => CongestionControlAlgorithm::Copa,
            _ => CongestionControlAlgorithm::Bbr,
        }
    }

    pub fn alpn_bytes(&self) -> Vec<Vec<u8>> {
        self.alpn.iter().map(|s| s.as_bytes().to_vec()).collect()
    }

    pub fn resolved_answer_mode(&self) -> AnswerMode {
        match self.answer_mode.as_deref() {
            Some("forward") => AnswerMode::Forward,
            Some("ack") => AnswerMode::Ack,
            Some("echo") => AnswerMode::Echo,
            _ => {
                if self.echo {
                    AnswerMode::Echo
                } else {
                    AnswerMode::Ack
                }
            }
        }
    }

    pub fn forward_timeout(&self) -> std::time::Duration {
        std::time::Duration::from_millis(self.forward_timeout_ms)
    }
}
