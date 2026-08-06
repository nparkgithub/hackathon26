# 5-Minute Presentation Plan: AI-Powered Allergen Detection

**Format:** 6 slides, ~5 minutes total (300s), Snapdragon hackathon lightning-round pitch.
**Theme:** "Local When Possible. Cloud When Needed. Safety Always."
**Derived from:** `docs/presentation_plan.md` (full 15-slide deck) — same facts, same repo grounding, compressed for time. Use that doc for full architecture-diagram detail and extended speaker notes; this doc is the on-stage script.

**Pacing rule:** total speaking time should land at ~4:30–4:45, leaving 15-30s buffer for a stumble or a transition — don't fill the whole 5:00.

---

## Slide 1 — Vision + The Problem (0:00–0:30, 30s)

**Key Message:** Millions of people can't tell if a meal will hurt them — until it's too late.

**Condensed Speaker Notes:** "Food allergies affect millions of people. A menu doesn't list hidden ingredients. A home-cooked dish has no label. We built glasses that answer 'is this safe?' the moment you look at your food and ask." State the theme line once, here, and don't repeat it verbatim again until the close.

**Visual:** Hero image (AR glasses + plated food) with the 9 allergen icons underneath.

**Demo Callout:** "You'll see this exact question asked and answered, live, in about three minutes."

---

## Slide 2 — Solution + Architecture at a Glance (0:30–1:15, 45s)

**Key Message:** One phone-based AI agent, two inference paths — local first, cloud when needed.

**Condensed Speaker Notes:** Collapse the journey and architecture into one breath: RayNeo AR glasses capture the food image and the spoken question, relay to a Samsung Galaxy S25 Ultra, whose on-device agent tries a nearby Snapdragon X Elite PC first via automatic network discovery, and only falls back to AWS cloud if no local host answers. Land: "same user experience either way — the phone decides, not the person."

**Visual:** Compressed architecture diagram — Glasses → Phone (decision diamond) → {Local X Elite | Cloud AWS} → AR response.

**Demo Callout:** None — set up the two paths the demo will exercise.

---

## Slide 3 — Edge AI: Snapdragon X Elite + LM Studio (1:15–2:00, 45s)

**Key Message:** On-device inference — Qwen3-VL:4B running locally via LM Studio on Snapdragon X Elite, no cloud round-trip required.

**Condensed Speaker Notes:** The model identifies food items, infers ingredients, flags matches against the nine major allergen categories, and returns a result without ever leaving the device or the LAN. Be accurate if asked: in the current build, LM Studio on X Elite runs this inference on CPU — NPU/GPU acceleration isn't wired up yet, it's the clear next optimization (see appendix). The win today is architectural, not silicon-specific: zero image data leaves the LAN, no per-inference cloud cost, and it keeps working fully offline.

**Visual:** X Elite device photo + LM Studio screenshot inset; four benefit icons (Privacy / Cost / Speed / Offline).

**Demo Callout:** Flag that the upcoming demo will show a visible latency counter on this exact path.

---

## Slide 4 — Cloud Fallback + Multipath QUIC (2:00–2:45, 45s)

**Key Message:** When local isn't available, the system fails over to a bigger cloud model over a network link that doesn't have a single point of failure.

**Condensed Speaker Notes:** If no local host is discovered, the same request routes to AWS EC2, where Ollama serves the larger Qwen3-VL:8B — a stronger, not weaker, fallback. The link to AWS uses Multipath QUIC, riding Wi-Fi and 5G at the same time; if one network drops, the connection survives on the other with no reconnect. This is the single most technically distinctive piece of the stack — say so.

**Visual:** Two-path lightning-bolt graphic (Wi-Fi + 5G) converging into one QUIC connection to an AWS icon.

**Demo Callout:** "Watch for this in the demo — we're going to kill Wi-Fi mid-request on purpose."

---

## Slide 5 — Live Demo (2:45–4:15, 90s)

**Key Message:** Everything just described, running now, on real hardware.

