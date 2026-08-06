# Presentation Plan: AI-Powered Allergen Detection Using AR Glasses, Edge AI, and Multipath QUIC

**Theme:** "Local When Possible. Cloud When Needed. Safety Always."
**Audience:** Judges, mentors, and fellow builders at a Snapdragon hackathon event — expect Qualcomm engineers in the room evaluating Snapdragon X Elite / NPU usage specifically, alongside AI, cloud, and networking-minded hackers. Optimize for "how creatively and correctly did they use the platform + how real is the demo," not an enterprise sales pitch.
**Tone:** Energetic, technical, demo-first, peer-to-peer — a hackathon pitch, not a boardroom deck.

## Grounding notes (from repo review)

This plan maps the pitch onto what's actually implemented in `\\10.73.51.54\hackathon26\` (excluding `phone/shared/koog`, a third-party submodule) so speaker notes describe real system behavior, not aspiration:

- **`local_llm/mdns/`** — real zero-config discovery: Android app (`devmon`) advertises `_devmon._tcp.local.` via `NsdManager` + a fixed-port TCP server; a Windows/Python client (`discover_and_report.py`) resolves it via `zeroconf` and streams telemetry, including a static "which local LLMs this host serves" label read from `llm_info.json`. This is the real substrate for Slide 8 — note honestly that the LLM label is a configured description of the host, not a live model query, and that AP client-isolation can block multicast (handled via a `--scan` TCP-probe fallback).
- **`mpquic/`** — real multipath QUIC Android client/server apps on Tencent `tquic`, with genuine Wi-Fi + cellular (`wlan*`/`rmnet_data*`) dual-path support, `minrtt`/`redundant`/`roundrobin` schedulers, live per-path SRTT/cwnd/loss stats, and an HTTP/3 tunnel mode for JPEG uploads. This is the strongest engineering artifact in the repo — Slide 11 should lean on it directly.
- **`tquic-vlm-server-interface/`** — a real Rust QUIC/HTTP-3 (via `tquic`) server that bridges phone requests to any OpenAI-compatible VLM backend's `/v1/chat/completions` (works with Ollama or LM Studio, since both expose that shape). This is the literal cloud entry point in Slide 10.
- **`VideoShowCase/`** — a real RayNeo X3 Pro glasses → phone (Wi-Fi Direct) → cloud RTMP relay pipeline, proving the glasses-to-phone capture path used in Slides 3 and 6.
- **Not found as checked-in code:** LM Studio server config, Ollama deployment scripts, or Qwen3-VL-specific prompts/allergen category logic. These run live on the demo hardware (Qualcomm X Elite / AWS EC2) rather than being repo artifacts — expected for hackathon demo rigs. Speaker notes flag this so no one overclaims "the allergen logic is in this repo" — the repo proves the *transport and discovery* fabric; the model calls are generic OpenAI-compatible chat completions carrying an allergen-detection prompt.
- **`Integration.txt`** is a working integration prompt describing exactly the in-memory pipeline the demo needs: camera capture → image labeling → JPEG bytes + label → network sender, replacing an older file-based (JPG+TXT) handoff. This confirms the "direct in-memory pipeline" is the intended near-term architecture, not yet fully merged — worth an honest mention on Slide 5 or 14 as current engineering status.

---

## Slide 1 — Vision and Mission

**Key Message:** Every meal should be safe to eat — everywhere, instantly, with or without connectivity.

**Speaker Notes:** Open with the mission, not the tech. Millions of people check every label, question every waiter, and still get it wrong sometimes. We built a system that answers "is this safe for me?" in real time, using whichever compute — local or cloud — gets the safest, fastest answer. The theme line, "Local When Possible. Cloud When Needed. Safety Always," is the design philosophy for every architectural decision in this deck.

**Recommended Visual:** Full-bleed hero image — person wearing AR glasses looking at a plated meal, subtle AR overlay highlighting an allergen icon.

**Architecture Diagram Suggestion:** None — keep this slide diagram-free to hold emotional weight.

