# -*- coding: utf-8 -*-
"""
Звук для ролика: музыка и эффекты, синтезированные с нуля.

Ничего чужого: все звуки считаются формулами, поэтому дорожку можно
публиковать где угодно без прав на музыку.

    python promo/make_audio.py   ->  promo/reel-audio.wav
"""
import math
import os
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "promo", "reel-audio.wav")

SR = 44100
DUR = 37.0
N = int(SR * DUR)
BEAT = 0.6                      # 100 ударов в минуту
BAR = BEAT * 4

# границы сцен ролика — музыка держится за них
T_REVEAL, T_STEPS, T_ALARM, T_FEATURES, T_CTA = 5.0, 10.2, 20.6, 26.2, 32.0

rng = np.random.default_rng(12)
left = np.zeros(N, dtype=np.float64)
right = np.zeros(N, dtype=np.float64)
wet = np.zeros(N, dtype=np.float64)     # то, что уйдёт в реверберацию


def midi(m):
    return 440.0 * 2 ** ((m - 69) / 12.0)


def place(buf, start, sig, gain=1.0):
    i = int(start * SR)
    if i >= N or gain == 0:
        return
    k = min(len(sig), N - i)
    if k > 0:
        buf[i:i + k] += sig[:k] * gain


def stereo(start, sig, gain=1.0, pan=0.0, send=0.0):
    """pan: -1 слева, +1 справа. send: сколько уходит в реверберацию."""
    l = gain * math.sqrt((1 - pan) / 2) * math.sqrt(2)
    r = gain * math.sqrt((1 + pan) / 2) * math.sqrt(2)
    place(left, start, sig, l)
    place(right, start, sig, r)
    if send > 0:
        place(wet, start, sig, gain * send)


def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def decay(dur, tau, attack=0.004):
    t = t_axis(dur)
    e = np.exp(-t / tau)
    a = np.clip(t / max(attack, 1e-5), 0, 1)
    return e * a


