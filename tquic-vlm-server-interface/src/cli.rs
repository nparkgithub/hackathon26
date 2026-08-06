//! CLI configuration surface. Nothing about the VLM backend is hardcoded --
//! no service is running yet, so `--vlm-base-url`/`--vlm-model` must be
//! supplied or left at their explicitly-placeholder defaults.

use clap::Parser;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

/// tquic's actual supported set (tquic 1.6.0's `CongestionControlAlgorithm::from_str`).
/// Duplicated from `tquic-jni/src/config.rs`'s `SUPPORTED_CONGESTION_CONTROL` rather
/// than shared, since these are two independently-shipped binaries -- update both if
/// tquic's supported set changes.
pub const SUPPORTED_CONGESTION_CONTROL: &[&str] = &["cubic", "bbr", "bbr3", "copa", "dummy"];

#[derive(Parser, Debug)]
#[command(name = "tquic-vlm-server-interface", about = "QUIC/H3 server bridging phone requests to a local OpenAI-compatible VLM")]
pub struct Args {
    /// UDP address to bind the QUIC listener on.
    #[arg(long, default_value = "0.0.0.0:19500")]
    pub bind: SocketAddr,

    /// PEM certificate path (EC P-256 recommended; BoringSSL may reject PKCS#1 keys).
    #[arg(long, default_value = "certs/server.crt")]
    pub cert: PathBuf,

    /// PEM private key path (PKCS#8).
    #[arg(long, default_value = "certs/server.key")]
    pub key: PathBuf,

    /// Comma-separated ALPN protocol list.
    #[arg(long, default_value = "h3")]
    pub alpn: String,

    /// One of: cubic, bbr, bbr3, copa, dummy.
    #[arg(long, default_value = "bbr")]
    pub congestion_control: String,

    /// QUIC idle timeout, milliseconds.
    #[arg(long, default_value_t = 30_000)]
    pub idle_timeout_ms: u64,

    /// H3 path this server accepts inference requests on.
    #[arg(long, default_value = "/v1/infer")]
    pub infer_path: String,

    /// Base URL of the local OpenAI-compatible VLM/LLM service (no trailing
    /// slash, no "/chat/completions" suffix). Placeholder default -- nothing
    /// listens here until a real backend is configured.
    #[arg(long, default_value = "http://127.0.0.1:8080/v1")]
    pub vlm_base_url: String,

    /// Model name passed in the "model" field of the chat-completion request.
    /// Placeholder default -- most local OpenAI-compatible servers accept
    /// any value here, but check yours.
    #[arg(long, default_value = "local-vlm")]
    pub vlm_model: String,

    /// How long to wait for the VLM backend before giving up (real VLM
    /// inference can be slow -- default is generous).
    #[arg(long, default_value_t = 120_000)]
    pub vlm_timeout_ms: u64,

    /// Max concurrent in-flight VLM calls (worker threads). Beyond this,
    /// new requests get an immediate 503 rather than an unbounded thread pool.
    #[arg(long, default_value_t = 8)]
    pub max_inflight_vlm: usize,

    /// Max bytes buffered for a single request body before it's rejected (413).
    /// Sole size guard now that the body is one JSON document (base64 image
    /// + prompt, or a pre-formatted OpenAI request) rather than TLV frames
    /// with their own per-field cap.
    #[arg(long, default_value_t = 32 * 1024 * 1024)]
    pub max_body_bytes: usize,

    /// UDP address for the MPQUIC tunnel terminus (mpquic-jni, "server"
    /// role, answer_mode="forward") -- a second, independent listener
    /// alongside `--bind`'s plain-H3-JSON one. A real MPQUIC client (the
    /// Android app or `mpquic-client`) tunnels an HTTP/3 request to this
    /// address; its body is forwarded verbatim to `--vlm-base-url`, no
    /// packaging/repackaging, and the raw response relayed back.
    #[arg(long, default_value = "0.0.0.0:4433")]
    pub mpquic_bind: SocketAddr,

    /// PEM cert for the MPQUIC tunnel terminus. Defaults to the same file
    /// as `--cert` -- verify_peer=false on every known client of this
    /// tunnel makes the cert's actual content non-blocking either way.
    #[arg(long)]
    pub mpquic_cert: Option<PathBuf>,

    /// PEM key for the MPQUIC tunnel terminus. Defaults to `--key`.
    #[arg(long)]
    pub mpquic_key: Option<PathBuf>,

    /// minrtt | redundant | roundrobin -- mpquic's own flag vocabulary,
    /// kept distinct from `--congestion-control` since this is a genuinely
    /// different tunnel/connection from the plain-H3-JSON listener.
    #[arg(long, default_value = "minrtt")]
    pub mpquic_scheduler: String,

    /// cubic | bbr | bbr3 | copa, for the MPQUIC tunnel specifically.
    #[arg(long, default_value = "bbr")]
    pub mpquic_congestion_control: String,
}

pub struct ValidatedArgs {
    pub args: Args,
}

impl Args {
    /// Fails fast on anything `server_config::build` or `vlm_client` would
    /// otherwise only discover deep inside a request -- mirrors
    /// `tquic-jni/src/config.rs`'s `require_path_exists` rationale: a
    /// pre-check turns a typo'd path into an immediate, specific error
    /// instead of an opaque handshake failure three calls later.
    pub fn validate(self) -> Result<ValidatedArgs, String> {
        if !self.cert.exists() {
            return Err(format!("--cert '{}' does not exist", self.cert.display()));
        }
        if !self.key.exists() {
            return Err(format!("--key '{}' does not exist", self.key.display()));
        }
        if self.alpn.split(',').map(str::trim).filter(|s| !s.is_empty()).count() == 0 {
            return Err("--alpn must not be empty (e.g. \"h3\")".to_string());
        }
        if !SUPPORTED_CONGESTION_CONTROL.contains(&self.congestion_control.as_str()) {
            return Err(format!(
                "--congestion-control='{}' is not supported by tquic 1.6.0. Supported: {}",
                self.congestion_control,
                SUPPORTED_CONGESTION_CONTROL.join(", "),
            ));
        }
        if !self.infer_path.starts_with('/') {
            return Err(format!("--infer-path='{}' must start with '/'", self.infer_path));
        }
        if let Some(cert) = &self.mpquic_cert {
            if !cert.exists() {
                return Err(format!("--mpquic-cert '{}' does not exist", cert.display()));
            }
        }
        if let Some(key) = &self.mpquic_key {
            if !key.exists() {
                return Err(format!("--mpquic-key '{}' does not exist", key.display()));
            }
        }
        Ok(ValidatedArgs { args: self })
    }
}

impl ValidatedArgs {
    pub fn vlm_timeout(&self) -> Duration {
        Duration::from_millis(self.args.vlm_timeout_ms)
    }

    /// Resolves to `--mpquic-cert`, falling back to `--cert`.
    pub fn mpquic_cert_path(&self) -> &std::path::Path {
        self.args.mpquic_cert.as_deref().unwrap_or(&self.args.cert)
    }

    /// Resolves to `--mpquic-key`, falling back to `--key`.
    pub fn mpquic_key_path(&self) -> &std::path::Path {
        self.args.mpquic_key.as_deref().unwrap_or(&self.args.key)
    }
}
