"""Original dragon audio for All Under Heaven, synthesized from scratch.

House-of-the-Dragon-style sound DESIGN (deep chest rumbles, ragged roars
that fall in pitch, heavy wing punches, a breath that is more furnace than
flamethrower) - but every sample here is generated procedurally, no source
material involved, so the mod owns its audio outright.

Technique per family:
  vocals (growl/roar/death) - detuned saw stack gliding DOWN in pitch,
      a half-frequency subharmonic underneath (the chest), throat-flutter
      AM tremor, tanh waveshaping for the rasp, then FFT-domain formant
      peaks (fixed vocal-tract resonances) so all three voices read as the
      SAME animal; onset breath-noise spit on the roars.
  aerodynamics (flap/fire) - shaped noise: the flap is a band-swept whoosh
      with a low thump at the power stroke; the fire loop is brown-noise
      furnace roar + sparse crackle impulses, crossfaded at the seam so it
      loops clean.
  steps - low decaying thump + gravel scuff.

Deterministic (seeded per sound). Writes OGG/Vorbis straight into the mod:
  src/main/resources/assets/allunderheaven/sounds/entity/dragon/*.ogg

Run:  python build_sounds.py
"""

from __future__ import annotations

import os

import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "src", "main", "resources", "assets", "allunderheaven",
    "sounds", "entity", "dragon"))


def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def glide(dur, f0, f1, curve=1.6):
    """Frequency contour f0 -> f1, eased (curve > 1 = spends longer high)."""
    u = np.linspace(0.0, 1.0, int(dur * SR)) ** curve
    return f0 + (f1 - f0) * u


def osc_saw(freq):
    """Band-limited-enough saw for sub-1kHz fundamentals: phase-accumulated."""
    phase = np.cumsum(freq) / SR
    return 2.0 * (phase % 1.0) - 1.0