def lowpass(x, cutoff):
    """Однополюсный фильтр — мягко срезает верх."""
    a = math.exp(-2 * math.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc = (1 - a) * x[i] + a * acc
        y[i] = acc
    return y


def lowpass_fast(x, cutoff):
    """То же, но через частотную область — быстрее на длинных кусках."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    h = 1.0 / (1.0 + (f / max(cutoff, 1.0)) ** 2)
    return np.fft.irfft(np.fft.rfft(x) * h, n)


def highpass_fast(x, cutoff):
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    h = (f / max(cutoff, 1.0)) ** 2 / (1.0 + (f / max(cutoff, 1.0)) ** 2)
    return np.fft.irfft(np.fft.rfft(x) * h, n)


# ---------- инструменты ----------
def kick(gain=1.0):
    t = t_axis(0.42)
    f = 46 + 130 * np.exp(-t / 0.028)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.15)
    click = rng.normal(0, 1, len(t)) * np.exp(-t / 0.004) * 0.25
    return np.tanh((body + click) * 1.6) * 0.9 * gain


def sub(freq, dur, gain=1.0):
    t = t_axis(dur)
    e = np.clip(t / 0.01, 0, 1) * np.exp(-t / (dur * 0.55))
    return np.sin(2 * np.pi * freq * t) * e * gain


def hat(dur=0.06, gain=0.3, open_=False):
    t = t_axis(dur if not open_ else 0.22)
    n = rng.normal(0, 1, len(t))
    n = highpass_fast(n, 7000)
    return n * np.exp(-t / (0.012 if not open_ else 0.08)) * gain


def clap(gain=0.5):
    t = t_axis(0.3)
    n = highpass_fast(rng.normal(0, 1, len(t)), 1200)
    env = np.exp(-t / 0.09)
    for off in (0.008, 0.016, 0.026):
        i = int(off * SR)
        env[i:] += np.exp(-t[: len(t) - i] / 0.05) * 0.7
    return n * env * 0.25 * gain


def pluck(freq, dur, gain=0.5, detune=0.004):
    t = t_axis(dur)
    e = np.exp(-t / (dur * 0.38)) * np.clip(t / 0.005, 0, 1)
    s = (np.sin(2 * np.pi * freq * t)
         + 0.5 * np.sin(2 * np.pi * freq * (1 + detune) * t)
         + 0.25 * np.sin(2 * np.pi * freq * 2 * t))
    return s * e * gain * 0.4


def pad(freqs, dur, gain=0.3, cutoff=1400):
    t = t_axis(dur)
    s = np.zeros(len(t))
    for f in freqs:
        for d in (-0.006, 0.0, 0.007):
            ph = rng.uniform(0, 2 * np.pi)
            saw = 2 * ((f * (1 + d) * t + ph / (2 * np.pi)) % 1.0) - 1
            s += saw
    s /= len(freqs) * 3
    s = lowpass_fast(s, cutoff)
    a, r = 0.55, 0.9
    env = np.clip(t / a, 0, 1) * np.clip((dur - t) / r, 0, 1)
    return s * env * gain


def bell(freq=880.0, dur=2.2, gain=0.5):
    t = t_axis(dur)
    s = np.zeros(len(t))
    for ratio, amp, tau in ((1.0, 1.0, 1.4), (2.0, 0.55, 0.9), (2.76, 0.42, 0.6),
                            (5.4, 0.22, 0.35), (8.93, 0.12, 0.22)):
        s += amp * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-t / tau)
    return s * np.clip(t / 0.002, 0, 1) * gain * 0.32


def whoosh(dur=0.9, gain=0.5, up=True):
    t = t_axis(dur)
    n = rng.normal(0, 1, len(t))
    n = lowpass_fast(n, 4000)
    sweep = np.sin(2 * np.pi * np.cumsum(180 + (1400 if up else -900) * (t / dur)) / SR) * 0.15
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
    return (n * 0.5 + sweep) * env * gain


def riser(dur=1.4, gain=0.5):
    t = t_axis(dur)
    k = t / dur
    n = highpass_fast(rng.normal(0, 1, len(t)), 500)
    tone = np.sin(2 * np.pi * np.cumsum(300 + 900 * k ** 2) / SR)
    return (n * 0.5 + tone * 0.5) * (k ** 2.2) * gain


def impact(gain=1.0):
    t = t_axis(1.6)
    boom = np.sin(2 * np.pi * np.cumsum(70 * np.exp(-t / 0.35) + 32) / SR) * np.exp(-t / 0.5)
    n = lowpass_fast(rng.normal(0, 1, len(t)), 900) * np.exp(-t / 0.18)
    return np.tanh((boom * 1.2 + n * 0.5) * 1.3) * 0.75 * gain


def tick(freq=2200, gain=0.25):
    t = t_axis(0.07)
    return np.sin(2 * np.pi * freq * t) * np.exp(-t / 0.012) * gain


def pop(gain=0.4):
    t = t_axis(0.18)
    f = 900 * np.exp(-t / 0.03) + 220
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.05) * gain


def lock_click(gain=0.35):
    t = t_axis(0.12)
    n = highpass_fast(rng.normal(0, 1, len(t)), 2500) * np.exp(-t / 0.008)
    body = np.sin(2 * np.pi * 420 * t) * np.exp(-t / 0.03)
    return (n * 0.6 + body * 0.5) * gain


# ---------- гармония ----------
# ре минор: Dm - B♭ - F - C
CHORDS = [
    ([50, 53, 57], 38),   # Dm,  бас D
    ([46, 50, 53], 34),   # B♭,  бас B♭
    ([45, 48, 53], 41),   # F,   бас F
    ([48, 52, 55], 36),   # C,   бас C
]


def chord_at(bar_index):
    return CHORDS[bar_index % 4]


# =====================================================================
#                              АРАНЖИРОВКА
# =====================================================================

# --- 0–5 с: тревожное ожидание ---
stereo(0.0, pad([midi(38), midi(50), midi(53)], 5.4, gain=0.24, cutoff=700), send=0.35)
for i in range(4):
    stereo(0.35 + i * 1.2, sub(midi(38), 0.5, 0.5), gain=0.8)          # пульс, как сердце
    stereo(0.35 + i * 1.2 + 0.16, sub(midi(38), 0.35, 0.28), gain=0.7)
stereo(3.6, riser(1.4, 0.42), send=0.3)
stereo(4.62, whoosh(0.55, 0.45), pan=-0.2, send=0.35)

# --- 5–10,2 с: знакомство ---
stereo(5.0, impact(1.0), send=0.25)
stereo(5.0, bell(midi(74), 2.6, 0.5), pan=0.1, send=0.6)
stereo(5.0, pad([midi(50), midi(57), midi(62)], 5.2, gain=0.26, cutoff=1500), send=0.4)
for b in range(int((T_STEPS - T_REVEAL) / BEAT)):
    tt = T_REVEAL + b * BEAT
    if b % 2 == 0:
        stereo(tt, kick(0.75))
    stereo(tt + BEAT / 2, hat(gain=0.16), pan=0.25)
arp_notes = [74, 77, 81, 77, 74, 81, 84, 81]
for i in range(16):
    tt = T_REVEAL + 1.2 + i * (BEAT / 2)
    if tt > T_STEPS - 0.1:
        break
    stereo(tt, pluck(midi(arp_notes[i % 8]), 0.5, 0.3), pan=(-0.3 if i % 2 else 0.3), send=0.45)

stereo(9.25, riser(0.95, 0.4), send=0.3)          # подводка к разделу «как это работает»
stereo(10.1, whoosh(0.5, 0.4), pan=0.2, send=0.35)

# --- 10,2–20,6 с: три шага, ровный бит ---
steps_bars = int((T_ALARM - T_STEPS) / BAR) + 1
for bar in range(steps_bars):
    b0 = T_STEPS + bar * BAR
    notes, bass = chord_at(bar)
    if b0 < T_ALARM:
        stereo(b0, pad([midi(n) for n in notes], min(BAR, T_ALARM - b0), gain=0.2, cutoff=1800), send=0.45)
    for beat in range(4):
        tt = b0 + beat * BEAT
        if tt >= T_ALARM - 0.05:
            break
        stereo(tt, kick(0.85))
        stereo(tt, sub(midi(bass), 0.55, 0.5), gain=0.9)
        if beat % 2 == 1:
            stereo(tt, clap(0.42), pan=0.05, send=0.3)
        for eighth in (0.5, 1.0, 1.5):
            stereo(tt + eighth * BEAT / 2, hat(gain=0.14 if eighth != 1.0 else 0.2),
                   pan=0.3 if eighth == 0.5 else -0.25)
    # мелодия
    for i, n in enumerate((notes[2] + 12, notes[1] + 12, notes[0] + 12, notes[1] + 12)):
        tt = b0 + i * BEAT + BEAT / 2
        if tt < T_ALARM - 0.1:
            stereo(tt, pluck(midi(n), 0.55, 0.26), pan=(-0.25 if i % 2 else 0.25), send=0.5)

# звуки интерфейса под картинку
stereo(12.15, pop(0.45), send=0.25)              # метка упала на карту
stereo(13.85, tick(1800, 0.22), pan=-0.1)        # поднялась панель
stereo(15.25, tick(2400, 0.3), pan=0.1)          # выбрали 500 метров
stereo(17.75, lock_click(0.4))                   # экран заблокирован
stereo(19.2, riser(1.4, 0.5), send=0.3)          # подводка к будильнику

# --- 20,6–26,2 с: будильник ---
stereo(T_ALARM, impact(1.15), send=0.3)
stereo(T_ALARM, whoosh(0.7, 0.5, up=False), pan=0.15, send=0.4)
for i, off in enumerate((0.05, 0.42, 0.79, 1.16)):
    stereo(T_ALARM + off, bell(midi(81 if i % 2 == 0 else 84), 1.9, 0.55 - i * 0.05),
           pan=(-0.2 if i % 2 else 0.2), send=0.55)
alarm_bars = int((T_FEATURES - T_ALARM) / BAR) + 1
for bar in range(alarm_bars):
    b0 = T_ALARM + bar * BAR
    notes, bass = chord_at(bar)
    if b0 < T_FEATURES:
        stereo(b0, pad([midi(n) for n in notes], min(BAR, T_FEATURES - b0), gain=0.22, cutoff=2200), send=0.4)
    for beat in range(4):
        tt = b0 + beat * BEAT
        if tt >= T_FEATURES - 0.05:
            break
        stereo(tt, kick(0.95))
        stereo(tt, sub(midi(bass), 0.55, 0.55), gain=0.95)
        if beat % 2 == 1:
            stereo(tt, clap(0.5), send=0.3)
        stereo(tt + BEAT / 2, hat(gain=0.2), pan=-0.3)
        stereo(tt + BEAT * 0.75, hat(gain=0.12), pan=0.3)
stereo(24.0, bell(midi(86), 1.6, 0.34), pan=-0.15, send=0.6)

# --- 26,2–32 с: возможности ---
feat_bars = int((T_CTA - T_FEATURES) / BAR) + 1
for bar in range(feat_bars):
    b0 = T_FEATURES + bar * BAR
    notes, bass = chord_at(bar + 2)
    if b0 < T_CTA:
        stereo(b0, pad([midi(n) for n in notes], min(BAR, T_CTA - b0), gain=0.22, cutoff=2600), send=0.45)
    for beat in range(4):
        tt = b0 + beat * BEAT
        if tt >= T_CTA - 0.05:
            break
        stereo(tt, kick(0.8))
        stereo(tt, sub(midi(bass), 0.5, 0.45), gain=0.85)
        if beat % 2 == 1:
            stereo(tt, clap(0.38), send=0.3)
        stereo(tt + BEAT / 2, hat(gain=0.15), pan=0.28)
for i in range(6):                                # карточки влетают
    stereo(26.55 + i * 0.13, tick(1500 + i * 190, 0.16), pan=(-0.3 if i % 2 else 0.3), send=0.25)

# --- 32–37 с: финал ---
stereo(T_CTA, impact(1.0), send=0.3)
stereo(T_CTA, whoosh(0.8, 0.4), pan=-0.15, send=0.4)
stereo(T_CTA, pad([midi(50), midi(57), midi(62), midi(69)], 4.6, gain=0.3, cutoff=2400), send=0.55)
for i, n in enumerate((74, 81, 86)):
    stereo(T_CTA + 0.1 + i * 0.28, bell(midi(n), 2.6, 0.4), pan=(i - 1) * 0.25, send=0.6)
for beat in range(6):
    tt = T_CTA + beat * BEAT
    stereo(tt, kick(0.7 - beat * 0.08))
    stereo(tt, sub(midi(38), 0.5, 0.4), gain=0.7)
stereo(35.6, bell(midi(74), 2.0, 0.34), send=0.7)


# ---------- реверберация и сведение ----------
def reverb(x, seconds=1.1, decay_tau=0.32, mix=1.0):
    n_ir = int(seconds * SR)
    t = np.arange(n_ir) / SR
    ir = rng.normal(0, 1, n_ir) * np.exp(-t / decay_tau)
    ir[: int(0.012 * SR)] = 0                      # предзадержка
    ir = lowpass_fast(ir, 4200)
    ir /= np.sqrt(np.sum(ir ** 2))
    out = np.convolve(x, ir)[:N]
    return out * mix


rev = reverb(wet)
left += rev * 0.85
right += np.roll(rev, 180) * 0.85                  # лёгкий сдвиг — шире картинка

# мягкое ограничение и общая громкость
mixdown = np.stack([left, right])
mixdown = np.tanh(mixdown * 0.85)
peak = np.max(np.abs(mixdown))
mixdown *= 0.89 / peak

# аккуратный вход и выход
fade_in = np.clip(np.arange(N) / (0.25 * SR), 0, 1)
fade_out = np.clip((N - np.arange(N)) / (0.9 * SR), 0, 1)
mixdown *= fade_in * fade_out

data = (mixdown.T * 32767).astype(np.int16)
with wave.open(OUT, "w") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(data.tobytes())

rms = np.sqrt(np.mean(mixdown ** 2))
print("готово:", OUT)
print("длина: %.2f с, пик: %.2f, средняя громкость: %.3f (%.1f dBFS)"
      % (DUR, np.max(np.abs(mixdown)), rms, 20 * math.log10(rms)))
