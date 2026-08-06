//! Builds `tquic::Config`/`TlsConfig` for the server from validated CLI
//! args. Adapted from `tquic-jni`'s `ServerOpenParams::build()` (see
//! `native/tquic-jni/src/config.rs`) minus the JNI error type and the
//! (advisory-only) require-client-cert path, which this binary doesn't use
//! -- matching the existing Android demo's `requireClientCert=false`
//! posture.

use crate::cli::ValidatedArgs;
use std::str::FromStr;
use tquic::{CongestionControlAlgorithm, Config, TlsConfig};

fn parse_alpn(alpn: &str) -> Vec<Vec<u8>> {
    alpn.split(',').map(str::trim).filter(|s| !s.is_empty()).map(|s| s.as_bytes().to_vec()).collect()
}

pub fn build(v: &ValidatedArgs) -> Result<Box<Config>, String> {
    let args = &v.args;
    let cert_path = args.cert.to_str().ok_or_else(|| "--cert path is not valid UTF-8".to_string())?;
    let key_path = args.key.to_str().ok_or_else(|| "--key path is not valid UTF-8".to_string())?;
    let protos = parse_alpn(&args.alpn);

    // Already validated against our own SUPPORTED_CONGESTION_CONTROL list in
    // cli.rs; re-parsing here via tquic's own FromStr is the actual source
    // of truth and would only diverge from ours if the two lists drift.
    let cca = CongestionControlAlgorithm::from_str(&args.congestion_control)
        .map_err(|_| format!("congestion-control='{}' rejected by tquic", args.congestion_control))?;

    let tls = TlsConfig::new_server_config(cert_path, key_path, protos, false)
        .map_err(|e| format!("failed to build server TLS config: {e}"))?;

    let mut cfg = Config::new().map_err(|e| format!("tquic::Config::new failed: {e}"))?;
    cfg.set_max_idle_timeout(args.idle_timeout_ms);
    cfg.set_congestion_control_algorithm(cca);
    cfg.enable_pacing(true);
    cfg.enable_dplpmtud(true);
    cfg.set_tls_config(tls);
    Ok(Box::new(cfg))
}