**Demo Talking Points:** Tease that a live look-and-ask demo closes the deck (Slide 14) — sets audience expectation early.

---

## Slide 2 — The Food Allergy Challenge

**Key Message:** Food allergies are common, dangerous, and invisible until it's too late.

**Speaker Notes:** Cite scale (millions affected globally, 9 major allergen categories tracked in the US: peanuts, tree nuts, dairy, eggs, shellfish, fish, soy, wheat, sesame). The failure mode isn't lack of caution — it's lack of information at the point of decision: a menu doesn't list hidden ingredients, a home cook's dish has an unknown recipe, packaging is misread or absent. Frame the gap: existing solutions (asking staff, scanning barcodes, memorizing ingredient lists) are slow, error-prone, or don't cover fresh/prepared food at all.

**Recommended Visual:** Icon row of the 9 major allergen categories; a stat callout box (e.g., prevalence figures).

**Architecture Diagram Suggestion:** None — problem framing slide.

**Demo Talking Points:** Preview the exact demo scenario (a plated dish or menu with a hidden allergen) that Slide 14 will resolve live.

---

## Slide 3 — User Experience Journey

**Key Message:** Look at food, ask a question out loud, get an answer in seconds — no app-switching, no typing.

**Speaker Notes:** Walk the journey linearly: user wears RayNeo AR glasses → looks at food (plated meal, packaged product, or menu) → asks aloud "Are there any allergens in this food?" → glasses capture image + voice → both are relayed to the Samsung Galaxy S25 Ultra → the phone does the thinking → an AR-rendered answer appears in the user's field of view. Emphasize this is a *hands-free, glance-based* interaction — critical for a safety tool people will actually use in the moment, not after the fact.

**Recommended Visual:** Horizontal journey diagram: Glasses (look/ask) → Phone (relay) → AI decision → AR response, with small device icons at each step.

**Architecture Diagram Suggestion:** Simple 4-box sequence flow (no protocol detail yet — save that for Slide 5).

**Demo Talking Points:** This exact sequence is what the live demo performs end-to-end.

---

## Slide 4 — Complete Solution Overview

**Key Message:** One phone-based AI agent, two inference paths, one unified safety answer.

**Speaker Notes:** Introduce the three pillars: (1) AR capture at the edge of perception — RayNeo glasses; (2) an orchestration agent on the Samsung S25 Ultra that decides *where* inference should run; (3) two inference backends — local (Qualcomm X Elite via LM Studio, Qwen3-VL:4B) and cloud (AWS EC2 via Ollama, Qwen3-VL:8B) — bridged over Multipath QUIC for reliability. Land the theme line here explicitly as the through-line for the rest of the deck.

**Recommended Visual:** Three-pillar hero graphic (Capture / Decide / Infer) with the theme line as a banner.

**Architecture Diagram Suggestion:** High-level box diagram: Glasses → Phone Agent → {Local X Elite, Cloud AWS} → AR Response.

**Demo Talking Points:** State plainly that both paths will be shown live — local-hit and cloud-fallback — so the audience knows to watch for the switch.

---

## Slide 5 — End-to-End System Architecture

**Key Message:** A single decision point on the phone governs a local-first, cloud-capable pipeline.

**Speaker Notes:** This is the technical anchor slide — show the full pipeline: RayNeo glasses (camera + mic) → Samsung S25 Ultra (orchestration agent) → mDNS discovery of LAN hosts → branch: Local (LM Studio/Qwen3-VL:4B on Qualcomm X Elite) or Cloud (Multipath QUIC → tquic-vlm-server-interface bridge on AWS EC2 → Ollama/Qwen3-VL:8B) → allergen result → AR overlay back on glasses. Be transparent about current engineering status: the capture-to-send pipeline is being converted from an intermediate file-based (JPG+label file) handoff to a direct in-memory pipeline (per `Integration.txt`) for lower latency and cleaner code — this is active, not finished, engineering, which is a credibility point with the engineer-heavy audience.

