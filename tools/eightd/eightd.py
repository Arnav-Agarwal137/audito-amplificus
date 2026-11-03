"""8D copy of a song FILE (his tap 29 Sep "Make 8D copies"; live 8D clicks, ERRORS E-027).

The song's left and right channels become two virtual sources 60 degrees apart that circle the head once every
PERIOD_S seconds, rendered through measured head-related impulse responses (HRIRs: how each ear hears a sound from
each direction) - MIT KEMAR compact set, 5 degree steps, horizontal plane, in D:/dev/hrtf (licence: free with
citation, licenses/MIT-KEMAR.txt). The direction moves EVERY SAMPLE: each ear's output is the linear blend of the two
nearest measured directions, so there are no steps to click. Diffuse-field EQ removes the dummy head's own tone
(the part common to every direction), a light short room helps the sound sit outside the head, and the result is
matched to the original's loudness (then scaled down only if it would clip).
Writes "<song> - 8D.wav" beside the song. LAST line is "OK <file>" or "FAILED <reason>" - the app reads only that.
Usage: D:/dev/demucs-venv/Scripts/python eightd.py <song file>      |   eightd.py --selftest
"""
import os, sys, traceback
import numpy as np
from scipy.io import wavfile
from scipy.signal import fftconvolve

HRTF_DIR = os.path.join(os.environ.get("AUDITO_TOOLS", "D:/dev"), "hrtf", "elev0")
RATE = 44100            # the KEMAR set's own rate
STEP = 5                # degrees between measured directions
PERIOD_S = 12.0         # one full circle; the 8D habit is 10-20 s (research/data-models-standards.md section 6)
SPREAD = 30.0           # each channel sits this far either side of the moving centre
ROOM_DB = -18.0         # the room's level against the dry sound
CHUNK = 4096
EQ_LO, EQ_HI = 150.0, 18000.0   # 14 kHz left the top octave 12.5 dB down on a real song (29 Sep)
PAN_DEPTH = 0.8         # extra left-right pan on the 3D path: 0 = the head alone, 0.8 = about +-9.5 dB more at the side
BASS_HZ = 150.0         # below this the ears cannot tell direction: bass skips the 3D stage and stays full, in stereo
XOVER_TAPS = 1023       # linear-phase crossover (odd: a whole-sample delay of 511)


