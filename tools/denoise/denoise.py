"""Clean the noise out of a TALK file - lecture, video, voice note (his tap 28 Sep: "Clean a file").

His warning governs everything here: *"you have to be sure you are not removing the stuff necesary and all like some
fan delebratly on for demonstration, or some used static"*. So:
  - the original is never touched; a cleaned COPY is written beside it;
  - Gentle / Stronger: the noise is only turned DOWN, by at most --limit dB (12 / 24), never deleted outright;
  - Voice only (--voice-only, his tap 29 Sep "Add three gentle stronger voice only"): Demucs htdemucs lifts the voice
    out of everything else (music, fan, room), then DeepFilterNet with NO limit takes the steady noise Demucs leaves.
  - NO music check any more (his words 29 Sep: "if anybody has put it there, that means that they want it cleaned.
    It doesn't matter what it is"). Whatever file is picked gets cleaned. --probe still prints the voice share.

DeepFilterNet3 (code MIT, Rikorose/DeepFilterNet) at 48 kHz, on the GPU when there is one; Demucs htdemucs (MIT) for
the voice. Own venv D:/dev/denoise-venv (system torch 2.5.1+cu124 reused, nothing global installed).
Video in -> video out with the picture copied untouched (ffmpeg) and the cleaned sound; audio in -> a WAV.
The LAST line is "OK <file>" or "FAILED <reason>" - the app reads only that.
Usage: D:/dev/denoise-venv/Scripts/python denoise.py <file> [--limit 12 | --voice-only] [--probe]
"""
import json, os, random, subprocess, sys, tempfile, traceback, warnings

os.environ.setdefault("TORCH_HOME", os.path.join(os.environ.get("AUDITO_TOOLS", "D:/dev"), "demucs-models"))
warnings.filterwarnings("ignore")

SR = 48000
CHUNK_S, FADE_S = 30, 1          # long files go through in 30 s pieces, cross-faded over 1 s so no seam clicks
VOICE_SHIFTS = 4                 # Voice only: Demucs runs this many shifted passes and averages them
MUSIC_WINDOWS, WINDOW_S = 8, 8   # the music check: 8 windows of 8 s, evenly spread
MUSIC_SHARE = 0.7                # voice under 70% of a window = music. 28 Sep probe: songs 0.00-0.62 (Anuv Jain highest), talks 0.76-1.00 (0.76 = talk under noise as loud as the voice). Thin margin, 7 files


def has_video(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=codec_type,disposition",
                        "-of", "json", path], capture_output=True, text=True)
    try:
        streams = json.loads(r.stdout or "{}").get("streams", [])
    except ValueError:
        return False
    # a cover picture inside an mp3 shows up as a video stream; it is not a video
    return any(not s.get("disposition", {}).get("attached_pic") for s in streams)


def voice_shares(wav):
    """Voice share of the power in each check window, from Demucs. wav: [2, T] at SR."""
    import torch, torchaudio
    from demucs.apply import apply_model
    from demucs.pretrained import get_model
    model = get_model("htdemucs"); model.eval()
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    x = torchaudio.functional.resample(wav, SR, model.samplerate)
    n, w = x.shape[-1], WINDOW_S * model.samplerate
    starts = [0] if n <= w else [round(i * (n - w) / (MUSIC_WINDOWS - 1)) for i in range(MUSIC_WINDOWS)]
    vi = model.sources.index("vocals")
    out = []
    for s in starts:
        seg = x[:, s:s + w]
        p = float((seg ** 2).mean())
        if p < 1e-8:
            continue                              # a silent stretch says nothing either way
        std = seg.mean(0).std().clamp_min(1e-6)
        with torch.no_grad():
            src = apply_model(model, (seg / std)[None], device=dev, progress=False)[0] * std
        voice = float((src[vi] ** 2).mean())
        rest = float(sum((src[i] ** 2).mean() for i in range(len(model.sources)) if i != vi))
        out.append(voice / max(voice + rest, 1e-12))
    return out


def voice_stem(wav):
    """The voice alone, from Demucs over the whole file (it splits long input itself). wav: [2, T] at SR -> same."""
    import torch, torchaudio
    from demucs.apply import apply_model
    from demucs.pretrained import get_model
    model = get_model("htdemucs"); model.eval()
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    x = torchaudio.functional.resample(wav, SR, model.samplerate)
    mean, std = x.mean(), x.std().clamp_min(1e-6)
    # Demucs shifts the input by a random offset; one shift made the pause noise swing 26-31 dB between identical runs
    # (29 Sep). Averaging VOICE_SHIFTS shifts is Demucs' own quality trick, and the seed makes the answer repeatable.
    torch.manual_seed(0); random.seed(0)          # demucs.apply draws its shifts from Python's random, not torch
    with torch.no_grad():
        src = apply_model(model, ((x - mean) / std)[None], device=dev, shifts=VOICE_SHIFTS, overlap=0.5,
                          progress=False)[0]
    v = src[model.sources.index("vocals")].cpu() * std + mean / len(model.sources)
    v = torchaudio.functional.resample(v, model.samplerate, SR)
    n = wav.shape[-1]
    return torch.nn.functional.pad(v, (0, max(0, n - v.shape[-1])))[:, :n]