**Condensed Speaker Notes:** Two beats only, tight timing: **(1)** Ask the allergen question wearing the glasses, local path answers fast — call out the path indicator. **(2)** Disable the local host (or just narrate "now forcing cloud") and re-ask — show the fallback to AWS and, if time allows, the Wi-Fi-to-5G path switch on the live stats. Narrate each beat in one sentence — don't go quiet while it runs.

**Visual:** Live glasses/phone screen mirror, projected large, with a small "Local / Cloud / Network" status indicator overlay if available.

**Demo Callout:** This IS the demo slide — have a 60-90s backup video cued in case live networking fails at the venue.

---

## Slide 6 — Close (4:15–4:45, 30s)

**Key Message:** Real NPU inference, real multipath networking, real AR glasses — one working safety demo.

**Condensed Speaker Notes:** "Local when possible. Cloud when needed. Safety always. Thank you — happy to take questions." Keep it to one breath; don't introduce new information here.

**Visual:** Theme line large, centered, closing banner.

**Demo Callout:** None — hold for Q&A.

---

## Appendix — "Why This Component?" backup slides (not on the 5-minute clock)

These are Q&A/backup slides, not part of the timed 6-slide run — keep them in the deck after Slide 6 so you can jump to one if a judge asks, without disrupting the 4:30–4:45 pacing above. Each is written to answer a specific "why did you choose X over the obvious alternative" question directly.

### Backup A — Why Koog?

**Key Message:** Koog gives the agent memory and model-portability, so it isn't reinvented from scratch.

**Speaker Notes:** Koog handles conversation history/context summarization across turns and lets the agent move sessions between LLM backends (local model today, cloud model on fallback, or a different model entirely tomorrow) without losing context. This is the same problem LangChain/LangGraph solve on the Python side — Koog is that role for this stack. Without it, every local↔cloud handoff would either drop context or require hand-rolled summarization.

**Visual:** Simple before/after: "session context" box persisting across a model-swap arrow (Model A → Model B), labeled "Koog."

### Backup B — Why Multipath QUIC (Tencent tquic)?

**Key Message:** Multiple paths exist for two reasons — reliability and aggregate throughput — and a smart scheduler picks the best one in real time instead of just tolerating whichever one you're stuck on.

**Speaker Notes:** If you're on a single path, a bad Wi-Fi signal is something you just suffer through — the connection doesn't know a better path exists. With Multipath QUIC, the client holds Wi-Fi and 5G open simultaneously; a scheduler like `minrtt` actively routes traffic over whichever path currently measures the best round-trip time, and can shift away from a degrading path without dropping the connection. It's not just failover — it's continuous best-path selection, plus the option to aggregate both paths for higher throughput.

**Visual:** Two paths with live "quality" indicators (good/bad signal icon) and an arrow showing traffic shifting to the better path in real time.

### Backup C — Why mDNS?

**Key Message:** mDNS lets the phone find nearby AI-capable devices with zero user setup.

**Speaker Notes:** The goal is discovering available devices and services on the LAN without the user manually entering an IP address or pairing anything — the same category of "just works" discovery as finding a nearby printer or Chromecast. That's the whole justification: local-first only works as an *experience* if discovery is invisible.

**Visual:** Phone silently broadcasting a query, X Elite host responding — no UI, no user action shown.

### Backup D — Why AR Glasses as the Companion Use Case?

**Key Message:** Glasses are faster to use than a phone in the exact moment safety matters — and the phone is still there as a fallback.

**Speaker Notes:** If you're already wearing the glasses, asking them is quicker than pulling out and unlocking a phone — and speed matters when the moment to check is right before the first bite. If glasses aren't available or aren't worn, the same question still works by phone alone — the glasses are an accelerant to the experience, not a hard dependency. Pair this with the honest note from Slide 3: LM Studio on X Elite is CPU-only in this build, no NPU/GPU acceleration yet — worth stating proactively if a Qualcomm engineer doesn't ask, since it's the clearest immediate next step for this project.

**Visual:** Side-by-side: "glasses out already, just ask" vs. "phone: unlock, open app, aim camera, ask" — visually contrast step count.