def formants(x, peaks, width=0.35):
    """FFT-domain resonances: gaussian gain bumps at the given (hz, gain)
    peaks over a gentle 1/f tilt - the fixed vocal tract."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / SR)
    gain = 0.16 + 1.0 / (1.0 + f / 260.0)  # dark 1/f body
    for hz, g in peaks:
        gain += g * np.exp(-((np.log(np.maximum(f, 1.0) / hz)) ** 2)
                           / (2 * width ** 2))
    return np.fft.irfft(spec * gain, len(x))


def env_ad(n, attack, release, hold=1.0):
    """Attack / hold / release amplitude envelope (times in seconds)."""
    a = int(attack * SR)
    r = int(release * SR)
    e = np.full(n, hold, dtype=np.float64)
    e[:a] = np.linspace(0.0, hold, a)
    e[n - r:] = np.linspace(hold, 0.0, r)
    return e


def norm(x, peak=0.86):
    x = x - np.mean(x)
    return (x / np.max(np.abs(x)) * peak).astype(np.float32)


def voice(dur, f0, f1, drive, trem_hz, trem_depth, peaks, seed,
          breath=0.0, curve=1.6, sub_gain=0.55):
    """The shared dragon voice box."""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    freq = glide(dur, f0, f1, curve)
    # slow personal wobble so no two calls are identical machines
    freq *= 1.0 + 0.012 * np.sin(2 * np.pi * 0.9 * t_axis(dur) + rng.uniform(0, 6))
    stack = (osc_saw(freq) + 0.6 * osc_saw(freq * 1.027)
             + 0.6 * osc_saw(freq * 0.976))
    stack += sub_gain * osc_saw(freq * 0.5)          # the chest underneath
    trem = 1.0 - trem_depth * 0.5 * (1 + np.sin(2 * np.pi * trem_hz * t_axis(dur)))
    x = np.tanh(drive * stack * trem)                # rasp
    if breath > 0.0:
        noise = rng.standard_normal(n)
        spit = noise * env_ad(n, 0.005, dur * 0.7, breath)
        x += spit * np.abs(x)                        # breath rides the voice
    return formants(x, peaks)


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.ogg")
    sf.write(path, norm(x), SR, format="OGG", subtype="VORBIS")
    print(f"{name}.ogg  {len(x) / SR:.2f}s")


def growls():
    """Ambient territorial rumble, two takes."""
    peaks = ((150, 2.6), (390, 1.7), (830, 0.8))
    for i, (f0, f1, dur) in enumerate(((58, 41, 2.9), (52, 38, 3.3)), 1):
        x = voice(dur, f0, f1, drive=3.2, trem_hz=11.0, trem_depth=0.55,
                  peaks=peaks, seed=100 + i, curve=1.3)
        x *= env_ad(len(x), 0.35, 1.1)
        write(f"growl{i}", x)


def roar():
    """The aggro/hurt bellow: high slam, ragged fall."""
    peaks = ((280, 2.4), (680, 1.9), (1350, 0.9))
    x = voice(1.9, 132, 68, drive=5.0, trem_hz=14.0, trem_depth=0.35,
              peaks=peaks, seed=201, breath=0.8, curve=1.9)
    x *= env_ad(len(x), 0.03, 0.7)
    write("roar", x)


def death():
    """The last bellow: longer, sinking, collapsing into breath."""
    peaks = ((240, 2.2), (600, 1.6), (1200, 0.7))
    dur = 3.6
    x = voice(dur, 112, 42, drive=4.2, trem_hz=9.0, trem_depth=0.6,
              peaks=peaks, seed=301, breath=0.5, curve=1.2)
    n = len(x)
    # the voice gutters: tremor deepens into silence, one last chest thump
    x *= env_ad(n, 0.05, 1.6)
    tt = t_axis(dur)
    thump_at = int(3.0 * SR)
    thump = np.zeros(n)
    k = np.arange(n - thump_at)
    thump[thump_at:] = np.sin(2 * np.pi * 44 * k / SR) * np.exp(-k / (0.28 * SR))
    write("death", x + 1.6 * thump)


def flap():
    """One wing punch: band-swept whoosh peaking into a low thump."""
    rng = np.random.default_rng(401)
    dur = 0.8
    n = int(dur * SR)
    noise = rng.standard_normal(n)
    # time-varying one-pole lowpass: cutoff follows the stroke (slow-fast-slow)
    u = np.linspace(0, 1, n)
    stroke = np.exp(-((u - 0.42) ** 2) / (2 * 0.13 ** 2))   # power at 42%
    cutoff = 120 + 900 * stroke
    alpha = np.clip(2 * np.pi * cutoff / SR, 0.0, 0.6)
    y = np.empty(n)
    acc = 0.0
    for i in range(n):                                       # tiny, fine
        acc += alpha[i] * (noise[i] - acc)
        y[i] = acc
    y *= (0.18 + stroke) * env_ad(n, 0.04, 0.25)
    thump = np.sin(2 * np.pi * 56 * u * dur) * np.exp(-((u - 0.45) ** 2)
                                                      / (2 * 0.05 ** 2))
    write("flap", y * 3.0 + 0.9 * thump)


def fire_loop():
    """The furnace: brown-noise roar + crackle, seam crossfaded to loop."""
    rng = np.random.default_rng(501)
    dur = 2.6
    n = int(dur * SR)
    brown = np.cumsum(rng.standard_normal(n))
    brown /= np.max(np.abs(brown))
    roar = formants(brown, ((90, 3.0), (240, 2.2), (620, 0.9)), width=0.5)
    roar *= 1.0 + 0.18 * np.sin(2 * np.pi * 6.5 * t_axis(dur))  # surge
    crackle = np.zeros(n)
    for pos in rng.integers(0, n - 2000, 90):
        k = np.arange(1600)
        ring = np.sin(2 * np.pi * rng.uniform(900, 2600) * k / SR)
        crackle[pos:pos + 1600] += ring * np.exp(-k / 140.0) * rng.uniform(0.2, 1.0)
    x = roar + 0.16 * crackle
    fade = int(0.22 * SR)                     # seam: end folds into start
    ramp = np.linspace(0.0, 1.0, fade)
    x[:fade] = x[:fade] * ramp + x[n - fade:] * (1 - ramp)
    write("fire", x[: n - fade])


def step():
    """Tonnes of dragon putting a wrist down: thump + gravel scuff."""
    rng = np.random.default_rng(601)
    dur = 0.5
    n = int(dur * SR)
    u = t_axis(dur)
    thump = np.sin(2 * np.pi * glide(dur, 64, 40, 1.0) * u) * np.exp(-u * 11)
    scuff = rng.standard_normal(n) * np.exp(-u * 22)
    scuff = formants(scuff, ((300, 1.4), (900, 0.8)), width=0.6) * 0.25
    write("step", thump * 1.4 + scuff)


if __name__ == "__main__":
    growls()
    roar()
    death()
    flap()
    fire_loop()
    step()
    print("wrote", OUT)
