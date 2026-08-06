//! Shared runner for the Linux MPQUIC client/server CLIs.
//!
//! This is a thin console frontend over the same engine the Android apps
//! use (`mpquic_jni::{config, engine, output}`): it builds the identical
//! JSON config, spawns the engine thread, and turns the engine's queued
//! log lines / JSON events into terminal output.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::time::Duration;

use mio::{Poll, Waker};
use mpquic_jni::config::BridgeConfig;
use mpquic_jni::engine::{self, Cmd};
use mpquic_jni::output;

const RECORD_SEP: char = '\u{1E}';

#[derive(Debug, Clone)]
pub struct Options {
    pub role: &'static str,
    pub listen: String,
    pub connect: String,
    pub locals: Vec<String>,
    pub multipath: bool,
    pub scheduler: String,
    pub congestion: String,
    pub log_level: String,
    pub cert: String,
    pub key: String,
    pub echo: bool,
    /// Client: send this many MB of test payload once connected.
    pub send_mb: Option<u64>,
    /// Client: send this text once connected.
    pub message: Option<String>,
    /// Client: exit after the send_complete summary.
    pub oneshot: bool,
    /// Client: run a local HTTP/3 listener on this port whose requests are
    /// tunneled over the MPQUIC connection (same as the app's HTTP/3 RX).
    pub h3_port: Option<u16>,
    /// Print the per-second stats events too.
    pub stats: bool,
}

impl Options {
    pub fn defaults(role: &'static str) -> Self {
        Options {
            role,
            listen: "0.0.0.0:4433".into(),
            connect: String::new(),
            locals: Vec::new(),
            multipath: true,
            scheduler: "minrtt".into(),
            congestion: "bbr".into(),
            log_level: "info".into(),
            cert: "server.crt".into(),
            key: "server.key".into(),
            echo: true,
            send_mb: None,
            message: None,
            oneshot: false,
            h3_port: None,
            stats: false,
        }
    }
}

/// Parse `--flag value` / boolean flags; returns None (after printing usage)
/// on error or `--help`.
pub fn parse(role: &'static str, args: &[String], usage: &str) -> Option<Options> {
    let mut o = Options::defaults(role);
    let mut i = 0;
    fn value<'a>(args: &'a [String], i: &mut usize, flag: &str) -> Option<&'a str> {
        *i += 1;
        match args.get(*i) {
            Some(v) => Some(v.as_str()),
            None => {
                eprintln!("missing value for {flag}");
                None
            }
        }
    }
    while i < args.len() {
        let a = args[i].as_str();
        match a {
            "--listen" => o.listen = value(args, &mut i, a)?.to_string(),
            "--connect" => o.connect = value(args, &mut i, a)?.to_string(),
            "--local" => {
                o.locals = value(args, &mut i, a)?
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect()
            }
            "--scheduler" => o.scheduler = value(args, &mut i, a)?.to_string(),
            "--cc" => o.congestion = value(args, &mut i, a)?.to_string(),
            "--log-level" => o.log_level = value(args, &mut i, a)?.to_string(),
            "--cert" => o.cert = value(args, &mut i, a)?.to_string(),
            "--key" => o.key = value(args, &mut i, a)?.to_string(),
            "--send-mb" => {
                o.send_mb = Some(value(args, &mut i, a)?.parse().ok().or_else(|| {
                    eprintln!("--send-mb needs a number");
                    None
                })?)
            }
            "--message" => o.message = Some(value(args, &mut i, a)?.to_string()),
            "--h3-port" => {
                o.h3_port = Some(value(args, &mut i, a)?.parse().ok().or_else(|| {
                    eprintln!("--h3-port needs a port number");
                    None
                })?)
            }
            "--no-multipath" => o.multipath = false,
            "--no-echo" => o.echo = false,
            "--oneshot" => o.oneshot = true,
            "--stats" => o.stats = true,
            "--help" | "-h" => {
                println!("{usage}");
                return None;
            }
            other => {
                eprintln!("unknown flag {other}\n{usage}");
                return None;
            }
        }
        i += 1;
    }
    if role == "client" && o.connect.is_empty() {
        eprintln!("--connect <ip:port> is required\n{usage}");
        return None;
    }
    Some(o)
}

fn build_config(o: &Options) -> Result<BridgeConfig, String> {
    let mut cfg = serde_json::json!({
        "role": o.role,
        "enable_multipath": o.multipath,
        "multipath_algorithm": o.scheduler,
        "congestion_control": o.congestion,
        "log_level": o.log_level,
    });
    if o.role == "server" {
        cfg["listen"] = o.listen.clone().into();
        cfg["cert_file"] = o.cert.clone().into();
        cfg["key_file"] = o.key.clone().into();
        cfg["echo"] = o.echo.into();
    } else {
        cfg["connect_to"] = o.connect.clone().into();
        cfg["local_addresses"] = o.locals.clone().into();
    }
    serde_json::from_value(cfg).map_err(|e| format!("bad config: {e}"))
}

fn test_payload(mb: u64) -> Vec<u8> {
    let unit: Vec<u8> = (0..1024 * 1024).map(|i| (i % 251) as u8).collect();
    let mut out = Vec::with_capacity((mb as usize) * unit.len());
    for _ in 0..mb {
        out.extend_from_slice(&unit);
    }
    out
}

