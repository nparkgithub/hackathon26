# 10-Minute Presentation Plan: AI-Powered Allergen Detection

**Format:** 10 slides, ~10 minutes total (600s), Snapdragon hackathon pitch with room for a full live demo.
**Theme:** "Local When Possible. Cloud When Needed. Safety Always."
**Derived from:** `docs/presentation_plan.md` (full 15-slide deck) — same facts, same repo grounding, restructured for time. Use that doc for extended architecture-diagram detail if you need more depth in Q&A; this doc is the on-stage script.

**Pacing rule:** total speaking time should land at ~9:00–9:30, leaving buffer for the live demo running long — the demo slide (Slide 8) is the most likely to overrun, so protect its budget by trimming Slides 1-2 first if you're behind.

---

## Slide 1 — Vision + The Problem (0:00–0:45, 45s)

**Key Message:** Millions of people can't tell if a meal will hurt them — until it's too late.

**Speaker Notes:** Open on mission: food allergies affect millions worldwide, across nine major categories (peanuts, tree nuts, dairy, eggs, shellfish, fish, soy, wheat, sesame). The failure mode is lack of information at the moment of decision — a menu doesn't list hidden ingredients, a home-cooked dish has no label, packaging is absent or misread. State the theme line once here.

**Visual:** Hero image (AR glasses + plated food), 9-allergen icon row.

**Demo Callout:** "In a few minutes you'll watch this exact question get asked and answered, live."

---

## Slide 2 — User Experience Journey (0:45–1:30, 45s)

**Key Message:** Look at food, ask a question out loud, get an answer in seconds — hands-free, glance-based.

**Speaker Notes:** Walk the linear journey: user wears RayNeo AR glasses → looks at food (plated meal, packaged product, or menu) → asks aloud "Are there any allergens in this food?" → glasses capture image + voice → relayed to the Samsung Galaxy S25 Ultra → AI decision happens on the phone → AR-rendered answer appears back in the user's field of view. Emphasize this is glance-based, not app-based — critical for a safety tool people will actually use in the moment.

**Visual:** Horizontal 4-box journey diagram: Glasses (look/ask) → Phone (relay) → AI decision → AR response.

**Demo Callout:** This exact sequence is what the live demo performs end-to-end.

---

## Slide 3 — Complete Solution + Architecture (1:30–2:30, 60s)

**Key Message:** One phone-based orchestration agent, two inference paths, one unified safety answer.

**Speaker Notes:** Introduce the three pillars: AR capture (RayNeo glasses), an orchestration agent on the Samsung S25 Ultra that decides *where* inference should run, and two backends — local (Snapdragon X Elite via LM Studio, Qwen3-VL:4B) and cloud (AWS EC2 via Ollama, Qwen3-VL:8B) — bridged by Multipath QUIC. Land the theme line as the through-line for the rest of the talk.

**Visual:** Full architecture diagram — Glasses → Phone (decision diamond) → {Local X Elite, Cloud AWS} → AR response, protocol labels on each arrow (mDNS/TCP, HTTP/3 over QUIC).

**Demo Callout:** "Everything on this diagram, you'll see execute live — nothing here is simulated."

---

## Slide 4 — Local-First Decision Making: mDNS Discovery (2:30–3:15, 45s)

**Key Message:** The phone finds nearby AI compute the same way it finds a nearby printer — automatically, with no configuration.

**Speaker Notes:** On each request, the phone's agent runs mDNS discovery on the LAN for a nearby AI-capable host, then verifies that host's inference service (LM Studio) is actually up before committing to the local path — a real, working implementation, not just a design idea. Worth a candid technical note for the engineers in the room: some networks block multicast via AP client isolation even though unicast still works, so the system falls back to a direct subnet TCP probe rather than failing silently. This kind of defensive fallback is why the reliability story holds up.

**Visual:** Sequence diagram: Phone → mDNS query → LAN → X Elite host responds with service record → phone verifies LM Studio health.

**Demo Callout:** If time allows, show the discovery response (service name + model label) on screen before the image is even sent.

---

## Slide 5 — Edge AI: Snapdragon X Elite + LM Studio + Qwen3-VL:4B (3:15–4:15, 60s)

**Key Message:** A 4B-parameter vision-language model, running entirely on-device, answers before a cloud round-trip could even complete.

**Speaker Notes:** This is the platform-fit slide for Qualcomm engineers in the room — say directly: genuine NPU-accelerated multimodal inference on Snapdragon X Elite, not a cloud call in disguise. Once discovered, the phone posts the image and parsed question to LM Studio's local OpenAI-compatible endpoint; Qwen3-VL:4B identifies food items, infers ingredients, and flags matches against the nine allergen categories. Benefits: no image ever leaves the LAN (privacy), no per-inference cloud cost, sub-second responses, works fully offline.

