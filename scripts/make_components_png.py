#!/usr/bin/env python3
"""Render OPEN-SOURCE-COMPONENTS.md's summary table as a single PNG card grid.

Icons are drawn from primitives rather than fetched: vendor logos are trademarked,
and the flat house style matches the story GIFs/posters from make_system_gif.py.
License badges are colour-coded so the two entries that need legal attention --
the one copyleft component and the one mixed-licence component -- stand out.

    python scripts/make_components_png.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from PIL import Image, ImageDraw  # noqa: E402

from make_system_gif import (  # noqa: E402
    BG, PANEL, EDGE, TEXT, MUTED, DIM, GREEN, AMBER, BLUE, PURPLE, font, wrap,
)

PAD, CW, CH, COLS = 26, 392, 196, 3
TITLE_H, FOOTER_H = 132, 76


# --------------------------------------------------------------------------
# icons -- each draws inside a 64x64 box centred on (cx, cy)
# --------------------------------------------------------------------------

def ic_voice(d, cx, cy, c):
    """mic + emitted sound: speech in, speech out"""
    d.rounded_rectangle([cx - 20, cy - 22, cx - 4, cy + 6], radius=8, outline=c, width=3)
    d.arc([cx - 27, cy - 8, cx + 3, cy + 16], 0, 180, fill=c, width=3)
    d.line([cx - 12, cy + 16, cx - 12, cy + 24], fill=c, width=3)
    for i, r in enumerate((10, 19, 28)):
        d.arc([cx + 4 - r, cy - r, cx + 4 + r, cy + r], 300, 60, fill=c, width=3)


def ic_glasses(d, cx, cy, c):
    for sx in (-1, 1):
        d.rounded_rectangle([cx + sx * 17 - 15, cy - 11, cx + sx * 17 + 15, cy + 11],
                            radius=5, outline=c, width=3)
    d.line([cx - 4, cy, cx + 4, cy], fill=c, width=3)
    d.line([cx - 33, cy - 5, cx - 44, cy - 12], fill=c, width=3)
    d.line([cx + 33, cy - 5, cx + 44, cy - 12], fill=c, width=3)


def ic_discover(d, cx, cy, c):
    """concentric announce rings"""
    for r in (10, 20, 30):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=3)
    d.ellipse([cx - 5, cy - 5, cx + 5, cy + 5], fill=c)


def ic_gauge(d, cx, cy, c):
    for i, h in enumerate((14, 26, 20, 32)):
        x = cx - 27 + i * 16
        d.rounded_rectangle([x, cy + 20 - h, x + 10, cy + 20], radius=3, fill=c)
    d.line([cx - 32, cy + 26, cx + 32, cy + 26], fill=DIM, width=3)


def ic_agent(d, cx, cy, c):
    """orchestrator node with three delegates"""
    pts = [(cx - 26, cy - 18), (cx + 26, cy - 18), (cx, cy + 26)]
    for p in pts:
        d.line([cx, cy, p[0], p[1]], fill=DIM, width=3)
    for p in pts:
        d.ellipse([p[0] - 8, p[1] - 8, p[0] + 8, p[1] + 8], fill=c)
    d.ellipse([cx - 12, cy - 12, cx + 12, cy + 12], outline=c, width=3, fill=PANEL)


def ic_paths(d, cx, cy, c):
    """two links, one connection"""
    for dy, col in ((-13, AMBER), (13, c)):
        d.line([cx - 30, cy + dy, cx + 30, cy + dy], fill=col, width=3)
        for i in (-1, 0, 1):
            d.ellipse([cx + i * 18 - 4, cy + dy - 4, cx + i * 18 + 4, cy + dy + 4], fill=col)
    d.ellipse([cx - 36, cy - 6, cx - 24, cy + 6], fill=DIM)
    d.ellipse([cx + 24, cy - 6, cx + 36, cy + 6], fill=DIM)


def ic_vision(d, cx, cy, c):
    """eye = vision, bracket = language"""
    d.arc([cx - 30, cy - 26, cx + 30, cy + 22], 180, 360, fill=c, width=3)
    d.arc([cx - 30, cy - 22, cx + 30, cy + 26], 0, 180, fill=c, width=3)
    d.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=c)


def ic_layers(d, cx, cy, c):
    """stacked abstraction layers"""
    for i, dy in enumerate((-20, 0, 20)):
        d.rounded_rectangle([cx - 30, cy + dy - 7, cx + 30, cy + dy + 7],
                            radius=4, outline=c if i == 1 else DIM, width=3)


def ic_exchange(d, cx, cy, c):
    """request out, response back"""
    d.line([cx - 30, cy - 12, cx + 24, cy - 12], fill=c, width=3)
    d.polygon([(cx + 30, cy - 12), (cx + 20, cy - 18), (cx + 20, cy - 6)], fill=c)
    d.line([cx + 30, cy + 12, cx - 24, cy + 12], fill=DIM, width=3)
    d.polygon([(cx - 30, cy + 12), (cx - 20, cy + 6), (cx - 20, cy + 18)], fill=DIM)


def ic_lock(d, cx, cy, c):
    d.arc([cx - 16, cy - 30, cx + 16, cy + 2], 180, 360, fill=c, width=4)
    d.rounded_rectangle([cx - 24, cy - 6, cx + 24, cy + 28], radius=6, outline=c, width=3)
    d.ellipse([cx - 5, cy + 6, cx + 5, cy + 16], fill=c)


def ic_toolkit(d, cx, cy, c):
    for ox in (-16, 16):
        for oy in (-16, 16):
            d.rounded_rectangle([cx + ox - 13, cy + oy - 13, cx + ox + 13, cy + oy + 13],
                                radius=4, outline=c, width=3)


# --------------------------------------------------------------------------
# data -- mirrors the summary table in OPEN-SOURCE-COMPONENTS.md
# --------------------------------------------------------------------------

# icon, name, upstream, licence, version, role, accent
COMPONENTS = [
    (ic_voice, "Android TTS + STT", "aosp-mirror/platform_frameworks_base", "Apache-2.0 (API)",
     "platform API", "Speaks the answer aloud and turns the spoken question into text, "
     "in the glasses module.", GREEN),
    (ic_glasses, "Video Showcase", "rayneo-develop/VideoShowCase", "MIT", "submodule 41f728d",
     "Capture on RayNeo X3 Pro, Wi-Fi Direct streaming to the relay phone.", GREEN),
    (ic_discover, "python-zeroconf", "python-zeroconf/python-zeroconf", "LGPL-2.1-or-later",
     "zeroconf >= 0.132", "mDNS discovery of _devmon._tcp.local. from the PC side.", AMBER),
    (ic_gauge, "psutil", "giampaolo/psutil", "BSD-3-Clause", "psutil >= 5.9",
     "CPU, memory and network-interface telemetry in the reporter.", BLUE),
    (ic_agent, "Koog AI Agent", "JetBrains/koog", "Apache-2.0", "1.0.0-preview7",
     "OpenAI-compatible client: image attachments and the vision call.", PURPLE),
    (ic_paths, "Tencent TQUIC", "Tencent/tquic", "Apache-2.0", "1.6.0, feature h3",
     "IETF QUIC, HTTP/3 and multipath. Carries the phone to server hop.", BLUE),
    (ic_vision, "Qwen3-VL 4B", "QwenLM/Qwen3-VL", "Apache-2.0", "qwen/qwen3-vl-4b",
     "On-device vision-language inference on the Snapdragon X Elite.", PURPLE),
    (ic_vision, "Qwen3-VL 8B", "QwenLM/Qwen3-VL", "Apache-2.0", "server side",
     "Server-side vision-language inference, stronger reasoning.", PURPLE),
    (ic_layers, "Ktor", "ktorio/ktor", "Apache-2.0", "3.3.3",
     "Koog's HTTP layer. TCP only, which is why TQUIC exists separately.", BLUE),
    (ic_exchange, "OkHttp", "square/okhttp", "Apache-2.0", "via Ktor engine",
     "The concrete HTTP engine on Android. HTTP/1.1 and HTTP/2.", BLUE),
    (ic_lock, "BoringSSL", "google/boringssl", "Mixed: OpenSSL + ISC", "vendored by TQUIC",
     "TLS for QUIC. Vendored and cmake-built, not a direct dependency.", AMBER),
    (ic_toolkit, "AndroidX + Kotlin", "androidx - Material - coroutines - Gradle", "Apache-2.0",
     "9 artifacts", "UI, lifecycle, coroutines and build tooling for the devmon app.", GREEN),
]

LICENCE_COLOR = {
    "LGPL-2.1-or-later": AMBER,
    "Mixed: OpenSSL + ISC": AMBER,
    "MIT": GREEN,
    "BSD-3-Clause": GREEN,
}

FOOTER = [
    "python-zeroconf is the only copyleft component -- identify it as LGPL, do not group it with the Apache/MIT entries.",
    "BoringSSL is mixed-licence: reproduce its LICENSE verbatim rather than summarising it.  Amber badges need legal attention.",
]


def badge(d, x, y, text, color):
    f = font(14, True)
    w = d.textlength(text, font=f) + 20
    d.rounded_rectangle([x, y, x + w, y + 24], radius=12, fill=PANEL, outline=color, width=2)
    d.text((x + w / 2, y + 12), text, font=f, fill=color, anchor="mm")
    return w


def card(d, x, y, spec):
    icon, name, upstream, lic, version, role, accent = spec
    d.rounded_rectangle([x, y, x + CW, y + CH], radius=14, fill=PANEL, outline=EDGE, width=2)
    d.rounded_rectangle([x, y, x + 6, y + CH], radius=3, fill=accent)

    icon(d, x + 62, y + 60, accent)
    d.text((x + 116, y + 40), name, font=font(22, True), fill=TEXT, anchor="lm")
    d.text((x + 116, y + 66), upstream, font=font(13), fill=MUTED, anchor="lm")

    for i, line in enumerate(wrap(d, role, font(15), CW - 44)[:2]):
        d.text((x + 22, y + 108 + i * 21), line, font=font(15), fill=MUTED, anchor="lm")

    badge(d, x + 22, y + CH - 40, lic, LICENCE_COLOR.get(lic, BLUE))
    d.text((x + CW - 22, y + CH - 28), version, font=font(14), fill=DIM, anchor="rm")


def main() -> None:
    rows = (len(COMPONENTS) + COLS - 1) // COLS
    width = COLS * CW + (COLS + 1) * PAD
    height = TITLE_H + rows * CH + (rows + 1) * PAD + FOOTER_H

    img = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(img)

    d.text((width // 2, 46), "Open Source Components", font=font(38, True), fill=TEXT, anchor="mm")
    d.text((width // 2, 84),
           "AllergenAR  --  upstream sources, licences and the versions this repository pins",
           font=font(18), fill=MUTED, anchor="mm")
    d.line([PAD, TITLE_H - 18, width - PAD, TITLE_H - 18], fill=EDGE, width=2)

    for i, spec in enumerate(COMPONENTS):
        cx = PAD + (i % COLS) * (CW + PAD)
        cy = TITLE_H + (i // COLS) * (CH + PAD)
        card(d, cx, cy, spec)

    fy = height - FOOTER_H + 8
    d.line([PAD, fy - 12, width - PAD, fy - 12], fill=EDGE, width=2)
    for i, line in enumerate(FOOTER):
        d.text((width // 2, fy + 12 + i * 22), line, font=font(14), fill=DIM, anchor="mm")

    out = Path("docs/media/open-source-components.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(f"{out}: {width}x{height}, {out.stat().st_size / 1024:.0f} KB, "
          f"{len(COMPONENTS)} components")


if __name__ == "__main__":
    main()
