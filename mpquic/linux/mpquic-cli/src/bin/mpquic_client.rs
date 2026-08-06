//! MPQUIC client CLI — same engine as the Android client app.

const USAGE: &str = "\
mpquic-client --connect <ip:port> [options]

  --connect <ip:port>   server address (required; [v6]:port for IPv6)
  --local a,b,c         local IPs, one QUIC path per IP (first = initial)
  --scheduler S         minrtt | redundant | roundrobin   (default minrtt)
  --cc C                bbr | cubic | bbr3 | copa         (default bbr)
  --log-level L         off|error|warn|info|debug|trace   (default info)
  --no-multipath        disable multipath
  --send-mb N           send N MB of test payload once connected
  --message TEXT        send TEXT once connected
  --h3-port N           run a local HTTP/3 listener on port N; requests it
                        receives (e.g. large JPEG POSTs) are tunneled over
                        MPQUIC and the peer's response is returned
  --cert/--key FILE     TLS pair for that HTTP/3 listener
  --oneshot             exit after the send-complete summary
  --stats               print per-second stats events
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(opts) = mpquic_cli::parse("client", &args, USAGE) else {
        std::process::exit(2);
    };
    std::process::exit(mpquic_cli::run(opts));
}
