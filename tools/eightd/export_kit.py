"""Writes the 8D kit for the phone: every FIXED filter eightd.py builds, computed by eightd.py's own functions, so the
phone's 8D copy (core EightD.kt) is the laptop's one he approved ("Good, keep it", 29 Sep) and not a re-derivation.
Also writes a parity fixture: a short seeded clip and eightd.render()'s output for it, which core's EightDTest must
reproduce.

Kit format (little-endian): b"8DK1", int32 rate, dirs, taps, eqLen, lpLen, lag, roomLen, float64 gain,
then float64 hrirs[dir][ear][tap], eq[eqLen], lp[lpLen], room[ear][roomLen].
Fixture: b"8DF1", int32 n, float32 in[n][2], float32 out[n][2].

Usage: D:/dev/demucs-venv/Scripts/python export_kit.py <kit out> <fixture out>
"""
import struct
import sys

import numpy as np
from scipy.signal import firwin

import eightd as e


def main(kit_path, fixture_path):
    hrirs = e.load_hrirs()                                    # (dirs, taps, ears)
    eq = e.diffuse_eq(hrirs)
    gain = e.high_gain(hrirs, eq)
    lp = firwin(e.XOVER_TAPS, e.BASS_HZ, fs=e.RATE)
    lag = int(np.argmax(np.abs(hrirs[0]).max(1)))
    room = e.room_ir()                                        # (n, ears)
    dirs, taps, _ = hrirs.shape
    with open(kit_path, "wb") as f:
        f.write(b"8DK1")
        f.write(struct.pack("<7i", e.RATE, dirs, taps, len(eq), len(lp), lag, len(room)))
        f.write(struct.pack("<d", gain))
        f.write(np.ascontiguousarray(hrirs.transpose(0, 2, 1), dtype="<f8").tobytes())
        f.write(np.ascontiguousarray(eq, dtype="<f8").tobytes())
        f.write(np.ascontiguousarray(lp, dtype="<f8").tobytes())
        f.write(np.ascontiguousarray(room.T, dtype="<f8").tobytes())
    rng = np.random.default_rng(11)
    n = e.RATE                                                # one second: 30 degrees of travel, many HRIR steps
    t = np.arange(n) / e.RATE
    clip = np.stack([0.2 * np.sin(2 * np.pi * 60 * t) + 0.1 * rng.standard_normal(n),
                     0.15 * np.sin(2 * np.pi * 440 * t) + 0.1 * rng.standard_normal(n)], 1)
    out, cut = e.render(clip)
    with open(fixture_path, "wb") as f:
        f.write(b"8DF1")
        f.write(struct.pack("<i", n))
        f.write(np.ascontiguousarray(clip, dtype="<f4").tobytes())
        f.write(np.ascontiguousarray(out, dtype="<f4").tobytes())
    print(f"OK dirs={dirs} taps={taps} eq={len(eq)} lp={len(lp)} lag={lag} room={len(room)} gain={gain:.6f} cut={cut:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
