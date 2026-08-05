use clap::Parser;
use tquic_vlm_server_interface::{cli, reactor, server_config};

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
