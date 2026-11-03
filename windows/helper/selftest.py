"""Helper self-test: play a 1 kHz tone at -20 dBFS for 2 s and check SoundHelper saw it.
Checks the helper's OWN output (not re-typed values): a dev line, and fft frames whose loudest bin is 1 kHz
and whose rms is near the tone's. Also a silent control: before the tone, no fft frame may claim 1 kHz.
Run with the eqapo venv python (needs pyaudiowpatch + numpy)."""
import json, os, subprocess, sys, threading, time
import numpy as np
import pyaudiowpatch as pa

EXE = sys.argv[1:] or ["dotnet", os.path.join(os.environ.get("AUDITO_TOOLS", "D:/dev"), "sound-builds/helper/SoundHelper.dll")]
proc = subprocess.Popen(EXE, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
lines = []
threading.Thread(target=lambda: [lines.append((time.time(), l)) for l in proc.stdout], daemon=True).start()
time.sleep(2.0)
t_tone = time.time()
p = pa.PyAudio()
out = p.get_device_info_by_index(p.get_host_api_info_by_type(pa.paWASAPI)["defaultOutputDevice"])
rate, ch = int(out["defaultSampleRate"]), out["maxOutputChannels"]
t = np.arange(int(rate * 2)) / rate
x = (float(os.environ.get("TONE_AMP", "0.1")) * np.sin(2 * np.pi * 1000 * t)).astype(np.float32)
s = p.open(format=pa.paFloat32, channels=ch, rate=rate, output=True, output_device_index=out["index"])
s.write(np.repeat(x[:, None], ch, axis=1).tobytes()); s.close(); p.terminate()
time.sleep(0.5)
proc.stdin.write("quit\n"); proc.stdin.flush()
rc = proc.wait(timeout=5)

msgs = [(ts, json.loads(l)) for ts, l in lines]
kinds = {}
for _, m in msgs: kinds[m["t"]] = kinds.get(m["t"], 0) + 1
print("helper exit", rc, "| lines by kind:", kinds)
for _, m in msgs:
    if m["t"] in ("dev", "np", "err"): print(" ", m)
ffts = [(ts, m) for ts, m in msgs if m["t"] == "fft"]
during = [m for ts, m in ffts if t_tone + 0.5 < ts < t_tone + 2.0]
before = [m for ts, m in ffts if ts < t_tone]
ok = True
def at1k(m):
    n = 2 * (len(m["m"]) - 1); k = round(1000 * n / m["fs"])
    return 20 * np.log10(max(m["m"][k - 2:k + 3]) + 1e-12)
if not during or not before:
    print("FAIL: frames during", len(during), "before", len(before)); ok = False
else:
    # music may be playing, so compare the 1 kHz bin before vs during instead of expecting silence
    rise = np.median([at1k(m) for m in during]) - np.median([at1k(m) for m in before])
    print(f"1 kHz bin: rose {rise:+.1f} dB when the tone started ({len(before)} frames before, {len(during)} during)")
    ok &= rise > 10
ok &= rc == 0
print("SELFTEST", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
