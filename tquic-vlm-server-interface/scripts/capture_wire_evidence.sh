#!/usr/bin/env bash
# Sniffs loopback traffic on both hops (client -> this server on UDP 19500,
# this server -> VLM backend on TCP 11434) during one mock_client_demo.sh
# request, plus samples socket ownership (`ss -tunap`) throughout, so the
# routing can be verified from the wire rather than trusting application
# logs. Requires passwordless (or interactive) sudo for tcpdump/ss. Run on
# the same box as the running tquic-vlm-server-interface + VLM backend.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

sudo rm -f /tmp/quic19500.pcap /tmp/ollama11434.pcap /tmp/conn_monitor.log

sudo tcpdump -i lo -nn -s 0 -w /tmp/quic19500.pcap 'udp port 19500' &
TCPDUMP_QUIC_PID=$!
sudo tcpdump -i lo -nn -s 0 -w /tmp/ollama11434.pcap 'tcp port 11434' &
TCPDUMP_OLLAMA_PID=$!
sleep 1

(
  for i in $(seq 1 200); do
    { echo "--- sample $i ($(date +%s.%N)) ---"; sudo ss -tunap 2>/dev/null | grep -E '11434|19500'; } >> /tmp/conn_monitor.log
    sleep 0.2
  done
) &
MONITOR_PID=$!

./scripts/mock_client_demo.sh

sleep 1
kill "$MONITOR_PID" 2>/dev/null || true
sudo kill "$TCPDUMP_QUIC_PID" "$TCPDUMP_OLLAMA_PID" 2>/dev/null || true
sleep 1
sudo chmod 644 /tmp/quic19500.pcap /tmp/ollama11434.pcap

echo "Captures written to /tmp/quic19500.pcap, /tmp/ollama11434.pcap, /tmp/conn_monitor.log" >&2
echo "First QUIC packet header:  sudo tcpdump -r /tmp/quic19500.pcap -nn -X -c 1" >&2
echo "Ollama request/response:   sudo tcpdump -r /tmp/ollama11434.pcap -A" >&2
echo "Socket ownership samples:  grep -A1 sample /tmp/conn_monitor.log" >&2
