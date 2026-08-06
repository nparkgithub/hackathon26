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

#[derive(Deserialize, Debug, Clone)]
pub struct BridgeConfig {
    /// "client" or "server"
    pub role: String,

    /// Server address to connect to (client) e.g. "192.168.1.5:4433"
    pub connect_to: Option<String>,

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

    /// Echo received stream data back to the peer (server only).
    #[serde(default = "default_true")]
    pub echo: bool,

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
}