def load_hrirs():
    """(360/STEP, taps, 2) array, index k = azimuth k*STEP clockwise from the front (90 = right), channel 0 = left ear.
    The compact set holds 0..180 only (one ear measured, mirrored): the left half is the right half with ears swapped."""
    out = []
    for k in range(360 // STEP):
        az = k * STEP
        a = az if az <= 180 else 360 - az
        r, h = wavfile.read(os.path.join(HRTF_DIR, f"H0e{a:03d}a.wav"))
        if r != RATE:
            raise ValueError(f"HRIR rate {r}, expected {RATE}")
        h = h.astype(np.float64)
        out.append(h if az <= 180 else h[:, ::-1])
    return np.stack(out)


def diffuse_eq(hrirs, taps=256, limit_db=12.0):
    """Linear-phase FIR that undoes the power spectrum every direction shares (the dummy head's colour), within +-limit.
    Only EQ_LO..EQ_HI is corrected: outside it the KEMAR set is weak because of the measurement (-11.6 dB at 60 Hz,
    -14 dB at 20 kHz, measured 29 Sep), not because of the head, so the gain is held at the band edges there."""
    n = 1024
    f = np.fft.rfftfreq(n, 1 / RATE)
    power = (np.abs(np.fft.rfft(hrirs, n, axis=1)) ** 2).mean(axis=(0, 2))
    k = np.hanning(9); k /= k.sum()
    power = np.convolve(power, k, mode="same")                     # smooth: no narrow notches to chase
    g = 1 / np.sqrt(np.maximum(power, 1e-12))
    lo, hi = np.searchsorted(f, EQ_LO), np.searchsorted(f, EQ_HI)
    g[:lo], g[hi:] = g[lo], g[hi - 1]
    mid = (f >= 200) & (f <= 10000)
    g /= np.exp(np.log(g[mid]).mean())                             # 0 dB on average where music lives
    g = np.clip(g, 10 ** (-limit_db / 20), 10 ** (limit_db / 20))
    fir = np.fft.irfft(g, n)
    fir = np.roll(fir, taps // 2)[:taps] * np.hanning(taps)
    return fir


def high_gain(hrirs, eq):
    """Gain for the 3D path so a centred (L = R) sound comes out, averaged over a whole turn, at the level the bass
    bypass gives it: the raw HRIRs are not at unity (the bass came out 25 dB under the rest before this, 29 Sep)."""
    n = 1024
    f = np.fft.rfftfreq(n, 1 / RATE)
    band = (f >= 200) & (f <= 1000)
    H = np.fft.rfft(hrirs, n, axis=1)                              # (dirs, bins, ears)
    m = len(hrirs)
    s = int(SPREAD / STEP)
    pair = H[(np.arange(m) - s) % m] + H[(np.arange(m) + s) % m]   # both channels' sources, at every centre
    e = np.abs(np.fft.rfft(eq, n)) ** 2
    return 1 / np.sqrt(((np.abs(pair) ** 2)[:, band, :] * e[band, None]).mean())


def azimuth_path(n, start_deg, period_s=PERIOD_S):
    return (start_deg + 360.0 * np.arange(n) / (period_s * RATE)) % 360.0 if period_s else np.full(n, start_deg % 360.0)


def place(src, az, hrirs):
    """Binaural render of mono src whose direction is az[n] (degrees) at every sample. Returns (len+taps-1, 2)."""
    taps = hrirs.shape[1]
    m = len(hrirs)
    n = len(src) + taps - 1
    x = np.concatenate([np.zeros(taps - 1), src, np.zeros(taps - 1)])
    az = np.concatenate([az, np.full(taps - 1, az[-1])])
    pos = az / STEP
    i0 = np.floor(pos).astype(int) % m
    i1 = (i0 + 1) % m
    f = pos - np.floor(pos)
    out = np.zeros((n, 2))
    for a in range(0, n, CHUNK):
        b = min(n, a + CHUNK)
        seg = x[a:b + taps - 1]                                    # valid convolution gives exactly b-a samples
        for k in np.unique(np.concatenate([i0[a:b], i1[a:b]])):
            w = np.where(i0[a:b] == k, 1 - f[a:b], 0.0) + np.where(i1[a:b] == k, f[a:b], 0.0)
            if not w.any():
                continue
            for ear in (0, 1):
                out[a:b, ear] += w * fftconvolve(seg, hrirs[k, :, ear], mode="valid")
    return out


def room_ir(seconds=0.35, predelay_ms=15, seed=7):
    """A small room: two independent (decorrelated) noise tails decaying 60 dB over `seconds`, unit energy per ear."""
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    env = 10 ** (-3 * np.arange(n) / n)                            # -60 dB at the end
    ir = rng.standard_normal((n, 2)) * env[:, None]
    ir /= np.sqrt((ir ** 2).sum(0))
    return np.concatenate([np.zeros((int(predelay_ms * RATE / 1000), 2)), ir])


def render(stereo, period_s=PERIOD_S, start_deg=0.0):
    """stereo (n, 2) at RATE -> (8D stereo (n, 2), dB taken off to avoid clipping)."""
    from scipy.signal import firwin
    hrirs = load_hrirs()
    eq = diffuse_eq(hrirs)
    gain = high_gain(hrirs, eq)
    lp = firwin(XOVER_TAPS, BASS_HZ, fs=RATE)
    xd = XOVER_TAPS // 2
    lag = int(np.argmax(np.abs(hrirs[0]).max(1)))                  # the HRIRs' own onset: the bass waits for it
    n = len(stereo)
    out = np.zeros((n + hrirs.shape[1] - 1 + len(eq) - 1 + XOVER_TAPS, 2))
    lows = []
    for ch, side in ((0, -SPREAD), (1, SPREAD)):
        low = fftconvolve(stereo[:, ch], lp)[xd: xd + n]           # linear phase, lined up with the input
        high = stereo[:, ch] - low                                 # exact complement: low + high = the input
        src = fftconvolve(high, eq) * gain                         # linear phase: delayed by len(eq)//2
        out[: len(src) + hrirs.shape[1] - 1] += place(src, azimuth_path(len(src), start_deg + side, period_s), hrirs)
        lows.append(low)
    # the 8D habit on top of the real head: a smooth equal-power pan with the centre (a real head only shadows the
    # middle frequencies ~6 dB, measured on Husn 29 Sep; YouTube-style 8D is near one ear). Every sample, so no steps.
    th = np.radians(azimuth_path(len(out), start_deg, period_s))
    out[:, 0] *= np.sqrt(1 - PAN_DEPTH * np.sin(th))
    out[:, 1] *= np.sqrt(1 + PAN_DEPTH * np.sin(th))
    for ch, low in enumerate(lows):
        out[len(eq) // 2 + lag: len(eq) // 2 + lag + n, ch] += low # bass straight to its own ear, same channel
    out = out[len(eq) // 2: len(eq) // 2 + n]                      # line back up with the original
    ir = room_ir()
    mono = stereo.mean(1)
    for ear in (0, 1):
        wet = fftconvolve(mono, ir[:, ear])[:n]
        out[:, ear] += wet * np.sqrt((out[:, ear] ** 2).mean() / max((wet ** 2).mean(), 1e-20)) * 10 ** (ROOM_DB / 20)
    rms_in = np.sqrt((stereo ** 2).mean())
    out *= rms_in / max(np.sqrt((out ** 2).mean()), 1e-20)
    peak = np.abs(out).max(initial=0)
    cut = 0.0
    if peak > 0.98:
        cut = 20 * np.log10(peak / 0.98)
        out *= 0.98 / peak
    return out, cut


def main(path):
    import torch
    from demucs.audio import AudioFile, save_audio
    if not os.path.isfile(path):
        return "FAILED no such file"
    if not os.path.isdir(HRTF_DIR):
        return "FAILED the 3D sound data is not on this laptop"
    wav = AudioFile(path).read(streams=0, samplerate=RATE, channels=2).numpy().T.astype(np.float64)
    if wav.std() < 1e-6:
        return "FAILED the file is silent"
    print(f"rendering {len(wav) / RATE:.0f} s", flush=True)
    out, cut = render(wav)
    print(f"loudness matched to the original; {cut:.1f} dB taken off to stay under clipping", flush=True)
    name = os.path.splitext(os.path.basename(path))[0]
    dst = os.path.join(os.path.dirname(path), f"{name} - 8D.wav")
    save_audio(torch.from_numpy(out.T.astype(np.float32)), dst, RATE)
    return f"OK {dst}"


def selftest():
    """Checks on real renders; each prints PASS/FAIL with its number. Exit 1 on any FAIL."""
    fails = []

    def check(name, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'} {name}: {detail}", flush=True)
        if not ok:
            fails.append(name)

    rng = np.random.default_rng(1)
    hrirs = load_hrirs()
    noise = rng.standard_normal(RATE)
    # 1. direction: a still source at 90 must be louder in the right ear, at 270 in the left, at 0 equal
    for az, want in ((90, "right"), (270, "left"), (0, "equal")):
        y = place(noise, azimuth_path(len(noise), az, 0), hrirs)
        d = 10 * np.log10((y[:, 1] ** 2).sum() / (y[:, 0] ** 2).sum())   # right over left
        ok = d > 6 if want == "right" else d < -6 if want == "left" else abs(d) < 0.5
        check(f"source at {az} deg is {want}", ok, f"right-left {d:+.1f} dB")
    # 2. smooth motion: a rotating 1 kHz tone must have no 5 ms level jump bigger than 3 dB (live APO gave 29-45 dB)
    t = np.arange(int(6 * RATE)) / RATE
    tone = 0.1 * np.sin(2 * np.pi * 1000 * t)
    y = place(tone, azimuth_path(len(tone), 0, 3.0), hrirs)[: len(tone)]
    hop = RATE // 200
    m = len(y) // hop
    env = np.sqrt((y[: m * hop].reshape(m, hop, 2) ** 2).mean(1))[20:-20]
    steps = np.abs(np.diff(20 * np.log10(np.maximum(env, 1e-9)), axis=0)).max()
    check("rotation is smooth", steps < 3.0, f"biggest 5 ms step {steps:.2f} dB")
    swing = 20 * np.log10(env[:, 1].max() / env[:, 1].min())
    check("rotation actually moves", swing > 10, f"right ear swings {swing:.1f} dB over a turn")
    # 3. the whole render: same length, loudness matched, no clipping, and it is not just the input
    st = np.stack([rng.standard_normal(3 * RATE), rng.standard_normal(3 * RATE)], 1) * 0.1
    out, cut = render(st)
    check("length kept", out.shape == st.shape, f"{out.shape} vs {st.shape}")
    ld = 20 * np.log10(np.sqrt((out ** 2).mean()) / np.sqrt((st ** 2).mean()))
    check("loudness matched", abs(ld) < 0.1 or cut > 0, f"{ld:+.2f} dB (cut {cut:.1f})")
    check("no clipping", np.abs(out).max() <= 0.98 + 1e-9, f"peak {np.abs(out).max():.3f}")
    c = np.corrcoef(out[:, 0], st[:, 0])[0, 1]
    check("output is transformed", abs(c) < 0.9, f"correlation with input left {c:.2f}")
    # 4. the diffuse EQ must stay within its limit and be ~0 dB on average
    g = 20 * np.log10(np.abs(np.fft.rfft(diffuse_eq(hrirs), 1024)) + 1e-12)
    f = np.fft.rfftfreq(1024, 1 / RATE)
    band = g[(f >= 200) & (f <= 10000)]
    check("EQ within limits", g.max() < 12.5 and g.min() > -12.5 and abs(np.median(band)) < 1.5,
          f"range {g.min():.1f}..{g.max():.1f} dB, median in 200 Hz-10 kHz {np.median(band):.1f}")
    # 5. bass is kept: a 60 Hz tone must come out at the loudness of the input, with the 3D stage adding nothing to it
    b = np.sin(2 * np.pi * 60 * np.arange(2 * RATE) / RATE)[:, None] * np.array([[0.2, 0.2]])
    ob, _ = render(b)
    mid = slice(RATE // 2, 3 * RATE // 2)
    ratio = 20 * np.log10(np.sqrt((ob[mid] ** 2).mean()) / np.sqrt((b[mid] ** 2).mean()))
    bass_share = 20 * np.log10(np.abs(np.fft.rfft(ob[mid, 0]))[60] / np.abs(np.fft.rfft(ob[mid, 0])).max())
    check("bass kept", abs(ratio) < 1.0 and bass_share > -0.5, f"level {ratio:+.2f} dB, 60 Hz is the loudest line")
    # 6. no step where bass bypass meets the 3D path: centred noise, a whole turn, 80-120 Hz vs 200-300 Hz gain
    mono = rng.standard_normal(int(PERIOD_S * RATE)) * 0.1
    om, _ = render(np.stack([mono, mono], 1))
    fi, fo = np.abs(np.fft.rfft(mono)) ** 2, (np.abs(np.fft.rfft(om, axis=0)) ** 2).mean(1)
    fq = np.fft.rfftfreq(len(mono), 1 / RATE)
    gdb = lambda a, b: 10 * np.log10(fo[(fq >= a) & (fq <= b)].sum() / fi[(fq >= a) & (fq <= b)].sum())
    stepdb = gdb(200, 300) - gdb(80, 120)
    check("no step at the crossover", abs(stepdb) < 3, f"200-300 Hz sits {stepdb:+.1f} dB against 80-120 Hz")
    print(f"selftest: {len(fails)} failed" + (f" ({', '.join(fails)})" if fails else ""), flush=True)
    return not fails


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        sys.exit(0 if selftest() else 1)
    try:
        line = main(sys.argv[1]) if len(sys.argv) > 1 else "FAILED no file given"
    except Exception as e:
        traceback.print_exc()
        line = f"FAILED {type(e).__name__}: {e}"
    print(line, flush=True)
    sys.exit(0 if line.startswith("OK") else 1)
