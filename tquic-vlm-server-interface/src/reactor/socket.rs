//! A single mio UDP socket, doubling as the tquic `Endpoint`'s
//! `PacketSendHandler`. This server binds exactly one address, so (unlike
//! `tquic-jni`'s `SocketSet`, built to support N client paths for
//! multipath) there's no by-address indirection needed -- every outbound
//! packet just goes out this one socket. `dst` on each inbound packet is
//! this socket's own local address, standing in for `IP_PKTINFO` (mirrors
//! `tquic-jni/src/runtime/socketset.rs`'s `recv_from`).

use mio::net::UdpSocket as MioUdpSocket;
use std::net::{SocketAddr, UdpSocket as StdUdpSocket};
use tquic::{PacketInfo, PacketSendHandler, Result as TquicResult};

pub struct ServerSocket {
    sock: MioUdpSocket,
    local_addr: SocketAddr,
}

impl ServerSocket {
    pub fn bind(registry: &mio::Registry, token: mio::Token, addr: SocketAddr) -> std::io::Result<Self> {
        let std_sock = StdUdpSocket::bind(addr)?;
        std_sock.set_nonblocking(true)?;
        let local_addr = std_sock.local_addr()?;
        let mut sock = MioUdpSocket::from_std(std_sock);
        registry.register(&mut sock, token, mio::Interest::READABLE)?;
        Ok(ServerSocket { sock, local_addr })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub fn recv_from(&self, buf: &mut [u8]) -> std::io::Result<(usize, SocketAddr, SocketAddr)> {
        let (n, src) = self.sock.recv_from(buf)?;
        Ok((n, src, self.local_addr))
    }
}

impl PacketSendHandler for ServerSocket {
    fn on_packets_send(&self, pkts: &[(Vec<u8>, PacketInfo)]) -> TquicResult<usize> {
        let mut sent = 0usize;
        for (buf, info) in pkts {
            match self.sock.send_to(buf, info.dst) {
                Ok(_) => sent += 1,
                // WouldBlock: stop here, tquic retries the remainder on a
                // later on_packets_send() call -- the documented partial-
                // send contract (return count sent so far, not an error).
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => {
                    log::warn!("tquic-vlm-server-interface: send_to({}) failed: {e}", info.dst);
                    break;
                }
            }
        }
        Ok(sent)
    }
}
