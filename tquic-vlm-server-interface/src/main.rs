use clap::Parser;
use std::sync::atomic::AtomicBool;
use std::sync::{mpsc, Arc};
use tquic_vlm_server_interface::{cli, reactor, server_config};

/// Spawns the MPQUIC tunnel terminus (mpquic-jni, "server" role,
/// answer_mode="forward") on its own thread -- a second, independent
/// listener alongside the plain-H3-JSON one `main()` runs on the main
/// thread. A real MPQUIC client (the Android app or `mpquic-client`)
/// tunnels an HTTP/3 request to `--mpquic-bind`; its body is forwarded
/// verbatim to the same `--vlm-base-url` the JSON listener uses, no
/// packaging/repackaging, and the raw response relayed back. If this fails
/// to bind/start, it logs and returns on its own thread -- the JSON
/// listener on the main thread is unaffected either way.
fn spawn_mpquic_tunnel(validated: &cli::ValidatedArgs) {
    let mpquic_cfg: mpquic_jni::config::BridgeConfig = serde_json::from_value(serde_json::json!({
        "role": "server",
        "listen": validated.args.mpquic_bind.to_string(),
        "cert_file": validated.mpquic_cert_path().to_string_lossy(),
        "key_file": validated.mpquic_key_path().to_string_lossy(),
        "answer_mode": "forward",
        "forward_url": format!(
            "{}/chat/completions",
            validated.args.vlm_base_url.trim_end_matches('/')
        ),
        "forward_timeout_ms": validated.args.vlm_timeout_ms,
        "enable_multipath": true,
        "multipath_algorithm": validated.args.mpquic_scheduler,
        "congestion_control": validated.args.mpquic_congestion_control,
    }))
    .expect("mpquic BridgeConfig always deserializes from this literal");

    // mpquic_jni::engine::run() is built against mio 0.8, a different major
    // version from this crate's own mio 1.x reactor -- see the `mio08`
    // alias in Cargo.toml.
    let mpquic_poll = match mio08::Poll::new() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("tquic-vlm-server-interface: mpquic tunnel: mio::Poll::new failed: {e}");
            return;
        }
    };
    // No commands are ever sent on this channel today -- the sender only
    // needs to stay alive for main()'s lifetime so the engine's
    // cmd_rx.try_recv() sees "empty", not "disconnected" (which would make
    // it exit immediately). Held by the caller, not dropped here.
    let (cmd_tx, cmd_rx) = mpsc::channel();
    let running = Arc::new(AtomicBool::new(true));

    if let Err(e) = std::thread::Builder::new()
        .name("mpquic-tunnel".into())
        .spawn(move || mpquic_jni::engine::run(mpquic_cfg, mpquic_poll, cmd_rx, running))
    {
        eprintln!("tquic-vlm-server-interface: failed to spawn mpquic tunnel thread: {e}");
        return;
    }
    // Leak the sender for the process lifetime -- see the comment above;
    // main() never needs to reach it again.
    std::mem::forget(cmd_tx);
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let args = cli::Args::parse();
    let validated = match args.validate() {
        Ok(v) => v,
        Err(e) => {
            eprintln!("tquic-vlm-server-interface: invalid configuration: {e}");
            std::process::exit(1);
        }
    };

    let config = match server_config::build(&validated) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("tquic-vlm-server-interface: failed to build tquic config: {e}");
            std::process::exit(1);
        }
    };

    spawn_mpquic_tunnel(&validated);

    let bind = validated.args.bind;
    let reactor = match reactor::Reactor::new(&validated, config) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("tquic-vlm-server-interface: failed to bind {bind}: {e}");
            std::process::exit(1);
        }
    };

    reactor.run();
}