/// Run the engine until Ctrl+C (or `--oneshot` completion). Returns the
/// process exit code.
pub fn run(o: Options) -> i32 {
    let cfg = match build_config(&o) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };
    output::init_logger(&o.log_level);

    let poll = match Poll::new() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("mio poll: {e}");
            return 2;
        }
    };
    let waker = match Waker::new(poll.registry(), engine::waker_token()) {
        Ok(w) => Arc::new(w),
        Err(e) => {
            eprintln!("mio waker: {e}");
            return 2;
        }
    };
    let (cmd_tx, cmd_rx) = mpsc::channel::<Cmd>();
    let running = Arc::new(AtomicBool::new(true));
    let engine_running = running.clone();
    let join = std::thread::Builder::new()
        .name("mpquic-engine".into())
        .spawn(move || engine::run(cfg, poll, cmd_rx, engine_running))
        .expect("spawn engine thread");

    {
        let running = running.clone();
        let waker = waker.clone();
        let _ = ctrlc::set_handler(move || {
            eprintln!("\nstopping...");
            running.store(false, Ordering::Relaxed);
            let _ = waker.wake();
        });
    }

    let mut sent = false;
    let mut exit_code = 0;
    'outer: loop {
        std::thread::sleep(Duration::from_millis(200));
        let drained = output::drain();
        for rec in drained.split(RECORD_SEP) {
            if rec.is_empty() {
                continue;
            }
            if let Some(rest) = rec.strip_prefix("L|") {
                let mut it = rest.splitn(2, '|');
                let lvl = it.next().unwrap_or("?");
                let msg = it.next().unwrap_or("");
                eprintln!("[{lvl}] {msg}");
                continue;
            }
            let Some(json) = rec.strip_prefix("E|") else {
                println!("{rec}");
                continue;
            };
            let ev: serde_json::Value = match serde_json::from_str(json) {
                Ok(v) => v,
                Err(_) => {
                    println!("{json}");
                    continue;
                }
            };
            match ev["type"].as_str().unwrap_or("") {
                "listening" => println!("== listening on {} ==", ev["addr"].as_str().unwrap_or("?")),
                "h3_listening" => println!("== HTTP/3 listener on port {} ==", ev["port"]),
                "h3_stopped" => println!("== HTTP/3 listener stopped =="),
                "h3_request" => println!(
                    "h3 {} {} ({}) {} B -> tunnel stream {}",
                    ev["method"].as_str().unwrap_or("?"),
                    ev["path"].as_str().unwrap_or("?"),
                    ev["content_type"].as_str().unwrap_or(""),
                    ev["bytes"],
                    ev["tunnel_stream"]
                ),
                "h3_response" => println!(
                    "h3 response {} {} B <- tunnel stream {}",
                    ev["status"], ev["bytes"], ev["tunnel_stream"]
                ),
                "h3_error" => eprintln!("h3 error: {}", ev["message"].as_str().unwrap_or("?")),
                "connected" => {
                    println!("== connected (multipath={}) ==", ev["multipath"]);
                    if let Some(port) = o.h3_port {
                        let _ = cmd_tx.send(Cmd::H3Listen {
                            port,
                            cert: o.cert.clone(),
                            key: o.key.clone(),
                            idle_timeout_ms: mpquic_jni::h3relay::DEFAULT_IDLE_TIMEOUT_MS,
                        });
                        let _ = waker.wake();
                    }
                    if !sent {
                        if let Some(mb) = o.send_mb {
                            println!("sending {mb} MB test payload...");
                            let _ = cmd_tx.send(Cmd::Send(test_payload(mb)));
                            let _ = waker.wake();
                            sent = true;
                        } else if let Some(msg) = &o.message {
                            println!("sending {} B message...", msg.len());
                            let _ = cmd_tx.send(Cmd::Send(msg.clone().into_bytes()));
                            let _ = waker.wake();
                            sent = true;
                        }
                    }
                }
                "path_added" => println!(
                    "== path added {} -> {} ==",
                    ev["local"].as_str().unwrap_or("?"),
                    ev["remote"].as_str().unwrap_or("?")
                ),
                "path_skipped" => println!(
                    "== path skipped {} ({}) ==",
                    ev["local"].as_str().unwrap_or("?"),
                    ev["reason"].as_str().unwrap_or("?")
                ),
                "data" => println!(
                    "recv {} B on stream {} \"{}\"",
                    ev["bytes"],
                    ev["stream"],
                    ev["preview"].as_str().unwrap_or("")
                ),
                "send_complete" => {
                    println!("== send complete: {} B payload ==", ev["bytes_queued"]);
                    for p in ev["paths"].as_array().map(|a| a.as_slice()).unwrap_or(&[]) {
                        println!(
                            "   path {} -> {}: {} B / {} pkts this send (total {} B)",
                            p["local"].as_str().unwrap_or("?"),
                            p["remote"].as_str().unwrap_or("?"),
                            p["bytes_sent"],
                            p["pkts_sent"],
                            p["total_sent_bytes"]
                        );
                    }
                    if o.oneshot {
                        running.store(false, Ordering::Relaxed);
                        let _ = waker.wake();
                    }
                }
                "stats" => {
                    if o.stats {
                        println!("stats: {ev}");
                    }
                }
                "error" => {
                    eprintln!("error: {}", ev["message"].as_str().unwrap_or("?"));
                    exit_code = 1;
                }
                "disconnected" => println!("== disconnected =="),
                "stopped" => {
                    println!("== engine stopped ==");
                    break 'outer;
                }
                _ => println!("event: {ev}"),
            }
        }
        if !running.load(Ordering::Relaxed) && join.is_finished() {
            break;
        }
    }

    running.store(false, Ordering::Relaxed);
    let _ = waker.wake();
    let _ = join.join();
    exit_code
}
