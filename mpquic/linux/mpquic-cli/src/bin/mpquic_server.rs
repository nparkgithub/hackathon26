//! MPQUIC echo server CLI — same engine as the Android server app.

const USAGE: &str = "\
mpquic-server [options]

  --listen <ip:port>    bind address        (default 0.0.0.0:4433)
  --cert FILE           TLS certificate     (default server.crt)
  --key FILE            TLS private key     (default server.key)
  --scheduler S         minrtt | redundant | roundrobin   (default minrtt)
  --cc C                bbr | cubic | bbr3 | copa         (default bbr)
  --log-level L         off|error|warn|info|debug|trace   (default info)
  --no-multipath        disable multipath
  --no-echo             do not echo received payload back
  --stats               print per-second stats events
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(opts) = mpquic_cli::parse("server", &args, USAGE) else {
        std::process::exit(2);
    };
    std::process::exit(mpquic_cli::run(opts));
}
