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

**Key Message:** Real on-device NPU inference — Qwen3-VL:4B running locally via LM Studio on Snapdragon X Elite.

**Condensed Speaker Notes:** This is the platform-fit slide for the judges — say directly: "this is genuine NPU-accelerated multimodal inference on-device, not a cloud call in disguise." The model identifies food items, infers ingredients, flags matches against the nine major allergen categories, and returns a result before a cloud round-trip could even complete. Zero image data leaves the LAN.

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
