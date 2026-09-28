# -*- coding: utf-8 -*-
"""
Рекламный ролик для Reels: 1080x1920, 30 кадров в секунду, около 37 секунд.
Текст на русском и английском на каждом кадре.

Кадры рисуются в двойном разрешении и уменьшаются — так у PIL получаются
гладкие края. Готовый кадр уходит прямо в ffmpeg, промежуточные PNG не нужны.

    python promo/make_reel.py            весь ролик
    python promo/make_reel.py --preview  девять опорных кадров в promo/preview
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "app", "src", "main", "res", "font")
ICON = os.path.join(ROOT, "app", "src", "main", "ic_launcher-playstore.png")
OUT = os.path.join(ROOT, "promo", "ne-prospi-reel.mp4")

W, H = 1080, 1920          # кадр
S = 2                      # во сколько раз рисуем крупнее
FPS = 30
DURATION = 37.0

# ---------- палитра ----------
NIGHT0 = (10, 12, 20)
NIGHT1 = (26, 32, 62)
BLUE = (47, 111, 228)
INDIGO = (61, 54, 158)
AMBER = (255, 176, 32)
RED = (239, 68, 68)
WHITE = (255, 255, 255)
MUTED = (176, 188, 214)
INK = (22, 24, 29)
PAPER = (238, 242, 248)

FONT_FILES = {
    "x": "onest_extrabold.ttf",
    "b": "onest_bold.ttf",
    "s": "onest_semibold.ttf",
    "n": "nunito_sans_semibold.ttf",
    "nb": "nunito_sans_bold.ttf",
}
_fonts = {}


def font(kind, size):
    key = (kind, int(size * S))
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(os.path.join(FONTS, FONT_FILES[kind]), int(size * S))
    return _fonts[key]


# ---------- плавность ----------
def clamp01(x):
    return 0.0 if x < 0 else (1.0 if x > 1 else x)


def seg(t, a, b):
    """Насколько продвинулся отрезок времени [a, b] к моменту t."""
    return clamp01((t - a) / (b - a)) if b > a else (1.0 if t >= b else 0.0)


def out_cubic(t):
    return 1 - (1 - t) ** 3


def out_expo(t):
    return 1.0 if t >= 1 else 1 - 2 ** (-9 * t)


def out_back(t, s=1.9):
    t -= 1
    return t * t * ((s + 1) * t + s) + 1


def in_out(t):
    return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c0, c1, t):
    return tuple(int(round(lerp(c0[i], c1[i], t))) for i in range(3))


def fade(t, hold, up=0.35, down=0.35):
    """Появиться, подержаться, исчезнуть — доля видимости 0..1."""
    return min(seg(t, 0, up), 1 - seg(t, hold - down, hold))


# ---------- фон ----------
_grad_cache = {}


def gradient(c0, c1, angle=55.0):
    key = (c0, c1, round(angle, 1))
    if key not in _grad_cache:
        ww, hh = W * S, H * S
        x = np.linspace(0, 1, ww, dtype=np.float32)[None, :]
        y = np.linspace(0, 1, hh, dtype=np.float32)[:, None]
        a = math.radians(angle)
        g = x * math.cos(a) + y * math.sin(a)
        g = (g - g.min()) / (g.max() - g.min())
        arr = np.zeros((hh, ww, 3), dtype=np.uint8)
        for i in range(3):
            arr[:, :, i] = (c0[i] + (c1[i] - c0[i]) * g).astype(np.uint8)
        _grad_cache[key] = Image.fromarray(arr, "RGB")
    return _grad_cache[key].copy()


_radial_cache = {}


def radial(size, color, strength=1.0, power=2.0):
    """Мягкое пятно света — дешёвая замена размытию."""
    key = (int(size), color, round(strength, 2), round(power, 2))
    if key not in _radial_cache:
        n = max(8, int(size))
        yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
        c = (n - 1) / 2.0
        d = np.sqrt((xx - c) ** 2 + (yy - c) ** 2) / c
        a = np.clip(1 - d, 0, 1) ** power * (255 * strength)
        rgba = np.zeros((n, n, 4), dtype=np.uint8)
        rgba[:, :, 0], rgba[:, :, 1], rgba[:, :, 2] = color
        rgba[:, :, 3] = a.astype(np.uint8)
        _radial_cache[key] = Image.fromarray(rgba, "RGBA")
    return _radial_cache[key]


def glow(layer, x, y, size, color, alpha=1.0, power=2.0):
    if alpha <= 0.01 or size < 2:
        return
    sp = radial(size * S, color, 1.0, power)
    if alpha < 1.0:
        sp = sp.copy()
        sp.putalpha(sp.getchannel("A").point(lambda v: int(v * alpha)))
    layer.alpha_composite(sp, (int(x * S - sp.width / 2), int(y * S - sp.height / 2)))


_shadow_cache = {}


def shadow_sprite(w, h, radius, blur, opacity):
    key = (int(w), int(h), int(radius), int(blur), round(opacity, 2))
    if key not in _shadow_cache:
        pad = blur * 3
        im = Image.new("L", (int(w + pad * 2), int(h + pad * 2)), 0)
        ImageDraw.Draw(im).rounded_rectangle(
            [pad, pad, pad + w, pad + h], radius=radius, fill=int(255 * opacity)
        )
        im = im.filter(ImageFilter.GaussianBlur(blur))
        out = Image.new("RGBA", im.size, (0, 0, 0, 0))
        out.putalpha(im)
        _shadow_cache[key] = out
    return _shadow_cache[key]


def drop_shadow(layer, box, radius, blur=26, opacity=0.45, dy=12):
    x0, y0, x1, y1 = [v * S for v in box]
    sp = shadow_sprite(x1 - x0, y1 - y0, radius * S, blur * S, opacity)
    pad = blur * 3 * S
    layer.alpha_composite(sp, (int(x0 - pad), int(y0 - pad + dy * S)))


# ---------- примитивы ----------
def new_layer():
    return Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))


def rgba(color, alpha):
    return (color[0], color[1], color[2], max(0, min(255, int(alpha * 255))))


def card(layer, box, radius, fill, alpha=1.0, outline=None, width=2):
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(
        [v * S for v in box],
        radius=radius * S,
        fill=rgba(fill, alpha) if fill else None,
        outline=rgba(outline, alpha) if outline else None,
        width=int(width * S),
    )


def circle(layer, x, y, r, fill=None, alpha=1.0, outline=None, width=3):
    ImageDraw.Draw(layer).ellipse(
        [(x - r) * S, (y - r) * S, (x + r) * S, (y + r) * S],
        fill=rgba(fill, alpha) if fill else None,
        outline=rgba(outline, alpha) if outline else None,
        width=int(width * S),
    )


def line(layer, pts, color, width, alpha=1.0, joint="curve"):
    if len(pts) < 2:
        return
    ImageDraw.Draw(layer).line(
        [(p[0] * S, p[1] * S) for p in pts], fill=rgba(color, alpha), width=int(width * S), joint=joint
    )


def text(layer, x, y, s, kind, size, color, alpha=1.0, anchor="mm"):
    if alpha <= 0.01 or not s:
        return
    ImageDraw.Draw(layer).text((x * S, y * S), s, font=font(kind, size), fill=rgba(color, alpha), anchor=anchor)


def text_w(s, kind, size):
    f = font(kind, size)
    return (f.getbbox(s)[2] - f.getbbox(s)[0]) / S


def pill(layer, cx, y, s, kind, size, fg, bg, alpha=1.0, padx=26, pady=14, radius=None):
    w = text_w(s, kind, size) + padx * 2
    h = size * 1.5 + pady
    r = radius if radius is not None else h / 2
    card(layer, (cx - w / 2, y - h / 2, cx + w / 2, y + h / 2), r, bg, alpha)
    text(layer, cx, y, s, kind, size, fg, alpha)
    return w


def bilingual(layer, y, ru, en, ru_size, en_size, alpha, gap=None, ru_kind="x", en_kind="n",
              ru_color=WHITE, en_color=MUTED, dy=0, cx=W / 2):
    gap = gap if gap is not None else ru_size * 0.95
    text(layer, cx, y + dy, ru, ru_kind, ru_size, ru_color, alpha)
    text(layer, cx, y + dy + gap, en, en_kind, en_size, en_color, alpha * 0.92)


# ---------- иконка приложения ----------
_icon_cache = {}


def app_icon(size):
    key = int(size * S)
    if key not in _icon_cache:
        im = Image.open(ICON).convert("RGBA").resize((key, key), Image.LANCZOS)
        mask = Image.new("L", (key, key), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, key - 1, key - 1], radius=int(key * 0.235), fill=255)
        im.putalpha(mask)
        _icon_cache[key] = im
    return _icon_cache[key]


def put_icon(layer, cx, cy, size, alpha=1.0):
    im = app_icon(size)
    if alpha < 1:
        im = im.copy()
        im.putalpha(im.getchannel("A").point(lambda v: int(v * alpha)))
    layer.alpha_composite(im, (int(cx * S - im.width / 2), int(cy * S - im.height / 2)))


# ---------- мелкие значки, нарисованные руками ----------
def icon_bell(layer, cx, cy, r, color, alpha=1.0, w=None):
    sub = Image.new("RGBA", (int(r * 3 * S), int(r * 3 * S)), (0, 0, 0, 0))
    icon_bell_px(sub, r * 1.5 * S, r * 1.5 * S, r * S, color)
    if alpha < 1:
        sub.putalpha(sub.getchannel("A").point(lambda v: int(v * alpha)))
    layer.alpha_composite(sub, (int(cx * S - sub.width / 2), int(cy * S - sub.height / 2)))


def icon_pin(layer, cx, cy, r, color, alpha=1.0):
    d = ImageDraw.Draw(layer)
    d.pieslice([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], 160, 380, fill=rgba(color, alpha))
    d.polygon([((cx - r * 0.72) * S, (cy + r * 0.32) * S),
               ((cx + r * 0.72) * S, (cy + r * 0.32) * S),
               (cx * S, (cy + r * 1.7) * S)], fill=rgba(color, alpha))
    circle(layer, cx, cy, r * 0.34, fill=WHITE, alpha=alpha)


def icon_globe(layer, cx, cy, r, color, alpha=1.0, w=3):
    circle(layer, cx, cy, r, outline=color, alpha=alpha, width=w)
    d = ImageDraw.Draw(layer)
    d.ellipse([(cx - r * 0.45) * S, (cy - r) * S, (cx + r * 0.45) * S, (cy + r) * S],
              outline=rgba(color, alpha), width=int(w * S))
    d.line([((cx - r) * S, cy * S), ((cx + r) * S, cy * S)], fill=rgba(color, alpha), width=int(w * S))


def icon_timer(layer, cx, cy, r, color, alpha=1.0, w=3):
    circle(layer, cx, cy + r * 0.12, r * 0.88, outline=color, alpha=alpha, width=w)
    d = ImageDraw.Draw(layer)
    d.line([(cx * S, (cy + r * 0.12) * S), (cx * S, (cy - r * 0.42) * S)], fill=rgba(color, alpha), width=int(w * S))
    d.line([(cx * S, (cy + r * 0.12) * S), ((cx + r * 0.42) * S, (cy + r * 0.12) * S)],
           fill=rgba(color, alpha), width=int(w * S))
    d.line([((cx - r * 0.42) * S, (cy - r * 0.95) * S), ((cx + r * 0.42) * S, (cy - r * 0.95) * S)],
           fill=rgba(color, alpha), width=int(w * S))


def icon_map(layer, cx, cy, r, color, alpha=1.0, w=3):
    d = ImageDraw.Draw(layer)
    pts = [(cx - r, cy - r * 0.7), (cx - r * 0.33, cy - r), (cx + r * 0.33, cy - r * 0.7),
           (cx + r, cy - r), (cx + r, cy + r * 0.7), (cx + r * 0.33, cy + r),
           (cx - r * 0.33, cy + r * 0.7), (cx - r, cy + r), (cx - r, cy - r * 0.7)]
    d.line([(p[0] * S, p[1] * S) for p in pts], fill=rgba(color, alpha), width=int(w * S), joint="curve")
    d.line([((cx - r * 0.33) * S, (cy - r) * S), ((cx - r * 0.33) * S, (cy + r * 0.7) * S)],
           fill=rgba(color, alpha), width=int(w * S))
    d.line([((cx + r * 0.33) * S, (cy - r * 0.7) * S), ((cx + r * 0.33) * S, (cy + r) * S)],
           fill=rgba(color, alpha), width=int(w * S))


def icon_flash(layer, cx, cy, r, color, alpha=1.0):
    ImageDraw.Draw(layer).polygon(
        [((cx + r * 0.28) * S, (cy - r) * S), ((cx - r * 0.55) * S, (cy + r * 0.12) * S),
         (cx * S, (cy + r * 0.12) * S), ((cx - r * 0.24) * S, (cy + r) * S),
         ((cx + r * 0.6) * S, (cy - r * 0.16) * S), ((cx + r * 0.05) * S, (cy - r * 0.16) * S)],
        fill=rgba(color, alpha))


def icon_no_ads(layer, cx, cy, r, color, alpha=1.0, w=3):
    card(layer, (cx - r, cy - r * 0.72, cx + r, cy + r * 0.72), r * 0.3, None, alpha, outline=color, width=w)
    ImageDraw.Draw(layer).line([((cx - r * 0.95) * S, (cy + r * 0.95) * S), ((cx + r * 0.95) * S, (cy - r * 0.95) * S)],
                               fill=rgba(color, alpha), width=int(w * 1.3 * S))


def icon_lang(layer, cx, cy, r, color, alpha=1.0, w=3):
    card(layer, (cx - r, cy - r * 0.85, cx + r * 0.35, cy + r * 0.3), r * 0.28, None, alpha, outline=color, width=w)
    card(layer, (cx - r * 0.35, cy - r * 0.3, cx + r, cy + r * 0.85), r * 0.28, color, alpha)


def icon_lock(layer, cx, cy, r, color, alpha=1.0, w=None):
    w = w or r * 0.22
    d = ImageDraw.Draw(layer)
    body_top = cy - r * 0.08
    d.arc([(cx - r * 0.46) * S, (body_top - r * 0.86) * S, (cx + r * 0.46) * S, (body_top + r * 0.12) * S],
          180, 360, fill=rgba(color, alpha), width=int(w * S))
    card(layer, (cx - r * 0.8, body_top, cx + r * 0.8, body_top + r * 0.95), r * 0.24, color, alpha)
    circle(layer, cx, body_top + r * 0.42, r * 0.14, fill=(20, 24, 40), alpha=alpha)


# ---------- макет телефона ----------
PH_W, PH_H = 496, 1004
PH_R = 54


def phone_frame(layer, cx, cy, alpha=1.0, scale=1.0):
    w, h = PH_W * scale, PH_H * scale
    box = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    drop_shadow(layer, box, PH_R * scale, blur=30, opacity=0.5 * alpha, dy=18)
    card(layer, box, PH_R * scale, (8, 10, 16), alpha)
    card(layer, box, PH_R * scale, None, alpha * 0.5, outline=(120, 140, 190), width=2)
    return (box[0] + 10 * scale, box[1] + 10 * scale, box[2] - 10 * scale, box[3] - 10 * scale)


def screen_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def paste_screen(layer, screen, box, alpha=1.0):
    x0, y0, x1, y1 = [v * S for v in box]
    size = (int(x1 - x0), int(y1 - y0))
    screen = screen.resize(size, Image.LANCZOS) if screen.size != size else screen
    screen = screen.copy()
    a = screen.getchannel("A") if screen.mode == "RGBA" else Image.new("L", size, 255)
    m = screen_mask(size, int((PH_R - 8) * S))
    a = Image.composite(a, Image.new("L", size, 0), m)
    if alpha < 1:
        a = a.point(lambda v: int(v * alpha))
    screen = screen.convert("RGBA")
    screen.putalpha(a)
    layer.alpha_composite(screen, (int(x0), int(y0)))


# ---------- экран карты внутри телефона ----------
ROUTE = [(0.17, 0.88), (0.17, 0.72), (0.34, 0.72), (0.34, 0.54), (0.55, 0.54),
         (0.55, 0.38), (0.72, 0.38), (0.72, 0.22)]


def partial_path(pts, progress):
    if progress <= 0:
        return []
    total = sum(math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
    want = total * progress
    out = [pts[0]]
    for i in range(len(pts) - 1):
        d = math.dist(pts[i], pts[i + 1])
        if want <= d:
            k = want / d if d else 0
            out.append((lerp(pts[i][0], pts[i + 1][0], k), lerp(pts[i][1], pts[i + 1][1], k)))
            return out
        out.append(pts[i + 1])
        want -= d
    return out


def map_screen(t, route_p, pin_p, sheet_p, highlight, dim=0.0, size=(PH_W - 20, PH_H - 20)):
    """Экран приложения: карта, маршрут, метка и нижняя панель."""
    w, h = int(size[0] * S), int(size[1] * S)
    im = Image.new("RGB", (w, h), PAPER)
    d = ImageDraw.Draw(im, "RGBA")
    # кварталы
    for gx in range(-1, 7):
        d.line([(gx * w / 6 + w * 0.06, 0), (gx * w / 6 - w * 0.02, h)], fill=(214, 222, 234, 255), width=int(9 * S))
    for gy in range(0, 9):
        d.line([(0, gy * h / 8), (w, gy * h / 8 + h * 0.012)], fill=(214, 222, 234, 255), width=int(7 * S))
    d.line([(w * 0.1, h), (w * 0.38, 0)], fill=(226, 232, 242, 255), width=int(26 * S))
    d.line([(0, h * 0.62), (w, h * 0.55)], fill=(226, 232, 242, 255), width=int(22 * S))
    # зелёная зона и вода — чтобы карта не выглядела пустой
    d.rounded_rectangle([w * 0.04, h * 0.12, w * 0.33, h * 0.32], radius=int(18 * S), fill=(214, 233, 216, 255))
    d.rounded_rectangle([w * 0.62, h * 0.63, w * 1.02, h * 0.82], radius=int(20 * S), fill=(206, 226, 240, 255))

    pts = [(p[0] * w, p[1] * h) for p in partial_path(ROUTE, route_p)]
    if len(pts) > 1:
        d.line(pts, fill=(*BLUE, 255), width=int(13 * S), joint="curve")
        d.ellipse([pts[0][0] - 11 * S, pts[0][1] - 11 * S, pts[0][0] + 11 * S, pts[0][1] + 11 * S],
                  fill=(*BLUE, 255), outline=(255, 255, 255, 255), width=int(4 * S))

    if pin_p > 0:
        px, py = ROUTE[-1][0] * w, ROUTE[-1][1] * h
        drop = (1 - out_back(clamp01(pin_p))) * h * 0.22
        pulse = (t * 1.1) % 1.0
        pr = (36 + pulse * 60) * S
        d.ellipse([px - pr, py - pr, px + pr, py + pr], outline=(*RED, int(150 * (1 - pulse))), width=int(5 * S))
        d.ellipse([px - 34 * S, py - 34 * S, px + 34 * S, py + 34 * S], fill=(239, 68, 68, 40))
        sub = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        icon_pin_scaled(sub, px, py - drop, 26 * S)
        im.paste(sub, (0, 0), sub)

    if sheet_p > 0:
        sh = h * 0.38
        top = h - sh * out_cubic(clamp01(sheet_p))
        d.rounded_rectangle([0, top, w, h + sh], radius=int(34 * S), fill=(255, 255, 255, 255))
        d.rounded_rectangle([w / 2 - 34 * S, top + 16 * S, w / 2 + 34 * S, top + 24 * S],
                            radius=int(6 * S), fill=(206, 214, 228, 255))
        f1 = ImageFont.truetype(os.path.join(FONTS, FONT_FILES["b"]), int(30 * S))
        f2 = ImageFont.truetype(os.path.join(FONTS, FONT_FILES["n"]), int(20 * S))
        d.text((w * 0.08, top + 52 * S), "Сквер Молодёжи", font=f1, fill=(*INK, 255))
        d.text((w * 0.08, top + 96 * S), "1,2 км от вас · по дороге 2,4 км", font=f2, fill=(110, 120, 140, 255))
        labels = ["300 м", "500 м", "1 км", "2 км"]
        bw = w * 0.205
        for i, lab in enumerate(labels):
            x0 = w * 0.06 + i * (bw + w * 0.015)
            on = highlight == i
            d.rounded_rectangle([x0, top + 140 * S, x0 + bw, top + 212 * S], radius=int(20 * S),
                                fill=(*BLUE, 255) if on else (238, 242, 248, 255))
            d.text((x0 + bw / 2, top + 176 * S), lab, font=f1, anchor="mm",
                   fill=(255, 255, 255, 255) if on else (*INK, 255))
        d.rounded_rectangle([w * 0.06, top + 238 * S, w * 0.94, top + 320 * S], radius=int(40 * S), fill=(*BLUE, 255))
        d.text((w / 2, top + 279 * S), "В путь  ·  Start", font=f1, anchor="mm", fill=(255, 255, 255, 255))

    if dim > 0:
        d.rectangle([0, 0, w, h], fill=(0, 0, 0, int(210 * dim)))
    return im.convert("RGBA")


def icon_pin_scaled(layer, px, py, r):
    """Метка прямо в координатах слоя (без пересчёта на S)."""
    d = ImageDraw.Draw(layer)
    d.pieslice([px - r, py - r, px + r, py + r], 160, 380, fill=(*RED, 255))
    d.polygon([(px - r * 0.72, py + r * 0.32), (px + r * 0.72, py + r * 0.32), (px, py + r * 1.75)], fill=(*RED, 255))
    d.ellipse([px - r * 0.34, py - r * 0.34, px + r * 0.34, py + r * 0.34], fill=(255, 255, 255, 255))


def alarm_screen(t, hold, size=(PH_W - 20, PH_H - 20)):
    """Экран звонящего будильника."""
    w, h = int(size[0] * S), int(size[1] * S)
    y = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    arr = np.zeros((h, w, 3), dtype=np.float32)
    c0, c1 = (255, 176, 32), (239, 110, 32)
    for i in range(3):
        arr[:, :, i] = c0[i] + (c1[i] - c0[i]) * y
    im = Image.fromarray(arr.astype(np.uint8), "RGB")
    d = ImageDraw.Draw(im, "RGBA")
    cx, cy = w / 2, h * 0.36
    for k in range(3):
        p = ((t * 0.9 + k / 3.0) % 1.0)
        r = (70 + p * 200) * S
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 255, 255, int(120 * (1 - p))), width=int(5 * S))
    ang = math.sin(t * 13) * 13 * max(0.0, 1 - hold)
    bell = Image.new("RGBA", (int(280 * S), int(280 * S)), (0, 0, 0, 0))
    icon_bell_px(bell, 140 * S, 145 * S, 96 * S, (255, 255, 255))
    bell = bell.rotate(ang, resample=Image.BICUBIC, center=(140 * S, 46 * S))
    im.paste(bell, (int(cx - 140 * S), int(cy - 145 * S)), bell)

    f1 = ImageFont.truetype(os.path.join(FONTS, FONT_FILES["x"]), int(46 * S))
    f2 = ImageFont.truetype(os.path.join(FONTS, FONT_FILES["n"]), int(26 * S))
    f3 = ImageFont.truetype(os.path.join(FONTS, FONT_FILES["b"]), int(28 * S))
    d.text((cx, h * 0.6), "Пора выходить!", font=f1, anchor="mm", fill=(255, 255, 255, 255))
    d.text((cx, h * 0.655), "Time to get off!", font=f2, anchor="mm", fill=(255, 255, 255, 220))
    d.text((cx, h * 0.715), "Сквер Молодёжи · 480 м", font=f3, anchor="mm", fill=(255, 255, 255, 235))

    bx0, bx1 = w * 0.1, w * 0.9
    by0, by1 = h * 0.8, h * 0.885
    d.rounded_rectangle([bx0, by0, bx1, by1], radius=int((by1 - by0) / 2), fill=(255, 255, 255, 60))
    if hold > 0:
        d.rounded_rectangle([bx0, by0, bx0 + (bx1 - bx0) * hold, by1], radius=int((by1 - by0) / 2),
                            fill=(255, 255, 255, 235))
    d.text((w / 2, (by0 + by1) / 2), "Держите, чтобы выключить", font=f3, anchor="mm",
           fill=(120, 70, 10, 255) if hold > 0.5 else (255, 255, 255, 245))
    return im.convert("RGBA")


def icon_bell_px(layer, cx, cy, r, color, width=None):
    """Колокольчик силуэтом: купол, юбка, основание, язычок и ушко."""
    d = ImageDraw.Draw(layer)
    col = (*color, 255)
    dome_top = cy - r * 0.78
    waist = cy + r * 0.34
    # купол
    d.pieslice([cx - r * 0.54, dome_top, cx + r * 0.54, dome_top + r * 1.08], 180, 360, fill=col)
    # корпус, слегка расходящийся книзу
    d.polygon([(cx - r * 0.54, dome_top + r * 0.54), (cx + r * 0.54, dome_top + r * 0.54),
               (cx + r * 0.7, waist), (cx - r * 0.7, waist)], fill=col)
    # основание
    d.rounded_rectangle([cx - r * 0.92, waist, cx + r * 0.92, waist + r * 0.19],
                        radius=r * 0.1, fill=col)
    # язычок и ушко
    d.ellipse([cx - r * 0.19, waist + r * 0.24, cx + r * 0.19, waist + r * 0.62], fill=col)
    d.ellipse([cx - r * 0.13, dome_top - r * 0.2, cx + r * 0.13, dome_top + r * 0.06], fill=col)


# ---------- фон-частицы ----------
rng = np.random.default_rng(7)
SPARKS = [(float(rng.uniform(0, W)), float(rng.uniform(0, H)), float(rng.uniform(2, 6)),
           float(rng.uniform(0.15, 0.5)), float(rng.uniform(8, 26))) for _ in range(46)]


def sparks(layer, t, alpha=1.0):
    for x, y, r, a, speed in SPARKS:
        yy = (y - t * speed) % (H + 120) - 60
        circle(layer, x, yy, r, fill=WHITE, alpha=a * alpha * 0.5)


# =====================================================================
#                              СЦЕНЫ
# =====================================================================
T1, T2, T3, T4, T5, T6 = 0.0, 5.0, 10.2, 20.6, 26.2, 32.0


def scene_hook(t, layer):
    """0–5 с: узнавание проблемы."""
    sparks(layer, t, 0.8)
    # дорога, уходящая вдаль: полотно и пунктир с перспективой
    road_a = fade(t, 5.0, 0.6, 0.5)
    vy = H * 0.2
    ImageDraw.Draw(layer).polygon(
        [((W / 2 - 22) * S, vy * S), ((W / 2 + 22) * S, vy * S),
         (W * 1.16 * S, H * S), (-W * 0.16 * S, H * S)],
        fill=rgba((120, 140, 210), 0.075 * road_a))
    for i in range(9):
        u = ((t * 0.42 + i / 9.0) % 1.0) ** 2.1
        y = vy + (H - vy) * u
        w = lerp(5, 30, u)
        h = lerp(16, 104, u)
        card(layer, (W / 2 - w / 2, y, W / 2 + w / 2, y + h), w / 2, (200, 215, 255), 0.30 * road_a * (0.25 + u))
    # уезжающая мимо метка
    p = seg(t, 2.6, 4.6)
    if 0 < p < 1:
        e = out_cubic(p)
        icon_pin(layer, W * 0.8 + e * 70, H * 0.8 - e * 540, lerp(52, 15, e), RED, alpha=(1 - p ** 2))

    a1 = fade(t, 2.7, 0.5, 0.35)
    dy1 = (1 - out_expo(seg(t, 0.15, 1.0))) * 54
    bilingual(layer, H * 0.4, "Уснул в автобусе?", "Fell asleep on the bus?", 78, 40, a1, dy=dy1, gap=86)

    a2 = min(seg(t, 2.5, 3.1), 1 - seg(t, 4.55, 4.95))
    dy2 = (1 - out_expo(seg(t, 2.5, 3.3))) * 54
    bilingual(layer, H * 0.4, "И проехал остановку.", "And missed your stop.", 78, 40, a2, dy=dy2, gap=86,
              ru_color=(255, 214, 214))


def scene_reveal(t, layer):
    """5–10,2 с: что это за приложение."""
    sparks(layer, t + 5, 0.5)
    p = seg(t, 0.0, 0.9)
    glow(layer, W / 2, H * 0.36, 900, (130, 175, 255), 0.26 * out_cubic(seg(t, 0.1, 0.8)))
    put_icon(layer, W / 2, H * 0.36, lerp(120, 268, out_back(p)), alpha=out_cubic(seg(t, 0, 0.35)))
    # звонок-волны от иконки
    for k in range(2):
        q = ((t * 0.8 + k * 0.5) % 1.0)
        if t > 0.7:
            circle(layer, W / 2, H * 0.36, 150 + q * 210, outline=WHITE, alpha=0.32 * (1 - q), width=3)

    a = out_cubic(seg(t, 0.75, 1.35))
    dy = (1 - out_expo(seg(t, 0.75, 1.6))) * 40
    text(layer, W / 2, H * 0.575 + dy, "Не проспи", "x", 118, WHITE, a)
    text(layer, W / 2, H * 0.635 + dy, "Don't Oversleep", "nb", 50, (215, 226, 250), a * 0.95)

    bar = out_cubic(seg(t, 1.5, 2.2)) * 220
    if bar > 2:
        card(layer, (W / 2 - bar / 2, H * 0.673, W / 2 + bar / 2, H * 0.673 + 9), 5, AMBER, a)

    a2 = out_cubic(seg(t, 1.9, 2.6))
    bilingual(layer, H * 0.735, "Будильник на вашу остановку", "A GPS alarm for your stop",
              46, 34, a2 * (1 - seg(t, 4.7, 5.15)), gap=58, ru_kind="s", ru_color=(232, 238, 252))


def scene_steps(t, layer):
    """10,2–20,6 с: как это работает, три шага."""
    local = t
    ph_in = out_back(seg(local, 0.0, 0.85))
    ph_out = seg(local, 10.0, 10.4)
    cy = H * 0.545 + (1 - ph_in) * 900
    screen_box = phone_frame(layer, W / 2, cy, alpha=1 - ph_out)

    step = 0 if local < 3.4 else (1 if local < 6.8 else 2)
    route_p = out_cubic(seg(local, 0.9, 2.6))
    pin_p = seg(local, 1.9, 2.6)
    sheet_p = seg(local, 3.6, 4.3)
    highlight = 1 if local > 5.0 else -1
    dim = out_cubic(seg(local, 7.5, 8.4)) * 0.92

    sc = map_screen(local, route_p, pin_p, sheet_p, highlight, dim)
    paste_screen(layer, sc, screen_box, alpha=1 - ph_out)

    if dim > 0.15:
        icon_lock(layer, W / 2, cy - 40, 54, WHITE, alpha=dim)
        text(layer, W / 2, cy + 70, "22:41", "x", 70, WHITE, dim)
        for k in range(3):
            q = (local * 0.45 + k * 0.34) % 1.0
            text(layer, W / 2 + 150 + q * 60, cy - 250 - q * 150, "z", "x", 34 + q * 26,
                 WHITE, dim * (1 - q) * 0.85)

    steps = [
        ("1", "Отметьте остановку", "Tap your stop on the map"),
        ("2", "Выберите, за сколько будить", "Choose when to wake you"),
        ("3", "Заблокируйте и спите", "Lock the phone and sleep"),
    ]
    starts = [0.35, 3.5, 6.9]
    for i, (num, ru, en) in enumerate(steps):
        a = min(seg(local, starts[i], starts[i] + 0.45),
                1 - (seg(local, starts[i + 1] - 0.3, starts[i + 1]) if i < 2 else seg(local, 10.0, 10.35)))
        if a <= 0.01:
            continue
        dy = (1 - out_expo(seg(local, starts[i], starts[i] + 0.7))) * 36
        y = H * 0.115 + dy
        tw = text_w(ru, "x", 52)
        total = 60 + 24 + tw
        bx = W / 2 - total / 2 + 30
        circle(layer, bx, y + 2, 30, fill=AMBER, alpha=a)
        text(layer, bx, y + 2, num, "x", 34, (60, 40, 0), a)
        text(layer, bx + 30 + 24, y, ru, "x", 52, WHITE, a, anchor="lm")
        text(layer, W / 2, y + 62, en, "n", 34, MUTED, a * 0.9)


def scene_alarm(t, layer):
    """20,6–26,2 с: момент, ради которого всё сделано."""
    cy = H * 0.545
    screen_box = phone_frame(layer, W / 2, cy, alpha=1 - seg(t, 5.2, 5.6))
    hold = clamp01(seg(t, 3.9, 5.0))
    paste_screen(layer, alarm_screen(t, hold), screen_box, alpha=1 - seg(t, 5.2, 5.6))

    glow(layer, W / 2, cy, 1500, AMBER, 0.30 + 0.10 * math.sin(t * 6))

    a = out_cubic(seg(t, 0.45, 1.0)) * (1 - seg(t, 5.0, 5.45))
    dy = (1 - out_expo(seg(t, 0.45, 1.2))) * 36
    bilingual(layer, H * 0.115 + dy, "Разбудит за 500 метров", "Rings 500 m before your stop",
              58, 36, a, gap=68)
    a2 = out_cubic(seg(t, 3.7, 4.3)) * (1 - seg(t, 5.0, 5.45))
    bilingual(layer, H * 0.905, "Выключается удержанием — спросонья не смахнёте",
              "Hold to dismiss — no accidental swipe", 34, 27, a2, gap=46, ru_kind="s", ru_color=(255, 236, 200))


FEATURES = [
    (icon_timer, "Страховочный таймер", "Backup timer"),
    (icon_globe, "Любая страна мира", "Any country"),
    (icon_map, "Офлайн-карта", "Offline maps"),
    (icon_flash, "Фонарик и вибрация", "Flash & vibration"),
    (icon_no_ads, "Без рекламы", "No ads at all"),
    (icon_lang, "Три языка", "Three languages"),
]


def scene_features(t, layer):
    """26,2–32 с: чем берёт."""
    sparks(layer, t + 20, 0.5)
    a0 = out_cubic(seg(t, 0.0, 0.5)) * (1 - seg(t, 5.3, 5.75))
    bilingual(layer, H * 0.14, "И ещё немного заботы", "A few more things", 56, 34, a0, gap=66)

    cw, ch, gap = 452, 232, 36
    x0 = (W - cw * 2 - gap) / 2
    y0 = H * 0.325
    for i, (icon, ru, en) in enumerate(FEATURES):
        col, row = i % 2, i // 2
        st = 0.35 + i * 0.13
        p = out_back(seg(t, st, st + 0.6))
        a = min(seg(t, st, st + 0.35), 1 - seg(t, 5.3, 5.75))
        if a <= 0.01:
            continue
        cx = x0 + col * (cw + gap)
        cy = y0 + row * (ch + gap) + (1 - p) * 70
        card(layer, (cx, cy, cx + cw, cy + ch), 38, WHITE, 0.10 * a)
        card(layer, (cx, cy, cx + cw, cy + ch), 38, None, 0.18 * a, outline=WHITE, width=2)
        icon(layer, cx + 76, cy + 74, 34, AMBER, a)
        text(layer, cx + 34, cy + 136, ru, "b", 34, WHITE, a, anchor="lm")
        text(layer, cx + 34, cy + 180, en, "n", 26, MUTED, a * 0.9, anchor="lm")


def scene_cta(t, layer):
    """32–37 с: что делать дальше."""
    sparks(layer, t + 26, 0.6)
    zoom = 1 + 0.03 * out_cubic(seg(t, 0, 4.8))
    a = out_cubic(seg(t, 0.0, 0.5))
    glow(layer, W / 2, H * 0.3, 820, (120, 170, 255), 0.30 * a)
    put_icon(layer, W / 2, H * 0.3, 210 * zoom, a)

    text(layer, W / 2, H * 0.455, "Не проспи", "x", 104, WHITE, a)
    text(layer, W / 2, H * 0.51, "Don't Oversleep", "nb", 44, (215, 226, 250), a * 0.95)

    a2 = out_cubic(seg(t, 0.55, 1.1))
    bilingual(layer, H * 0.585, "Бесплатно. Без рекламы. Открытый код.",
              "Free. No ads. Open source.", 42, 32, a2, gap=56, ru_kind="s", ru_color=(232, 238, 252))

    a3 = out_cubic(seg(t, 1.0, 1.6))
    pill(layer, W / 2, H * 0.685, "github.com/Emirkhan-Sharshenov/ne_prospi_app", "s", 30, INK, WHITE, a3, padx=40)

    a4 = out_cubic(seg(t, 1.5, 2.2))
    bilingual(layer, H * 0.79, "Не проспи свою остановку", "Never miss your stop again",
              52, 34, a4, gap=64, ru_color=AMBER)


def background(t):
    """Фон общий для всех сцен, с переходом на бренд-градиент."""
    if t < T2 - 0.45:
        return gradient(NIGHT0, NIGHT1, 62)
    k = out_cubic(seg(t, T2 - 0.45, T2 + 0.35))
    base = gradient(NIGHT0, NIGHT1, 62)
    brand = gradient(mix(NIGHT0, BLUE, 0.92), mix(NIGHT1, INDIGO, 0.95), 62)
    if k >= 0.999:
        return brand
    # круг бренд-градиента, растущий из центра
    r = int(math.hypot(W, H) * S * k)
    mask = Image.new("L", (W * S, H * S), 0)
    ImageDraw.Draw(mask).ellipse([W * S / 2 - r, H * S / 2 - r, W * S / 2 + r, H * S / 2 + r], fill=255)
    base.paste(brand, (0, 0), mask)
    return base


SCENES = [(T1, T2, scene_hook), (T2, T3, scene_reveal), (T3, T4, scene_steps),
          (T4, T5, scene_alarm), (T5, T6, scene_features), (T6, DURATION, scene_cta)]


def render(t):
    frame = background(t)
    layer = new_layer()
    for a, b, fn in SCENES:
        if a <= t < b:
            fn(t - a, layer)
            break
    frame = Image.alpha_composite(frame.convert("RGBA"), layer)
    f = (1 - seg(t - T4, 0.0, 0.5)) if T4 <= t < T4 + 0.5 else 0.0
    if f > 0.01:
        frame = Image.blend(frame, Image.new("RGBA", frame.size, (255, 255, 255, 255)), f * 0.8)
    # мягкое затемнение к краям — взгляд держится в центре
    return frame.convert("RGB").resize((W, H), Image.LANCZOS)


def main():
    preview = "--preview" in sys.argv
    if preview:
        out_dir = os.path.join(ROOT, "promo", "preview")
        os.makedirs(out_dir, exist_ok=True)
        for i, t in enumerate([1.2, 3.4, 6.5, 11.5, 15.0, 19.5, 22.5, 28.5, 34.0]):
            render(t).save(os.path.join(out_dir, "f%02d_%.1fs.png" % (i, t)))
            print("кадр", t, "с")
        return

    import imageio_ffmpeg
    total = int(DURATION * FPS)
    writer = imageio_ffmpeg.write_frames(
        OUT, (W, H), fps=FPS, quality=8, macro_block_size=1,
        output_params=["-pix_fmt", "yuv420p", "-profile:v", "high", "-movflags", "+faststart"],
    )
    writer.send(None)
    for i in range(total):
        writer.send(render(i / FPS).tobytes())
        if i % 30 == 0:
            print("%d/%d кадров" % (i, total), flush=True)
    writer.close()
    print("готово:", OUT, os.path.getsize(OUT) // 1024, "КБ")


if __name__ == "__main__":
    main()