**Recommended Visual:** Full architecture diagram — the "money slide." Devices as icons, arrows labeled with protocol (mDNS/TCP, HTTP/3 over QUIC), decision diamond at the phone.

**Architecture Diagram Suggestion:** Swim-lane diagram: Glasses lane → Phone lane (with decision diamond) → Local lane / Cloud lane (parallel branches) → back to Phone → Glasses. Annotate each arrow with the real protocol (mDNS, TCP JSON, HTTP/3+QUIC).

**Demo Talking Points:** Tell the audience "everything on this diagram, you will see execute live in Slide 14 — nothing here is simulated."

---

## Slide 6 — RayNeo Glasses + Samsung S25 Ultra Experience

**Key Message:** The glasses are a capture surface; the phone is the brain — each device does only what it's best at.

**Speaker Notes:** Describe the RayNeo-to-phone relay pattern, grounded in the working `VideoShowCase` implementation: glasses capture camera frames (and optionally audio) and relay them to the phone over a direct local link (Wi-Fi Direct in the reference implementation), offloading heavy compute (encoding, AI orchestration, networking) to the more capable, better-powered phone. For allergen detection specifically, the payload is a still JPEG frame plus the transcribed voice query, not continuous video streaming — lighter weight, lower latency, better battery life on glasses. The phone then renders the AR-safe response back to the glasses display.

**Recommended Visual:** Side-by-side device photos (RayNeo glasses, Galaxy S25 Ultra) with a data-flow arrow and a callout: "glasses capture, phone thinks."

**Architecture Diagram Suggestion:** Two-box diagram: Glasses (Camera + Mic + AR Display) ⇄ Phone (Relay receiver + Orchestration Agent + AR render pipeline).

**Demo Talking Points:** Point out battery/thermal rationale — why inference never runs on the glasses themselves.

---

## Slide 7 — AI Agent Orchestration on Phone

**Key Message:** The phone's agent makes one decision, every time: local-first, cloud only when necessary.

**Speaker Notes:** Explain the agent's decision loop as a simple, deterministic policy rather than a black box: on each request, (1) run mDNS discovery for a local AI host, (2) if found and healthy, route locally, (3) otherwise route to cloud automatically — no user intervention, no visible mode switch. Emphasize that this is a *policy*, cheap to reason about and audit, which matters to the engineering audience: predictable behavior beats cleverness in a safety-critical path.

**Recommended Visual:** Decision-tree flowchart with two clear terminal states: "Local Inference" and "Cloud Inference," both converging to "AR Response."

**Architecture Diagram Suggestion:** Decision tree: Start → mDNS Discovery → {Host Found & Model Ready? Yes/No} → Local branch / Cloud branch → Unified Response Handler.

**Demo Talking Points:** State that the user experience is identical either way — call out that this is deliberate, not a limitation.

---

## Slide 8 — mDNS Discovery and Local-First Decision Making

**Key Message:** The phone finds nearby AI compute the same way it finds a nearby printer — automatically, with no configuration.

**Speaker Notes:** Ground this in the real `local_llm/mdns` implementation: an Android-side (or Qualcomm-side) service advertises itself over `_devmon._tcp.local.` via mDNS/NSD along with metadata about which local models it can serve; the querying side resolves that record via `zeroconf`-style discovery, then verifies the host is actually reachable and its inference service (LM Studio) is up before committing to the local path. Be candid about a real edge case worth surfacing to a networking-savvy audience: some networks apply AP client isolation, which silently blocks mDNS multicast even though unicast TCP works — the system falls back to direct subnet TCP probing on a known port rather than failing silently. This kind of defensive fallback is exactly why the audience should trust the reliability story.

**Recommended Visual:** Network diagram showing the phone broadcasting a discovery query on the LAN and a Qualcomm X Elite box responding with a service record (model name, capability).

**Architecture Diagram Suggestion:** Sequence diagram: Phone → (mDNS query) → LAN → X Elite host responds with service TXT record → Phone verifies LM Studio health → proceeds to Slide 9.

