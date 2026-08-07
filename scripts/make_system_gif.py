#!/usr/bin/env python3
"""Render the end-to-end offline allergy-check story as an animated GIF.

Pure Pillow -- no ffmpeg, no ImageMagick, no headless browser. Frames are drawn
with ImageDraw, collected in memory, and written as a single animated GIF with
per-frame durations, so static beats hold on one frame while only transitions
carry motion. That keeps the frame count (and the file) small.

    python scripts/make_system_gif.py                # both presets
    python scripts/make_system_gif.py --preset slide # 960x540
    python scripts/make_system_gif.py --preset readme

Everything is drawn on a 960x540 design canvas and downscaled for the smaller
preset, so there is exactly one set of layout coordinates to maintain.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 960, 540

# Flat palette -- no gradients. GIF quantises cleanly and LZW compresses hard.
BG = (15, 23, 42)
PANEL = (30, 41, 59)
EDGE = (51, 65, 85)
TEXT = (226, 232, 240)
MUTED = (148, 163, 184)
DIM = (71, 85, 105)
GREEN = (52, 211, 153)
AMBER = (251, 191, 36)
RED = (248, 113, 113)
BLUE = (96, 165, 250)
PURPLE = (167, 139, 250)

CAPTION_Y = 470

_FONT_CANDIDATES = {
    False: ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"],
    True: ["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf"],
}
_font_cache: dict[tuple[int, bool], ImageFont.FreeTypeFont] = {}


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    key = (size, bold)
    if key not in _font_cache:
        for path in _FONT_CANDIDATES[bold]:
            try:
                _font_cache[key] = ImageFont.truetype(path, size)
                break
            except OSError:
                continue
        else:  # no Windows fonts -- anchors still work with a truetype-less default
            _font_cache[key] = ImageFont.load_default(size)
    return _font_cache[key]


# --------------------------------------------------------------------------
# primitives
# --------------------------------------------------------------------------

def new_frame() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, CAPTION_Y, W, H], fill=(9, 14, 27))
    d.line([0, CAPTION_Y, W, CAPTION_Y], fill=EDGE, width=2)
    return img, d


def caption(d: ImageDraw.ImageDraw, text: str, color=TEXT, sub: str | None = None) -> None:
    if sub:
        d.text((W // 2, CAPTION_Y + 26), text, font=font(25, True), fill=color, anchor="mm")
        d.text((W // 2, CAPTION_Y + 52), sub, font=font(18), fill=MUTED, anchor="mm")
    else:
        d.text((W // 2, CAPTION_Y + 35), text, font=font(26, True), fill=color, anchor="mm")


def person(d, cx, cy, mood="neutral", glasses=False, scale=1.0, color=TEXT):
    """Iconic avatar: filled head + shoulders, features knocked out in BG."""
    r = 26 * scale
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    body_top = cy + r * 0.75
    d.rounded_rectangle(
        [cx - r * 1.25, body_top, cx + r * 1.25, body_top + r * 2.1],
        radius=r * 0.7, fill=color,
    )
    d.rectangle([cx - r * 1.3, body_top + r * 1.5, cx + r * 1.3, body_top + r * 2.2], fill=BG)
    d.rounded_rectangle(
        [cx - r * 1.25, body_top, cx + r * 1.25, body_top + r * 1.6],
        radius=r * 0.7, fill=color,
    )

    eye_dy = -r * 0.15
    eye_dx = r * 0.38
    if glasses:
        gy = cy + eye_dy
        gh = r * 0.34
        for sx in (-1, 1):
            d.rounded_rectangle(
                [cx + sx * eye_dx - r * 0.30, gy - gh, cx + sx * eye_dx + r * 0.30, gy + gh],
                radius=r * 0.12, fill=BG, outline=GREEN, width=max(2, int(2 * scale)),
            )
        d.line([cx - r * 0.06, gy, cx + r * 0.06, gy], fill=GREEN, width=max(2, int(2 * scale)))
    else:
        er = r * 0.11
        for sx in (-1, 1):
            d.ellipse(
                [cx + sx * eye_dx - er, cy + eye_dy - er, cx + sx * eye_dx + er, cy + eye_dy + er],
                fill=BG,
            )

    mw, my = r * 0.44, cy + r * 0.42
    lw = max(2, int(2.5 * scale))
    if mood == "annoyed":
        d.arc([cx - mw, my, cx + mw, my + r * 0.5], 200, 340, fill=BG, width=lw)
        for sx in (-1, 1):  # angled brows
            bx = cx + sx * eye_dx
            d.line([bx - sx * r * 0.22, cy - r * 0.48, bx + sx * r * 0.18, cy - r * 0.30],
                   fill=BG, width=lw)
    elif mood == "happy":
        d.arc([cx - mw, my - r * 0.32, cx + mw, my + r * 0.30], 20, 160, fill=BG, width=lw)
    else:
        d.line([cx - mw * 0.7, my + r * 0.06, cx + mw * 0.7, my + r * 0.06], fill=BG, width=lw)


def bubble(d, cx, cy, w, h, text, color=PANEL, fg=TEXT, size=20, tail=True):
    d.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2],
                        radius=14, fill=color, outline=EDGE, width=2)
    d.text((cx, cy), text, font=font(size), fill=fg, anchor="mm")
    if tail:
        d.polygon([(cx - 14, cy + h / 2 - 1), (cx + 6, cy + h / 2 - 1), (cx - 6, cy + h / 2 + 16)],
                  fill=color)


def wifi_icon(d, cx, cy, crossed=True, color=RED):
    for i, rr in enumerate((10, 19, 28)):
        d.arc([cx - rr, cy - rr, cx + rr, cy + rr], 210, 330,
              fill=color if crossed else GREEN, width=4)
    d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=color if crossed else GREEN)
    if crossed:
        d.line([cx - 24, cy - 24, cx + 24, cy + 14], fill=color, width=5)


def cell_icon(d, cx, cy, crossed=True, color=RED):
    for i, hgt in enumerate((8, 15, 22, 29)):
        x = cx - 24 + i * 14
        d.rectangle([x, cy + 12 - hgt, x + 9, cy + 12], fill=color if crossed else GREEN)
    if crossed:
        d.line([cx - 28, cy - 20, cx + 30, cy + 18], fill=color, width=5)


def cloud(d, cx, cy, color=DIM, label="Cloud VLM", label_color=DIM):
    d.ellipse([cx - 62, cy - 20, cx - 6, cy + 30], fill=color)
    d.ellipse([cx - 30, cy - 38, cx + 34, cy + 26], fill=color)
    d.ellipse([cx + 6, cy - 16, cx + 62, cy + 30], fill=color)
    d.rounded_rectangle([cx - 58, cy + 4, cx + 58, cy + 30], radius=13, fill=color)
    d.text((cx, cy + 56), label, font=font(18, True), fill=label_color, anchor="mm")


def glasses_pict(d, cx, cy, color=GREEN):
    for sx in (-1, 1):
        d.rounded_rectangle([cx + sx * 34 - 26, cy - 15, cx + sx * 34 + 26, cy + 15],
                            radius=6, fill=None, outline=color, width=3)
    d.line([cx - 8, cy, cx + 8, cy], fill=color, width=3)
    d.line([cx - 60, cy - 8, cx - 74, cy - 14], fill=color, width=3)
    d.line([cx + 60, cy - 8, cx + 74, cy - 14], fill=color, width=3)


def phone_pict(d, cx, cy, color=BLUE, screen=None):
    d.rounded_rectangle([cx - 22, cy - 34, cx + 22, cy + 34], radius=7, outline=color, width=3)
    d.rounded_rectangle([cx - 16, cy - 26, cx + 16, cy + 24], radius=3,
                        fill=screen if screen else None)
    d.line([cx - 6, cy + 29, cx + 6, cy + 29], fill=color, width=3)


def laptop_pict(d, cx, cy, color=PURPLE):
    d.rounded_rectangle([cx - 38, cy - 30, cx + 38, cy + 14], radius=5, outline=color, width=3)
    d.polygon([(cx - 54, cy + 26), (cx + 54, cy + 26), (cx + 44, cy + 16), (cx - 44, cy + 16)],
              fill=color)


def device_box(d, x, y, w, h, title, sub, color, pict, active=False, note=None):
    d.rounded_rectangle([x, y, x + w, y + h], radius=16,
                        fill=PANEL if not active else EDGE,
                        outline=color if active else EDGE, width=3 if active else 2)
    pict(d, x + w / 2, y + 48, color)
    d.text((x + w / 2, y + h - 46), title, font=font(20, True), fill=TEXT, anchor="mm")
    d.text((x + w / 2, y + h - 22), sub, font=font(16), fill=MUTED, anchor="mm")
    if note:
        d.text((x + w / 2, y - 16), note, font=font(15, True), fill=color, anchor="mm")


def link(d, x0, x1, y, label=None, color=DIM, phase=None, dots=3, dot_color=BLUE, back=False):
    d.line([x0, y, x1, y], fill=color, width=3)
    if label:
        d.text(((x0 + x1) / 2, y - 22), label, font=font(15), fill=MUTED, anchor="mm")
    if phase is not None:
        for i in range(dots):
            t = (phase + i / dots) % 1.0
            t = 1.0 - t if back else t
            px = x0 + (x1 - x0) * t
            d.ellipse([px - 6, y - 6, px + 6, y + 6], fill=dot_color)


def food(d, cx, cy, mark="?", mark_color=AMBER):
    d.rounded_rectangle([cx - 30, cy - 40, cx + 30, cy + 40], radius=8, fill=EDGE, outline=MUTED, width=2)
    d.rectangle([cx - 30, cy - 14, cx + 30, cy + 6], fill=PANEL)
    for i in range(3):
        d.line([cx - 22, cy + 16 + i * 8, cx + 22, cy + 16 + i * 8], fill=DIM, width=3)
    d.text((cx, cy - 4), mark, font=font(22, True), fill=mark_color, anchor="mm")


# --------------------------------------------------------------------------
# scenes
# --------------------------------------------------------------------------

frames: list[Image.Image] = []
durations: list[int] = []


def add(img: Image.Image, ms: int) -> None:
    frames.append(img)
    durations.append(ms)


def status_row(d, wifi_ok=False, cell_ok=False):
    wifi_icon(d, 812, 62, crossed=not wifi_ok)
    cell_icon(d, 892, 62, crossed=not cell_ok)
    # "Internet", not "Wi-Fi" -- Wi-Fi Direct between the devices still works; it is
    # the route to the outside world that is missing.
    d.text((812, 96), "Internet", font=font(14), fill=RED if not wifi_ok else GREEN, anchor="mm")
    d.text((892, 96), "Cellular", font=font(14), fill=RED if not cell_ok else GREEN, anchor="mm")


def scene_problem():
    """1 - no connectivity, and the question that needs answering."""
    for i in range(6):
        img, d = new_frame()
        status_row(d)
        person(d, 220, 250, mood="neutral")
        food(d, 340, 300)
        pulse = TEXT if i % 2 == 0 else AMBER
        bubble(d, 430, 130, 330, 62, "Does this contain peanuts?", fg=pulse)
        cloud(d, 760, 200)
        d.line([408, 268, 690, 218], fill=DIM, width=3)  # the route that does not exist
        d.line([534, 228, 570, 262], fill=RED, width=6)
        d.line([534, 262, 570, 228], fill=RED, width=6)
        caption(d, "No Wi-Fi network. No cellular. No cloud.",
                sub="The vision model that answers this lives on a server you cannot reach.")
        add(img, 420)


def scene_failure():
    """2 - the phone tries, fails, the patient is stuck."""
    for i in range(8):  # spinner
        img, d = new_frame()
        status_row(d)
        person(d, 220, 250, mood="neutral")
        food(d, 340, 300)
        phone_pict(d, 620, 240, BLUE, screen=PANEL)
        ang = i * 45
        d.arc([600, 220, 640, 260], ang, ang + 90, fill=BLUE, width=4)
        caption(d, "Checking...", MUTED)
        add(img, 90)

    for i in range(4):  # failure + annoyance
        img, d = new_frame()
        status_row(d)
        person(d, 220, 250, mood="annoyed")
        food(d, 340, 300)
        phone_pict(d, 620, 240, RED, screen=PANEL)
        d.text((620, 240), "!", font=font(30, True), fill=RED, anchor="mm")
        bubble(d, 430, 130, 300, 56, "No connection", fg=RED)
        caption(d, "The answer is unreachable.", RED,
                sub="Offline is not a degraded mode here -- it is no mode at all.")
        add(img, 240 if i < 3 else 900)


def scene_arrival():
    """3 - a second person walks in carrying the whole stack."""
    for i in range(10):
        t = i / 9
        x = 1030 - 240 * t
        img, d = new_frame()
        status_row(d)
        person(d, 220, 250, mood="annoyed")
        food(d, 340, 300)
        person(d, x, 250, mood="happy", glasses=True)
        d.text((x, 340), "RayNeo X3 Pro", font=font(15, True), fill=GREEN, anchor="mm")
        laptop_pict(d, x - 96, 300)
        caption(d, "Someone else walks in.", MUTED)
        add(img, 70)

    for _ in range(1):
        img, d = new_frame()
        status_row(d)
        person(d, 220, 250, mood="annoyed")
        food(d, 340, 300)
        person(d, 790, 250, mood="happy", glasses=True)
        d.text((790, 340), "RayNeo X3 Pro", font=font(15, True), fill=GREEN, anchor="mm")
        laptop_pict(d, 694, 300)
        d.text((694, 336), "Snapdragon X Elite", font=font(15, True), fill=PURPLE, anchor="mm")
        phone_pict(d, 600, 288, BLUE)
        d.text((600, 336), "Galaxy", font=font(15, True), fill=BLUE, anchor="mm")
        caption(d, "Glasses. Phone. Snapdragon X Elite.", GREEN,
                sub="Three devices, still with no Wi-Fi and no cellular between them and the world.")
        add(img, 1500)


BOX_Y, BOX_H, BOX_W = 196, 150, 224
GX, PX, LX = 52, 368, 684


def system_base(d, active=None, note_g=None, note_p=None, note_l=None,
                header="One local subnet. No internet."):
    status_row(d)
    if header:
        d.text((W // 2, 52), header, font=font(19, True), fill=GREEN, anchor="mm")
    device_box(d, GX, BOX_Y, BOX_W, BOX_H, "RayNeo X3 Pro", "camera - TTS - STT",
               GREEN, glasses_pict, active == "g", note_g)
    device_box(d, PX, BOX_Y, BOX_W, BOX_H, "Galaxy phone", "devmon relay :8080",
               BLUE, phone_pict, active == "p", note_p)
    device_box(d, LX, BOX_Y, BOX_W, BOX_H, "Snapdragon X Elite", "Qwen3-VL, local",
               PURPLE, laptop_pict, active == "l", note_l)


def scene_mesh():
    """4 - the devices find each other with no infrastructure."""
    for i in range(14):
        ph = (i / 14) % 1.0
        img, d = new_frame()
        system_base(d)
        link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2, "Wi-Fi Direct", phase=ph, dot_color=GREEN)
        link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2, "mDNS discovery", phase=ph, dot_color=BLUE)
        # Centred on the phone pictogram and capped, so the expanding ring never
        # runs through the device label underneath it.
        rr = 14 + (i % 7) * 6
        cx, cy = PX + BOX_W / 2, BOX_Y + 48
        d.arc([cx - rr, cy - rr, cx + rr, cy + rr], 0, 360, fill=BLUE, width=2)
        d.text((W // 2, 402), "_devmon._tcp.local.", font=font(17, True), fill=BLUE, anchor="mm")
        caption(d, "They discover each other with no router.", GREEN,
                sub="Zero-config mDNS on a link-local subnet -- nothing here leaves the room.")
        add(img, 95)


def scene_capture():
    """5 - spoken question + a photo of the label, both on the glasses."""
    for i in range(4):  # shutter flash
        img, d = new_frame()
        system_base(d, active="g", note_g="capture")
        link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2, "Wi-Fi Direct")
        link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2, "mDNS discovery")
        food(d, 164, 396, mark="", mark_color=TEXT)
        if i % 2 == 0:
            d.rounded_rectangle([GX + 8, BOX_Y + 8, GX + BOX_W - 8, BOX_Y + BOX_H - 8],
                                radius=12, outline=TEXT, width=4)
        bubble(d, 300, 116, 380, 58, '"What allergens are in this?"', fg=GREEN)
        caption(d, "Ask out loud. Look at the label.", GREEN,
                sub="Speech-to-text and capture both run on the glasses.")
        add(img, 130)

    for i in range(8):  # image travels to the phone
        t = i / 7
        img, d = new_frame()
        system_base(d, active="g")
        link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2, "Wi-Fi Direct", color=GREEN)
        link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2, "mDNS discovery")
        px = (GX + BOX_W) + (PX - GX - BOX_W) * t
        d.rounded_rectangle([px - 14, BOX_Y + BOX_H / 2 - 12, px + 14, BOX_Y + BOX_H / 2 + 12],
                            radius=4, fill=GREEN)
        d.text((W // 2, 402), "JPEG + prompt", font=font(17, True), fill=GREEN, anchor="mm")
        caption(d, "Ask out loud. Look at the label.", GREEN,
                sub="Speech-to-text and capture both run on the glasses.")
        add(img, 80)


def scene_inference():
    """6 - relay to the laptop, local vision inference."""
    for i in range(8):
        t = i / 7
        img, d = new_frame()
        system_base(d, active="p", note_p="relay")
        link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2)
        link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2, "POST /analyze", color=BLUE)
        px = (PX + BOX_W) + (LX - PX - BOX_W) * t
        d.rounded_rectangle([px - 14, BOX_Y + BOX_H / 2 - 12, px + 14, BOX_Y + BOX_H / 2 + 12],
                            radius=4, fill=BLUE)
        caption(d, "The phone relays it onward.", BLUE,
                sub="multipart image + query over the local link -- HTTP/1.1 on TCP, or HTTP/3 over QUIC.")
        add(img, 80)

    for i in range(10):
        img, d = new_frame()
        system_base(d, active="l", note_l="inference")
        link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2)
        link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2)
        cx, cy = LX + BOX_W / 2, 402
        for k in range(3):
            on = (i // 2) % 3 == k
            d.ellipse([cx - 26 + k * 24, cy - 7, cx - 12 + k * 24, cy + 7],
                      fill=PURPLE if on else DIM)
        d.text((W // 2, 402), "Qwen3-VL running on-device", font=font(17, True),
               fill=PURPLE, anchor="mm")
        caption(d, "The model runs right here.", PURPLE,
                sub="Vision-language inference on the Snapdragon X Elite -- no server, no round trip.")
        add(img, 110)


def scene_answer():
    """7 - answer flows back and is shown + spoken."""
    # Two legs, not one sweep: the packet has to hop laptop->phone and then
    # phone->glasses, otherwise the dot slides straight across the phone box.
    legs = [(LX, PX + BOX_W), (PX, GX + BOX_W)]
    for leg, (x0, x1) in enumerate(legs):
        for i in range(5):
            t = i / 4
            img, d = new_frame()
            system_base(d)
            link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2, color=AMBER if leg == 1 else DIM)
            link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2, color=AMBER)
            px = x0 + (x1 - x0) * t
            d.ellipse([px - 9, BOX_Y + BOX_H / 2 - 9, px + 9, BOX_Y + BOX_H / 2 + 9], fill=AMBER)
            d.text((W // 2, 402), "allergen findings", font=font(17, True), fill=AMBER, anchor="mm")
            caption(d, "The answer comes back the same way.", AMBER)
            add(img, 80)

    for i in range(6):  # lens display + TTS
        img, d = new_frame()
        system_base(d, active="g", note_g="display + speak")
        link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2)
        link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2)
        d.rounded_rectangle([44, 366, 452, 452], radius=12, fill=PANEL, outline=AMBER, width=3)
        d.text((64, 388), "Contains: PEANUTS, MILK", font=font(21, True), fill=AMBER)
        d.text((64, 418), "Informational only -- check the packaging.", font=font(15), fill=MUTED)
        for k in range(3):  # TTS waves
            if (i % 3) >= k:
                rr = 16 + k * 13
                d.arc([486 - rr, 408 - rr, 486 + rr, 408 + rr], 300, 60, fill=GREEN, width=3)
        person(d, 872, 392, mood="happy", scale=0.72)
        caption(d, "On the lens, and spoken aloud.", GREEN,
                sub="Still no internet. Still no cellular. The whole loop stayed in the room.")
        add(img, 200 if i < 5 else 1200)


def scene_card():
    """8 - final hold."""
    img, d = new_frame()
    system_base(d, header=None)  # the title below replaces the usual header line
    d.text((W // 2, 64), "Allergy answers with zero connectivity",
           font=font(30, True), fill=TEXT, anchor="mm")
    d.text((W // 2, 100), "One local subnet. No internet.",
           font=font(19, True), fill=GREEN, anchor="mm")
    link(d, GX + BOX_W, PX, BOX_Y + BOX_H / 2, "Wi-Fi Direct", color=GREEN)
    link(d, PX + BOX_W, LX, BOX_Y + BOX_H / 2, "mDNS + local subnet", color=BLUE)
    d.text((W // 2, 402), "capture -> relay -> local vision model -> lens + speech",
           font=font(18, True), fill=MUTED, anchor="mm")
    caption(d, "Nothing leaves the room.", GREEN)
    add(img, 2600)


# --------------------------------------------------------------------------
# story 2: multipath QUIC over Wi-Fi + cellular
# --------------------------------------------------------------------------

PHX, SVX, BW2, BH2, BY2 = 66, 704, 190, 140, 200
PATH_A_Y, PATH_B_Y = 244, 312


def signal_bars(d, cx, cy, filled, color, label, crossed=False):
    for i in range(4):
        hgt = 8 + i * 7
        x = cx - 26 + i * 14
        d.rectangle([x, cy + 12 - hgt, x + 9, cy + 12],
                    fill=color if i < filled else DIM)
    if crossed:
        d.line([cx - 30, cy - 18, cx + 30, cy + 16], fill=RED, width=5)
    d.text((cx, cy + 32), label, font=font(14), fill=color if not crossed else RED, anchor="mm")


def progress(d, x, y, w, frac, color, label=None, h=16):
    d.rounded_rectangle([x, y, x + w, y + h], radius=h // 2, fill=PANEL, outline=EDGE, width=2)
    if frac > 0:
        d.rounded_rectangle([x, y, x + max(h, w * min(frac, 1.0)), y + h], radius=h // 2, fill=color)
    if label:
        d.text((x, y - 16), label, font=font(15), fill=MUTED, anchor="lm")


def illustrative(d):
    d.text((W - 26, 452), "illustrative -- not measured", font=font(14), fill=DIM, anchor="rm")


def mp_base(d, wifi_bars=2, cell_bars=4, wifi_dead=False, header=None,
            a_color=DIM, b_color=DIM, a_phase=None, b_phase=None, a_dead=False):
    signal_bars(d, 806, 56, wifi_bars, AMBER, "Wi-Fi", crossed=wifi_dead)
    signal_bars(d, 892, 56, cell_bars, GREEN, "5G")
    if header:
        d.text((W // 2, 52), header, font=font(19, True), fill=TEXT, anchor="mm")

    device_box(d, PHX, BY2, BW2, BH2, "Galaxy phone", "MPQUIC client", BLUE, phone_pict)
    device_box(d, SVX, BY2, BW2, BH2, "X Elite", "tunnel :4433", PURPLE, laptop_pict)

    x0, x1 = PHX + BW2, SVX
    for y, color, phase, name, dead in (
        (PATH_A_Y, a_color, a_phase, "wlan0  (Wi-Fi)", a_dead),
        (PATH_B_Y, b_color, b_phase, "rmnet_data0  (5G)", False),
    ):
        d.line([x0, y, x1, y], fill=color, width=3)
        d.text(((x0 + x1) / 2, y - 20), name, font=font(15), fill=color, anchor="mm")
        if dead:
            d.line([(x0 + x1) / 2 - 18, y - 14, (x0 + x1) / 2 + 18, y + 14], fill=RED, width=5)
            d.line([(x0 + x1) / 2 - 18, y + 14, (x0 + x1) / 2 + 18, y - 14], fill=RED, width=5)
        elif phase is not None:
            for i in range(4):
                t = (phase + i / 4) % 1.0
                px = x0 + (x1 - x0) * t
                d.ellipse([px - 6, y - 6, px + 6, y + 6], fill=color)


def mp_both_up():
    for i in range(5):
        img, d = new_frame()
        mp_base(d, header="Wi-Fi and 5G, both available")
        caption(d, "Two good links. One gets used.", MUTED,
                sub="Wi-Fi is two bars and congested. 5G is strong and idle.")
        add(img, 420)


def mp_default():
    for i in range(10):
        img, d = new_frame()
        mp_base(d, header="Android's default route", a_color=AMBER, a_phase=(i / 10) % 1.0)
        d.text((W // 2, 372), "rmnet_data0 stays idle -- Wi-Fi is 'connected', so Wi-Fi wins",
               font=font(16), fill=DIM, anchor="mm")
        caption(d, "The phone picks Wi-Fi and stays there.", AMBER,
                sub="Connected is not the same as good. Nothing re-evaluates that choice.")
        add(img, 150)


def mp_cost():
    for i in range(10):
        img, d = new_frame()
        mp_base(d, header="A vision request is heavy", a_color=AMBER, a_phase=(i / 10) % 1.0)
        progress(d, 250, 384, 460, i / 34, AMBER, "4 MB JPEG uploading")
        d.text((W // 2, 428), f"{i * 0.4:.1f} s", font=font(20, True), fill=AMBER, anchor="mm")
        caption(d, "Megabytes up. Thousands of tokens back.", AMBER,
                sub="The bigger the image and the answer, the more the weak path costs you.")
        add(img, 150)


def mp_add_path():
    for i in range(8):
        img, d = new_frame()
        on = i >= 3
        mp_base(d, header="One connection, two paths",
                a_color=AMBER, a_phase=(i / 8) % 1.0,
                b_color=GREEN if on else DIM, b_phase=(i / 8) % 1.0 if on else None)
        d.text((W // 2, 372), "conn.add_path(rmnet_data0)" if on else "handshake complete",
               font=font(17, True), fill=GREEN if on else MUTED, anchor="mm")
        caption(d, "MPQUIC adds the cellular path.", GREEN,
                sub="Wi-Fi stays the initial path; cellular joins the same QUIC connection after the handshake.")
        add(img, 190)


def mp_probe():
    for i in range(6):
        img, d = new_frame()
        mp_base(d, header="It measures instead of guessing",
                a_color=AMBER, b_color=GREEN)
        d.text((300, PATH_A_Y + 16), "RTT 180 ms", font=font(17, True), fill=AMBER, anchor="lm")
        d.text((300, PATH_B_Y + 16), "RTT  32 ms", font=font(17, True), fill=GREEN, anchor="lm")
        progress(d, 470, PATH_A_Y + 8, 200, 0.85, AMBER, h=12)
        progress(d, 470, PATH_B_Y + 8, 200, 0.18, GREEN, h=12)
        d.text((W // 2, 396), "--mpquic-scheduler minrtt   (also: redundant, roundrobin)",
               font=font(16, True), fill=BLUE, anchor="mm")
        illustrative(d)
        caption(d, "minRTT picks the faster path per packet.", GREEN,
                sub="Round-trip time is sampled continuously, so the choice tracks reality.")
        add(img, 400)


def mp_aggregate():
    for i in range(12):
        ph = (i / 12) % 1.0
        img, d = new_frame()
        mp_base(d, header="Both links carry the payload",
                a_color=AMBER, a_phase=ph, b_color=GREEN, b_phase=ph)
        progress(d, 250, 384, 460, 0.12 + i / 13, GREEN, "4 MB JPEG uploading")
        d.text((W // 2, 428), f"{i * 0.16:.1f} s", font=font(20, True), fill=GREEN, anchor="mm")
        illustrative(d)
        caption(d, "The spare capacity stops being spare.", GREEN,
                sub="Once the fast path's window is full, the second path takes the overflow.")
        add(img, 120)


def mp_failover():
    for i in range(9):
        dead = i >= 3
        img, d = new_frame()
        mp_base(d, header="Wi-Fi drops mid-transfer", wifi_bars=0, wifi_dead=dead,
                a_color=RED if dead else AMBER, a_dead=dead,
                a_phase=None if dead else (i / 9) % 1.0,
                b_color=GREEN, b_phase=(i / 9) % 1.0)
        if dead:
            d.text((W // 2, 372), "same Connection ID -- no reconnect, no new handshake",
                   font=font(17, True), fill=GREEN, anchor="mm")
        progress(d, 250, 402, 460, 0.55 + i * 0.05, GREEN)
        caption(d, "The link dies. The connection doesn't.", GREEN,
                sub="QUIC keys the connection to an ID, not to an IP address and port.")
        add(img, 260 if i != 8 else 900)


def mp_result():
    for i in range(4):
        img, d = new_frame()
        mp_base(d, header="Same request, same image", a_color=AMBER, b_color=GREEN)
        d.text((250, 372), "Wi-Fi only", font=font(17), fill=AMBER, anchor="lm")
        progress(d, 380, 364, 330, 1.0, AMBER)
        d.text((720, 372), "12.4 s", font=font(18, True), fill=AMBER, anchor="lm")
        d.text((250, 414), "MPQUIC", font=font(17), fill=GREEN, anchor="lm")
        progress(d, 380, 406, 330, 0.33, GREEN)
        d.text((720, 414), "4.1 s", font=font(18, True), fill=GREEN, anchor="lm")
        illustrative(d)
        caption(d, "The answer arrives while it still matters.", GREEN)
        add(img, 300 if i < 3 else 1400)


def mp_card():
    img, d = new_frame()
    mp_base(d, a_color=AMBER, b_color=GREEN)
    d.text((W // 2, 56), "Use every link you already have",
           font=font(30, True), fill=TEXT, anchor="mm")
    d.text((W // 2, 372), "measure -> schedule -> aggregate -> survive a path loss",
           font=font(18, True), fill=MUTED, anchor="mm")
    d.text((W // 2, 404), "Multipath QUIC on TQUIC  --  minrtt scheduler, bbr",
           font=font(16), fill=BLUE, anchor="mm")
    caption(d, "One connection. Two paths. No stalls.", GREEN)
    add(img, 2600)


# --------------------------------------------------------------------------
# output
# --------------------------------------------------------------------------

def build_mpquic() -> None:
    mp_both_up()
    mp_default()
    mp_cost()
    mp_add_path()
    mp_probe()
    mp_aggregate()
    mp_failover()
    mp_result()
    mp_card()


def build_offline() -> None:
    scene_problem()
    scene_failure()
    scene_arrival()
    scene_mesh()
    scene_capture()
    scene_inference()
    scene_answer()
    scene_card()


def write_gif(path: Path, size: tuple[int, int], colors: int) -> None:
    scaled = [f if size == (W, H) else f.resize(size, Image.LANCZOS) for f in frames]

    # One shared palette across every frame, so Pillow's inter-frame diffing can
    # actually kick in -- per-frame palettes would force full frames each time.
    sample = Image.new("RGB", (size[0], size[1] * min(12, len(scaled))))
    step = max(1, len(scaled) // 12)
    for i, f in enumerate(scaled[::step][:12]):
        sample.paste(f, (0, i * size[1]))
    master = sample.quantize(colors=colors, method=Image.MEDIANCUT)

    pframes = [f.quantize(palette=master, dither=Image.Dither.NONE) for f in scaled]
    path.parent.mkdir(parents=True, exist_ok=True)
    pframes[0].save(
        path, save_all=True, append_images=pframes[1:],
        duration=durations, loop=0, optimize=True, disposal=1,
    )
    kb = path.stat().st_size / 1024
    print(f"{path.name}: {size[0]}x{size[1]}, {len(pframes)} frames, "
          f"{sum(durations) / 1000:.1f}s, {colors} colors, {kb:.0f} KB")


PRESETS = {
    "slide": ((W, H), 64),
    "readme": ((800, 450), 48),
}


STORIES = {
    "offline": (build_offline, "offline-allergy"),
    "mpquic": (build_mpquic, "mpquic-paths"),
}

POSTER_TEXT = {
    "offline": (
        "1  --  No network at all",
        "Offline allergy check",
        "No Wi-Fi, no cellular, no cloud. Glasses, phone and a Snapdragon X Elite "
        "discover each other over mDNS with no router, run Qwen3-VL locally, and speak "
        "the allergens onto the lens. Nothing leaves the room.",
    ),
    "mpquic": (
        "2  --  A network the phone trusts too much",
        "Multipath QUIC",
        "Wi-Fi and 5G both up, but the phone commits to two-bar Wi-Fi and never "
        "re-evaluates. MPQUIC puts both links on one connection, schedules by measured "
        "RTT, spends metered data only on overflow, and survives losing a path.",
    ),
}

FOOTER = ("Two failure modes of one assumption: that the network is what the application "
          "thinks it is.")


def wrap(d, text, f, max_w):
    words, lines, cur = text.split(), [], ""
    for w_ in words:
        trial = f"{cur} {w_}".strip()
        if d.textlength(trial, font=f) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = w_
    if cur:
        lines.append(cur)
    return lines


def story_cards() -> dict[str, Image.Image]:
    """Final title card of each story -- they are already the summary diagrams."""
    out = {}
    for key, (fn, _) in STORIES.items():
        frames.clear()
        durations.clear()
        fn()
        out[key] = frames[-1].copy()
    return out


def build_poster(path: Path, wide: bool) -> None:
    cards = story_cards()
    keys = list(STORIES)
    pad, tw = 28, 96          # panel padding, title band height
    text_h = 132

    if wide:
        cw = W
        canvas = Image.new("RGB", (cw * 2 + pad * 3, tw + H + text_h + pad * 2), BG)
        slots = [(pad + i * (cw + pad), tw) for i in range(2)]
    else:
        canvas = Image.new("RGB", (W + pad * 2, tw + (H + text_h) * 2 + pad * 3), BG)
        slots = [(pad, tw + i * (H + text_h + pad)) for i in range(2)]

    d = ImageDraw.Draw(canvas)
    CW = canvas.width
    d.text((CW // 2, 40), "Two stories, one assumption",
           font=font(36, True), fill=TEXT, anchor="mm")
    d.text((CW // 2, 74), FOOTER, font=font(17), fill=MUTED, anchor="mm")

    for (x, y), key in zip(slots, keys):
        canvas.paste(cards[key], (x, y))
        d.rounded_rectangle([x - 3, y - 3, x + W + 3, y + H + 3], radius=6,
                            outline=EDGE, width=3)
        kicker, title, body = POSTER_TEXT[key]
        ty = y + H + 22
        d.text((x + 6, ty), kicker, font=font(16, True),
               fill=GREEN if key == "offline" else BLUE)
        d.text((x + 6, ty + 24), title, font=font(26, True), fill=TEXT)
        for i, line in enumerate(wrap(d, body, font(17), W - 12)):
            d.text((x + 6, ty + 60 + i * 24), line, font=font(17), fill=MUTED)

    path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(path)
    print(f"{path.name}: {canvas.width}x{canvas.height}, "
          f"{path.stat().st_size / 1024:.0f} KB")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--story", choices=[*STORIES, "both"], default="both")
    ap.add_argument("--preset", choices=[*PRESETS, "both"], default="both")
    ap.add_argument("--out-dir", default="docs/media")
    ap.add_argument("--dump-frame", type=int, help="write frame N as PNG and exit (for review)")
    ap.add_argument("--poster", action="store_true",
                    help="render the static two-story summary PNGs instead of GIFs")
    args = ap.parse_args()

    out = Path(args.out_dir)

    if args.poster:
        build_poster(out / "two-stories-poster.png", wide=False)
        build_poster(out / "two-stories-wide.png", wide=True)
        return

    stories = list(STORIES) if args.story == "both" else [args.story]
    presets = list(PRESETS) if args.preset == "both" else [args.preset]

    for story in stories:
        frames.clear()
        durations.clear()
        build_fn, stem = STORIES[story]
        build_fn()

        if args.dump_frame is not None:
            out.mkdir(parents=True, exist_ok=True)
            p = out / f"{stem}-frame-{args.dump_frame:03d}.png"
            frames[args.dump_frame].save(p)
            print(f"{p} ({len(frames)} frames total)")
            continue

        for name in presets:
            size, colors = PRESETS[name]
            write_gif(out / f"{stem}-{name}.gif", size, colors)


if __name__ == "__main__":
    main()