def level_match(y, wav):
    """Give the voice back its loudness (RULES: changing the sound must never change the volume). Measured on the
    loudest fifth of 100 ms frames - where the voice is - in both; capped so the peak stays under 0.98, never cut."""
    import torch
    hop = SR // 10
    def loud(x):
        m = x.mean(0)
        n = m.shape[-1] // hop
        if n < 5:
            return float((m ** 2).mean())
        p = (m[: n * hop].reshape(n, hop) ** 2).mean(1)
        return float(p.sort().values[-max(1, n // 5):].median())
    gain = (loud(wav) / max(loud(y), 1e-12)) ** 0.5
    gain = min(gain, 0.98 / max(float(y.abs().max()), 1e-9))
    return y * max(gain, 1.0)


def clean(wav, limit):
    import torch
    from df.enhance import enhance, init_df
    model, state, _ = init_df(log_level="ERROR")
    n, c, f = wav.shape[-1], CHUNK_S * SR, FADE_S * SR
    if n <= c + f:
        return enhance(model, state, wav, atten_lim_db=limit)
    out = torch.zeros_like(wav)
    ramp = torch.linspace(0, 1, f)
    s = 0
    while s < n:
        a, b = max(0, s - f), min(n, s + c)
        y = enhance(model, state, wav[:, a:b].contiguous(), atten_lim_db=limit)
        if a > 0:                                 # cross-fade the overlap with the piece before
            y[:, :f] *= ramp
            out[:, a:a + f] *= 1 - ramp
        out[:, a:b] += y
        s = b
    return out


def main(path, limit, probe, voice_only=False):
    import numpy as np
    import torch
    from demucs.audio import AudioFile, save_audio

    if not os.path.isfile(path):
        return "FAILED no such file"
    wav = AudioFile(path).read(streams=0, samplerate=SR, channels=2)
    if wav.numel() == 0 or float(wav.abs().max()) < 1e-5:
        return "FAILED the file has no sound in it"
    print(f"read {wav.shape[-1] / SR:.0f} s", flush=True)

    if probe:
        shares = voice_shares(wav)
        print("voice share per window: " + " ".join(f"{v:.2f}" for v in shares), flush=True)
        return f"PROBE music windows {sum(v < MUSIC_SHARE for v in shares)} of {len(shares)}"

    y = clean(voice_stem(wav), None) if voice_only else clean(wav, limit)
    y = level_match(y, wav)
    removed = float(((wav - y) ** 2).mean() / (wav ** 2).mean().clamp_min(1e-12))
    print(f"took out {100 * removed:.1f}% of the power ({'voice only' if voice_only else f'limit {limit} dB'})", flush=True)
    if y.shape != wav.shape or not torch.isfinite(y).all():
        return "FAILED the cleaned sound came out broken"
    if float(y.abs().max()) < 1e-5:
        return "FAILED no voice was found in this file, so nothing was left after cleaning"

    name, ext = os.path.splitext(os.path.basename(path))
    folder = os.path.dirname(os.path.abspath(path))
    tag = "voice only" if voice_only else "cleaned"
    if not has_video(path):
        dst = os.path.join(folder, f"{name} ({tag}).wav")
        save_audio(y.cpu(), dst, SR)
        return f"OK {dst}"
    dst = os.path.join(folder, f"{name} ({tag}){ext.lower() if ext.lower() in ('.mp4', '.mkv', '.mov', '.m4v') else '.mkv'}")
    with tempfile.TemporaryDirectory() as tmp:
        a = os.path.join(tmp, "clean.wav")
        save_audio(y.cpu(), a, SR)
        r = subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", path, "-i", a, "-map", "0:v:0", "-map", "1:a:0",
                            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", dst], capture_output=True, text=True)
    if r.returncode != 0 or not os.path.isfile(dst) or os.path.getsize(dst) < 1000:
        return f"FAILED could not put the video back together: {(r.stderr or '').strip()[-200:]}"
    return f"OK {dst}"


if __name__ == "__main__":
    args = sys.argv[1:]
    probe = "--probe" in args
    limit = 12.0
    if "--limit" in args:
        limit = float(args[args.index("--limit") + 1])
    files = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] != "--limit")]
    try:
        line = main(files[0], limit, probe, "--voice-only" in args) if files else "FAILED no file given"
    except Exception as e:
        traceback.print_exc()
        line = f"FAILED {type(e).__name__}: {e}"
    print(line, flush=True)
    sys.exit(0 if line.startswith(("OK", "PROBE")) else 1)
