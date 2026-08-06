//! Shared output queue: log lines and structured events that the Kotlin side
//! drains periodically via `nativePoll`.

use std::collections::VecDeque;
use std::sync::Mutex;

use log::{Level, LevelFilter, Log, Metadata, Record};

const MAX_QUEUE: usize = 8192;

/// Record separator used when joining lines for the JNI poll call.
pub const RECORD_SEP: char = '\u{1E}';

static QUEUE: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

pub fn push(line: String) {
    let mut q = QUEUE.lock().unwrap();
    if q.len() >= MAX_QUEUE {
        q.pop_front();
    }
    q.push_back(line);
}

/// Push a structured event (JSON payload, prefixed with "E|").
pub fn push_event(json: String) {
    push(format!("E|{json}"));
}

/// Drain all pending lines joined by RECORD_SEP.
pub fn drain() -> String {
    let mut q = QUEUE.lock().unwrap();
    let mut out = String::new();
    let mut first = true;
    for line in q.drain(..) {
        if !first {
            out.push(RECORD_SEP);
        }
        out.push_str(&line);
        first = false;
    }
    out
}

/// A logger that captures log lines into the shared queue.
struct QueueLogger;

impl Log for QueueLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= log::max_level()
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let lvl = match record.level() {
            Level::Error => "E",
            Level::Warn => "W",
            Level::Info => "I",
            Level::Debug => "D",
            Level::Trace => "T",
        };
        push(format!("L|{lvl}|{}", record.args()));
    }

    fn flush(&self) {}
}

static LOGGER: QueueLogger = QueueLogger;

pub fn init_logger(level: &str) {
    let filter = match level.to_lowercase().as_str() {
        "off" => LevelFilter::Off,
        "error" => LevelFilter::Error,
        "warn" => LevelFilter::Warn,
        "info" => LevelFilter::Info,
        "debug" => LevelFilter::Debug,
        "trace" => LevelFilter::Trace,
        _ => LevelFilter::Info,
    };
    // set_logger fails if already set (engine restart); that's fine.
    let _ = log::set_logger(&LOGGER);
    log::set_max_level(filter);
}