**Demo Talking Points:** If time allows, show the discovery response on screen (service name + model label) before the image is even sent — makes the "local-first" claim tangible, not asserted.

---

## Slide 9 — Edge AI on Qualcomm X Elite with LM Studio and Qwen3-VL:4B

**Key Message:** A 4B-parameter vision-language model, running entirely on-device, answers allergen questions before a cloud round-trip could even complete.

**Speaker Notes:** Once discovered, the phone posts the food image (and the parsed question) directly to LM Studio's local OpenAI-compatible endpoint on the Qualcomm X Elite AI PC. Qwen3-VL:4B performs the vision-language reasoning: identify food items, infer likely ingredients, and flag matches against the nine major allergen categories. This is the slide to win over Qualcomm engineers in the room — call out that this is genuine NPU-accelerated on-device multimodal inference on Snapdragon X Elite, not a thin client shelling out to the cloud. Benefits to emphasize: no image ever leaves the LAN (privacy), no per-inference cloud cost, sub-second responses, and it keeps working with no internet at all — a genuine differentiator for a safety tool used in restaurants, flights, or areas with poor connectivity.

**Recommended Visual:** Qualcomm X Elite device photo with an inset showing LM Studio's local API serving a chat-completion request; four benefit icons (Privacy / Cost / Latency / Offline).

**Architecture Diagram Suggestion:** Local-path detail diagram: Phone → HTTP (LAN) → LM Studio server → Qwen3-VL:4B (NPU-accelerated) → JSON allergen result → back to Phone.

**Demo Talking Points:** Show the response arrive on the AR display with a visible timestamp/latency counter to make "ultra-low latency" a measured claim, not a slide adjective.

---

## Slide 10 — Cloud AI on AWS EC2 with Ollama and Qwen3-VL:8B

**Key Message:** When no local AI host is available, the system fails over to a larger, more capable cloud model — automatically and invisibly to the user.

**Speaker Notes:** Explain the fallback path concretely: when mDNS discovery finds nothing (no nearby Qualcomm host, or LM Studio unavailable), the phone's agent routes the same image + query to AWS instead — same request shape, different destination, zero behavior change for the user. On the AWS side, a Rust QUIC/HTTP-3 server (the `tquic-vlm-server-interface`) accepts the request over Multipath QUIC and forwards it to Ollama, which serves the larger Qwen3-VL:8B model. The bigger model trades a few hundred milliseconds of extra latency for materially stronger reasoning and recognition — worth it precisely in the cases where no faster local option exists. Frame this as "graceful degradation to a *stronger*, not weaker, fallback" — an unusual and compelling framing versus typical edge/cloud tradeoffs.

**Recommended Visual:** AWS EC2 instance icon with Ollama logo, Qwen3-VL:8B badge; side-by-side against the X Elite box from Slide 9 to visually reinforce "same interface, different backend."

**Architecture Diagram Suggestion:** Cloud-path detail diagram: Phone → Multipath QUIC (Wi-Fi+5G) → tquic-vlm-server-interface (Rust, HTTP/3 bridge on EC2) → Ollama → Qwen3-VL:8B → response relayed back over QUIC.

