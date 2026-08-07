//! UDP socket group for a QUIC endpoint, adapted from tquic tools' QuicSocket.
//! Holds one socket per configured local address so that multipath QUIC can
//! send/receive on several 4-tuples.

use std::collections::HashMap;
use std::io::ErrorKind;
use std::net::SocketAddr;

use log::debug;
use mio::net::UdpSocket;
use mio::{Interest, Registry, Token};
use slab::Slab;
use tquic::{PacketInfo, PacketSendHandler};

pub struct QuicSocket {
    socks: Slab<UdpSocket>,
    addrs: HashMap<SocketAddr, usize>,
    local_addr: SocketAddr,
    /// Poll-token offset, letting several socket groups (e.g. the tunnel
    /// endpoint and the local HTTP/3 listener) share one mio registry
    /// without token collisions.
    token_base: usize,
}

impl QuicSocket {
    pub fn new(local: &SocketAddr, registry: &Registry) -> std::io::Result<Self> {
        Self::new_with_base(local, registry, 0)
    }

    pub fn new_with_base(
        local: &SocketAddr,
        registry: &Registry,
        token_base: usize,
    ) -> std::io::Result<Self> {
        let mut socks = Slab::new();
        let mut addrs = HashMap::new();

        let socket = UdpSocket::bind(*local)?;
        let local_addr = socket.local_addr()?;
        let sid = socks.insert(socket);
        addrs.insert(local_addr, sid);

        let socket = socks.get_mut(sid).unwrap();
        registry.register(socket, Token(token_base + sid), Interest::READABLE)?;

        Ok(Self {
            socks,
            addrs,
            local_addr,
            token_base,
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// Bind an additional local address (an extra multipath path).
    pub fn add(&mut self, local: &SocketAddr, registry: &Registry) -> std::io::Result<SocketAddr> {
        let socket = UdpSocket::bind(*local)?;
        self.insert(socket, registry)
    }

    /// Add an additional path from an already-created, already-bound fd
    /// (e.g. an Android `DatagramSocket` that's been through
    /// `Network.bindSocket()`) instead of binding a fresh one ourselves.
    /// Needed because a socket this process creates and binds itself has no
    /// route authorization for a non-default Android network (cellular
    /// while Wi-Fi is default) -- see `engine.rs`'s `cellular_fd` handling.
    ///
    /// Takes ownership of `fd`: on success it's owned by the returned
    /// socket (closed on drop like any other); on error the fd is still
    /// consumed (closed), matching `UdpSocket::from_raw_fd`'s contract that
    /// the caller hands off ownership unconditionally.
    #[cfg(unix)]
    pub fn add_from_fd(
        &mut self,
        fd: std::os::unix::io::RawFd,
        registry: &Registry,
    ) -> std::io::Result<SocketAddr> {
        use std::os::unix::io::FromRawFd;
        let std_socket = unsafe { std::net::UdpSocket::from_raw_fd(fd) };
        std_socket.set_nonblocking(true)?;
        let socket = UdpSocket::from_std(std_socket);
        self.insert(socket, registry)
    }

    fn insert(&mut self, socket: UdpSocket, registry: &Registry) -> std::io::Result<SocketAddr> {
        let local_addr = socket.local_addr()?;
        let sid = self.socks.insert(socket);
        self.addrs.insert(local_addr, sid);

        let socket = self.socks.get_mut(sid).unwrap();
        registry.register(socket, Token(self.token_base + sid), Interest::READABLE)?;
        Ok(local_addr)
    }

    pub fn is_socket_token(&self, token: Token) -> bool {
        token.0 >= self.token_base && self.socks.contains(token.0 - self.token_base)
    }

    pub fn recv_from(
        &self,
        buf: &mut [u8],
        token: Token,
    ) -> std::io::Result<(usize, SocketAddr, SocketAddr)> {
        let socket = match token
            .0
            .checked_sub(self.token_base)
            .and_then(|sid| self.socks.get(sid))
        {
            Some(socket) => socket,
            None => return Err(std::io::Error::new(ErrorKind::Other, "invalid token")),
        };

        match socket.recv_from(buf) {
            Ok((len, remote)) => Ok((len, socket.local_addr()?, remote)),
            Err(e) => Err(e),
        }
    }

    pub fn send_to(&self, buf: &[u8], src: SocketAddr, dst: SocketAddr) -> std::io::Result<usize> {
        let sid = match self.addrs.get(&src) {
            Some(sid) => sid,
            None => {
                debug!("send_to: drop packet with unknown src {:?}", src);
                return Ok(buf.len());
            }
        };

        match self.socks.get(*sid) {
            Some(socket) => socket.send_to(buf, dst),
            None => {
                debug!("send_to: drop packet with unknown src {:?}", src);
                Ok(buf.len())
            }
        }
    }
}

impl PacketSendHandler for QuicSocket {
    fn on_packets_send(&self, pkts: &[(Vec<u8>, PacketInfo)]) -> tquic::Result<usize> {
        let mut count = 0;
        for (pkt, info) in pkts {
            if let Err(e) = self.send_to(pkt, info.src, info.dst) {
                if e.kind() == ErrorKind::WouldBlock {
                    return Ok(count);
                }
                return Err(tquic::Error::InvalidOperation(format!(
                    "socket send_to(): {e:?}"
                )));
            }
            count += 1;
        }
        Ok(count)
    }
}
