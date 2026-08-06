# Verifying the MPQUIC tunnel terminus (answer_mode=forward) with real devices

Manual verification procedure for the MPQUIC-tunnel-to-Ollama forwarding feature added to
`tquic-vlm-server-interface` (see `src/main.rs::spawn_mpquic_tunnel`). This exercises the real
Android `MPQUIC Client` app talking to the EC2 server over the internet, distinct from the
CLI-only (`mpquic-client` + `h3_sender.py` on the same box) end-to-end check already run during
development.

This is a manual runbook, not automated test coverage — see `docs/interface-guide.md` and the
crate-level tests for what's covered by `cargo test`.

## Topology

```
[Windows laptop, same WiFi as phone]  --h3_sender.py-->  [Phone: MPQUIC Client app]
                                                                  |
                                                    MPQUIC tunnel over the internet
                                                                  |
                                                                  v
                                                  [EC2: tquic-vlm-server-interface,
                                                   --mpquic-bind 0.0.0.0:10000,
                                                   answer_mode=forward]
                                                                  |
                                                                  v
                                                  [EC2: Ollama, 127.0.0.1:11434,
                                                   qwen3-vl:8b]
```

"The relevant listener" for this test is the MPQUIC tunnel terminus (`--mpquic-bind`), **not**
the older plain-JSON `/v1/infer` listener (`--bind`) that the separate `ai.koog.tquicdemo` app
uses — those are two independent UDP sockets in the same process. `--mpquic-bind` is put on
**10000** here to reuse an already-open port; `--bind` stays on its normal default (19500) since
it isn't part of this test.

## Part A — EC2

1. Confirm Ollama is up: `curl -s http://127.0.0.1:11434/api/tags` should list `qwen3-vl:8b`.
2. Confirm UDP 10000 is reachable from the internet, not just localhost: check both
   `sudo ufw status` (or `iptables`) **and** the AWS security group's inbound rules for UDP
   10000 — earlier testing only proved `--bind 10000` reachable for the plain-JSON code path, so
   this is worth re-checking for the mpquic-bind listener specifically.
3. Start the server from `~/tquic-vlm-server-interface`:
   ```bash
   ./target/release/tquic-vlm-server-interface \
     --bind 0.0.0.0:19500 \
     --mpquic-bind 0.0.0.0:10000 \
     --vlm-base-url http://127.0.0.1:11434/v1 \
     --vlm-model qwen3-vl:8b
   ```
   Cert/key default to `certs/server.crt`/`certs/server.key`, already present from earlier
   builds — no need to pass `--mpquic-cert`/`--mpquic-key` separately.
4. Watch the log for the mpquic tunnel thread coming up cleanly (no `TlsFail`/bind error). The
   plain listener starting on 19500 doesn't matter for this test — just confirm it doesn't crash
   the whole process.

## Part B — Android

1. If the phone doesn't already have it, install the client app (already built — no rebuild
   needed, forward mode is server-side only and this APK is untouched by that change):
   ```
   adb install -r mpquic\apks\client-debug.apk
   ```
2. Open **MPQUIC Client**, phone on WiFi.
3. **Server address**: `<EC2 public IP>:10000` (replace the default `10.0.2.2:4433`).
4. Leave multipath/scheduler/congestion at their defaults, tap **Connect**. Watch the log pane
   for a `connected` event and the stats panel showing an active path.
5. Set **HTTP/3 in port** to something like `47443` (default is fine), tap **Start HTTP/3 RX**.
   Note the phone's LAN IP shown at the top (`deviceIps`) — needed in Part C.

## Part C — Trigger a real VLM request (Windows laptop, same WiFi as the phone)

1. One-time: `pip install aioquic` if not already present.
2. Build an OpenAI-shaped chat-completion request file with a real image, e.g. from this repo's
   checked-in sample image:
   ```powershell
   python -c "
   import base64, json
   img = base64.b64encode(open('phone/shared/koog/multiverse/tquic-demo-android/app/src/main/assets/vlm-demo/sample_image.jpg','rb').read()).decode()
   json.dump({
     'model': 'qwen3-vl:8b',
     'messages': [{'role':'user','content':[
       {'type':'text','text':'What is in this image?'},
       {'type':'image_url','image_url':{'url': f'data:image/jpeg;base64,{img}'}}
     ]}]
   }, open('mpquic_request.json','w'))
   "
   ```
3. Send it through the tunnel:
   ```
   python mpquic/tools/h3_sender.py <phone-LAN-IP> mpquic_request.json --port 47443 --path /infer --content-type application/json --timeout 90
   ```

## Part D — What "working" looks like

- **Laptop console**: `response 200, <N> B body ... response body: b'{"id":...,"choices":[{"message":{"content":"..."}}],...}'` — Ollama's real, complete answer about the sample image, byte-identical to what `curl`ing Ollama directly would return.
- **Phone log**: an `h3_response` (or similar) event around the same time, tunnel byte counters ticking up on the stats panel / path graph.
- **EC2 log**: an inbound H3 request on the mpquic listener, a forward dispatched to
  `127.0.0.1:11434/v1/chat/completions`, and the response relayed back — no JSON
  parsing/repackaging log lines, since none happens.