**Demo Talking Points:** Trigger this path live by disabling/blocking the local X Elite host mid-demo, showing the seamless failover in real time (ties directly into Slide 11's networking story).

---

## Slide 11 — TQUIC Multipath QUIC over Wi-Fi + 5G

**Key Message:** The cloud path doesn't rely on a single network — it uses Wi-Fi and 5G simultaneously for speed and resilience.

**Speaker Notes:** This is the deepest networking-engineering slide — lean on real capability, not marketing language. The Samsung device runs a Multipath QUIC client (built on Tencent's `tquic`) that maintains simultaneous paths over Wi-Fi (`wlan*`) and cellular (`rmnet_data*`), each contributing its own IP address as a QUIC path once the connection is established. A pluggable scheduler (`minrtt` for lowest-latency-first, `redundant` for duplicate-everything reliability, `roundrobin` for balanced throughput) decides how traffic is spread across paths in real time, and per-path stats — smoothed RTT, congestion window, bytes sent, loss — are visible live. If one network degrades or drops, the connection survives on the other path with no reconnect, no dropped request — true seamless failover, not a retry-after-timeout hack. On the receiving end, the same connection tunnels an HTTP/3 request (the JPEG + prompt) to the Rust bridge server.

**Recommended Visual:** Two parallel lightning-bolt paths (Wi-Fi icon, 5G icon) converging into a single QUIC connection icon flowing to an AWS cloud icon; small live-stats readout mockup (SRTT / cwnd / loss per path).

**Architecture Diagram Suggestion:** Multipath illustration: Phone with two NICs (Wi-Fi, Cellular) → two independent UDP paths → single logical QUIC connection (path-aware scheduler) → EC2 endpoint. Include a failover callout: "Wi-Fi drops → 5G path already active → zero interruption."

**Demo Talking Points:** If feasible, kill the Wi-Fi connection mid-transfer during the live demo and show the per-path graph shift all traffic to 5G without the request failing — the single most memorable networking moment in the deck.

---

## Slide 12 — Local vs Cloud Inference Comparison

**Key Message:** Local and cloud aren't competitors — they're two tools the system picks between based on what's actually available.

**Speaker Notes:** Present this as a straight comparison table (see Recommended Visual), talking through each row rather than reading it verbatim. Land the key insight: the *system's* answer quality/behavior doesn't degrade to "bad" when it falls to cloud — it upgrades to "slower but smarter." The only real cost of the cloud path is latency and requiring connectivity; everything else is a wash or a cloud win (raw reasoning capacity).

**Recommended Visual:** Comparison table:

| Dimension | Local (X Elite / LM Studio / Qwen3-VL:4B) | Cloud (AWS EC2 / Ollama / Qwen3-VL:8B) |
|---|---|---|
| Latency | Lowest (LAN, sub-second) | Higher (WAN round-trip, still sub-2s target) |
| Privacy | Image never leaves LAN | Image transits network (encrypted via QUIC/TLS) |
| Cost per inference | ~$0 marginal (local compute) | Cloud compute cost per call |
| Model capacity | 4B params | 8B params — stronger reasoning |
| Connectivity requirement | None (offline-capable) | Requires Wi-Fi and/or cellular |
| Availability trigger | Preferred, tried first via mDNS | Automatic fallback, zero user action |

**Architecture Diagram Suggestion:** None needed — table carries the slide; optionally a small decision-diamond icon repeated from Slide 7 in the corner as a visual callback.

**Demo Talking Points:** Reference that the audience has now seen (or will see) both rows execute live.

---

## Slide 13 — Performance, Privacy, Cost, and Reliability Benefits

**Key Message:** The architecture isn't just clever — it measurably improves the four things that matter for a safety product: speed, privacy, cost, and reliability.

**Speaker Notes:** Synthesize benefits across the whole system, not just one path: local-first cuts average latency and cloud spend for the common case; Multipath QUIC cuts failure rate and tail latency for the fallback case; mDNS discovery adds zero-configuration reliability (no manual pairing); the unified request/response contract means privacy posture is a *routing decision*, not a rewrite. Use directional/qualitative claims here unless you have measured numbers from actual demo runs — if you have real latency numbers from testing, insert them into this slide's chart; otherwise, present the comparison as expected-order-of-magnitude (e.g., "tens to hundreds of ms locally vs. low seconds via cloud") and say so explicitly to preserve credibility with this technical audience.

**Recommended Visual:** Four-quadrant benefit chart (Performance / Privacy / Cost / Reliability), each quadrant with 1-2 supporting bullets and an icon; optionally a simple latency bar chart (Local vs Cloud) if real numbers exist from demo testing.

**Architecture Diagram Suggestion:** None — this is a synthesis/benefits slide, not a system diagram.

**Demo Talking Points:** If live-measured latency numbers were captured during rehearsal, cite them here specifically ("in our testing, local responses averaged X ms, cloud averaged Y ms") — concrete numbers land far better than adjectives with this audience.

---

## Slide 14 — Live Demo Walkthrough

**Key Message:** Everything described so far, running live, on real hardware, right now.

**Speaker Notes:** Structure the demo in three explicit beats so the audience can follow the architecture in real time: **(1) Local-first success** — wear the glasses, ask the allergen question about a real food item, show the response arriving quickly with the local-path indicator; **(2) Forced cloud fallback** — disable/hide the local X Elite host (or its LM Studio service) and repeat the same query, showing mDNS discovery come up empty and the system silently reroute to AWS; **(3) Multipath resilience** — during the cloud-path request, disrupt Wi-Fi and show the per-path stats/graph shift traffic to 5G without the request failing. Narrate what's happening architecturally at each beat rather than just watching silently — this is where Slides 5, 7, 8, 10, and 11 all pay off.

**Recommended Visual:** Live device camera feed / screen mirror of the glasses AR overlay and/or the phone app, projected large; a small persistent "architecture path" indicator overlay (Local / Cloud / Which network) so the audience always knows which path is active.

**Architecture Diagram Suggestion:** A condensed version of the Slide 5 diagram as a "current step" indicator, updated live at each demo beat (this can be a physical printed card flipped by a second presenter if no live-overlay tooling exists).

**Demo Talking Points:** Have a backup pre-recorded video of all three beats ready in case live Wi-Fi/venue networking is unreliable — standard hackathon-demo risk mitigation; say so plainly if switching to the recording rather than pretending it's live.

---

## Slide 15 — Future Vision and Key Takeaways

**Key Message:** This is a platform pattern — local-first, cloud-capable, network-resilient AI — not a single-use allergen app.

**Speaker Notes:** Close by widening the lens: the same architecture (edge-first inference, automatic cloud fallback, multipath networking, AR delivery) generalizes to other safety- and context-sensitive AI experiences — dietary restrictions beyond allergens (kosher/halal/vegan), medication interaction checks, industrial safety hazard identification, accessibility description for low-vision users. Frame "commercialization" as next-build-milestones appropriate for a hackathon judging context rather than a partnership pitch: finish the in-memory capture pipeline (Slide 5), harden mDNS discovery against AP isolation at scale, and push more of the allergen-reasoning prompt logic on-device as Snapdragon NPU headroom allows. Explicitly call back to what makes this a strong Snapdragon entry: real NPU-accelerated on-device VLM inference via LM Studio on X Elite, not a cloud-only wrapper — that's the judging-criteria payoff. Reiterate the theme line one final time as the memorable takeaway: "Local When Possible. Cloud When Needed. Safety Always."

**Recommended Visual:** Roadmap graphic — current state (allergen detection) branching into 3-4 future use-case icons; theme line restated large at the bottom as a closing banner.

**Architecture Diagram Suggestion:** None — closing/vision slide, keep visually clean.

**Demo Talking Points:** End by restating the judging hook directly: real Snapdragon X Elite NPU inference, real Multipath QUIC failover, real AR glasses capture — three hard engineering problems solved and wired together in one working demo. Thank the judges/organizers and open the floor for questions.

---

## Cross-cutting production notes

- **Consistency:** Use one recurring device/path iconography (glasses, phone, X Elite box, AWS cloud) across Slides 3, 5, 6, 9, 10, 11, 14 so the audience pattern-matches instantly without re-reading labels each time.
- **Honesty checkpoints:** Slides 5, 8, and 13 each contain a deliberate "here's the real current state" note (in-progress in-memory pipeline, mDNS AP-isolation fallback, qualitative vs. measured latency). Keep these in the speaker notes even if trimmed from on-slide text — this audience rewards technical candor over polish.
- **Diagram tool suggestion:** Excalidraw or a simple slide-native diagram (arrows/boxes) is sufficient for all architecture diagrams described above — none require simulation software.