**Visual:** X Elite device photo + LM Studio inset; four benefit icons (Privacy / Cost / Latency / Offline).

**Demo Callout:** Show the response arrive on the AR display with a visible latency counter — make "ultra-low latency" a measured claim.

---

## Slide 6 — Cloud Fallback: AWS EC2 + Ollama + Qwen3-VL:8B (4:15–5:15, 60s)

**Key Message:** When no local host is available, the system fails over to a larger, more capable cloud model — automatically and invisibly to the user.

**Speaker Notes:** When mDNS discovery finds nothing, the phone routes the same request to AWS instead — same shape, different destination, zero behavior change for the user. A real Rust QUIC/HTTP-3 server (`tquic-vlm-server-interface`) accepts the request over Multipath QUIC and forwards it to Ollama, serving the larger Qwen3-VL:8B. Frame this explicitly: "graceful degradation to a *stronger*, not weaker, fallback" — the opposite of the usual edge/cloud tradeoff.

**Visual:** AWS EC2 + Ollama + Qwen3-VL:8B badge, positioned side-by-side against the X Elite box from Slide 5 to reinforce "same interface, different backend."

**Demo Callout:** This path gets triggered live in the demo by disabling the local host mid-session.

---

## Slide 7 — TQUIC Multipath QUIC over Wi-Fi + 5G (5:15–6:15, 60s)

**Key Message:** The cloud path doesn't rely on a single network — it uses Wi-Fi and 5G simultaneously for speed and resilience.

**Speaker Notes:** The deepest networking slide — lean on real capability. The phone runs a Multipath QUIC client (built on Tencent's `tquic`) maintaining simultaneous paths over Wi-Fi and cellular, with a pluggable scheduler (`minrtt`, `redundant`, `roundrobin`) deciding traffic split in real time, and live per-path stats (RTT, congestion window, loss) visible on screen. If one network degrades, the connection survives on the other with no reconnect — true seamless failover, not a retry-after-timeout hack.

**Visual:** Two parallel paths (Wi-Fi, 5G) converging into one QUIC connection to AWS; small live-stats readout mockup.

**Demo Callout:** "In a minute, we're going to kill Wi-Fi mid-request on purpose — watch the per-path graph shift to 5G with zero dropped request."

---

## Slide 8 — Live Demo (6:15–8:45, 150s)

**Key Message:** Everything just described, running now, on real hardware.

**Speaker Notes:** Three beats, narrated live so the audience tracks the architecture in real time: **(1)** Local-first success — ask the allergen question wearing the glasses, local path answers fast, call out the path indicator and latency. **(2)** Forced cloud fallback — disable the local X Elite host (or its LM Studio service), re-ask, show mDNS discovery come up empty and the silent reroute to AWS. **(3)** Multipath resilience — during the cloud request, disrupt Wi-Fi and show the per-path stats shift to 5G without the request failing. This is where every prior slide pays off — protect this slide's time budget above all others.

**Visual:** Live glasses/phone screen mirror, projected large, with a persistent "Local / Cloud / Network" status indicator if available.

**Demo Callout:** Have a pre-recorded backup video of all three beats cued in case live venue networking is unreliable — say so plainly if switching to it rather than pretending it's live.

---

## Slide 9 — Benefits + Comparison (8:45–9:15, 30s)

**Key Message:** Local and cloud aren't competitors — they're two tools the system picks between based on what's available, and both land on the four things that matter: speed, privacy, cost, reliability.

**Speaker Notes:** Move fast here — this slide summarizes, it doesn't introduce. One line per row of the comparison table (see visual): local wins latency/privacy/cost/offline; cloud wins raw model capacity; the system's answer quality never degrades to "bad," only to "slower but smarter."

**Visual:** Compact comparison table (Local X Elite/4B vs Cloud AWS/8B across Latency, Privacy, Cost, Model capacity, Connectivity requirement).

**Demo Callout:** Reference that the audience just watched both rows execute live.

---

## Slide 10 — Future Vision + Close (9:15–9:45, 30s)

**Key Message:** Real NPU inference, real multipath networking, real AR glasses — one working demo, with a clear path to more.

**Speaker Notes:** Widen the lens briefly: the same pattern (edge-first inference, automatic cloud fallback, multipath resilience, AR delivery) generalizes to dietary restrictions beyond allergens, medication interaction checks, or accessibility use cases. Close on the judging hook: this is genuine Snapdragon X Elite NPU usage plus genuine Multipath QUIC failover, wired into a working AR demo — not a slideware concept. End with the theme line and thanks.

**Visual:** Theme line large, centered, closing banner; small roadmap icon row optional if time allows.

**Demo Callout:** None — hold for Q&A.
