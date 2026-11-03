"""Reads an 8D copy BACK FROM DISK next to its original: rate, length, loudness, per-octave tone, and the left-right
balance over time (it should swing with the 12 s turn). Usage: check_file.py <original> <8D copy>"""
import sys
import numpy as np
import torch
from demucs.audio import AudioFile

a = AudioFile(sys.argv[1]).read(streams=0, samplerate=44100, channels=2).numpy().T
b = AudioFile(sys.argv[2]).read(streams=0, samplerate=44100, channels=2).numpy().T
info = AudioFile(sys.argv[2]).info
print(f"8D file: rate {info['streams'][0]['sample_rate']}, {len(b) / 44100:.2f} s (original {len(a) / 44100:.2f} s)")
db = lambda x: 20 * np.log10(np.sqrt((x ** 2).mean()) + 1e-20)
print(f"loudness 8D {db(b):.2f} dBFS, original {db(a):.2f}; peak 8D {np.abs(b).max():.3f}")
fa, fb = np.abs(np.fft.rfft(a.mean(1))) ** 2, (np.abs(np.fft.rfft(b, axis=0)) ** 2).mean(1)
fq = np.fft.rfftfreq(len(a), 1 / 44100)
row = []
for lo in [31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]:
    m = (fq >= lo) & (fq < 2 * lo)
    row.append(f"{lo}:{10 * np.log10(fb[m].sum() / fa[m].sum()):+.1f}")
print("tone vs original per octave (dB):", " ".join(row))
sec = 44100
bal = [10 * np.log10((b[i:i + sec, 1] ** 2).sum() / max((b[i:i + sec, 0] ** 2).sum(), 1e-20)) for i in range(0, len(b) - sec, sec)]
print("right-over-left each second (dB):", " ".join(f"{v:+.0f}" for v in bal))
