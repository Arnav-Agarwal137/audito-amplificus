"""Karaoke + BGM extraction from a song FILE (his asks 26 Sep: karaoke, "bgm extract and instrumentalise it").

Demucs htdemucs (code MIT, facebookresearch/demucs), two stems: the voice and everything else. Runs in its own venv
D:/dev/demucs-venv (system torch 2.5.1+cu124 reused, nothing global installed); models in D:/dev/demucs-models.
Writes, in a folder "<song> (karaoke)" beside the song:
    "<song> - music only.wav"   the instrumental (karaoke / BGM)
    "<song> - voice only.wav"
Prints one line per stage; the LAST line is "OK <folder>" or "FAILED <reason>" - the app reads only that.
Checked 28 Sep on a 40 s clip: voice + music add back to the original within -52 dB (at the same sample rate).
Usage: D:/dev/demucs-venv/Scripts/python karaoke.py <song file>
"""
import os, sys, traceback

os.environ.setdefault("TORCH_HOME", os.path.join(os.environ.get("AUDITO_TOOLS", "D:/dev"), "demucs-models"))


def main(path):
    import numpy as np
    import torch
    from demucs.apply import apply_model
    from demucs.audio import AudioFile, save_audio
    from demucs.pretrained import get_model

    if not os.path.isfile(path):
        return "FAILED no such file"
    name = os.path.splitext(os.path.basename(path))[0]
    out = os.path.join(os.path.dirname(path), f"{name} (karaoke)")
    os.makedirs(out, exist_ok=True)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"model on {dev}", flush=True)
    model = get_model("htdemucs"); model.eval()
    wav = AudioFile(path).read(streams=0, samplerate=model.samplerate, channels=model.audio_channels)
    ref = wav.mean(0)
    std = ref.std()
    if float(std) < 1e-6:
        return "FAILED the file is silent"
    print(f"separating {wav.shape[-1] / model.samplerate:.0f} s", flush=True)
    with torch.no_grad():
        src = apply_model(model, ((wav - ref.mean()) / std)[None], device=dev, progress=False)[0]
    src = src * std + ref.mean()
    vi = model.sources.index("vocals")
    voice = src[vi]
    # music = song minus voice, so the two files add back to the song exactly and nothing of it can go missing:
    # whatever the model's four parts do not account for (-28 dB on his beat song, 28 Sep) stays in the music
    music = wav - voice
    err = float(((wav - src.sum(0)) ** 2).mean() / (wav ** 2).mean().clamp_min(1e-12))
    share = float((voice ** 2).mean() / (wav ** 2).mean().clamp_min(1e-12))
    print(f"model's own parts off by {10 * np.log10(max(err, 1e-12)):.0f} dB; voice is {100 * share:.0f}% of the power", flush=True)
    if share > 2.0:
        return "FAILED the voice came out louder than the whole song"
    save_audio(music.cpu(), os.path.join(out, f"{name} - music only.wav"), model.samplerate)
    save_audio(voice.cpu(), os.path.join(out, f"{name} - voice only.wav"), model.samplerate)
    return f"OK {out}"


if __name__ == "__main__":
    try:
        line = main(sys.argv[1]) if len(sys.argv) > 1 else "FAILED no file given"
    except Exception as e:
        traceback.print_exc()
        line = f"FAILED {type(e).__name__}: {e}"
    print(line, flush=True)
    sys.exit(0 if line.startswith("OK") else 1)
