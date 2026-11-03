// SoundHelper: the Windows ears of the Sound app. Prints one JSON object per line on stdout:
//   {"t":"dev", "id", "name", "form", "bt", "apo"}           - the default output, when it changes
//   {"t":"np",  "app", "title", "artist", "album", "genres", "playing"} - what is playing, when it changes
//   {"t":"fft", "fs", "rms", "peak", "m":[1025 magnitudes]}   - ~20 times a second while there is sound
//   {"t":"lvl", "rms", "peak"}                                 - instead of "fft" while it is silent
//     both frames carry "sd" = side/mid energy in dB (how much of the sound is stereo; absent for mono outputs)
//   {"t":"vol", "db", "s", "mute", "min", "max"}              - the Windows volume slider, when it changes
// Reads commands on stdin: "quit", and "vol <dB>" = set the default output's volume (only when the person pressed
// the app's button for it; the app never moves his volume by itself).
//   {"t":"err", "where", "msg"}
// It exits when stdin closes, so it can never outlive the app that started it.
// Audio is never stored: each 2048-sample window becomes magnitudes and is dropped.
using System.Text.Json;
using Microsoft.Win32;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using Windows.Media.Control;

static class Program
{
    const int N = 2048;
    static readonly object OutLock = new();
    static readonly JsonSerializerOptions Json = new() { NumberHandling = System.Text.Json.Serialization.JsonNumberHandling.AllowNamedFloatingPointLiterals };

    static void Emit(object o)
    {
        var s = JsonSerializer.Serialize(o, Json);
        lock (OutLock) { Console.Out.WriteLine(s); Console.Out.Flush(); }
    }

    static int Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var quit = new ManualResetEventSlim(false);
        // parent gone (stdin EOF) or "quit" -> exit
        new Thread(() =>
        {
            try
            {
                string? l;
                while ((l = Console.In.ReadLine()) != null)
                {
                    var c = l.Trim();
                    if (c == "quit") break;
                    if (c.StartsWith("vol ")) SetVolume(c[4..]);
                }
            }
            catch { }
            quit.Set();
        }) { IsBackground = true }.Start();

        var loop = new Loopback();
        string? devId = null;
        bool devApo = false;
        long lastRevive = 0;
        string npKey = "";
        string volKey = "";
        GlobalSystemMediaTransportControlsSessionManager? smtc = null;
        try { smtc = GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask().Result; }
        catch (Exception e) { Emit(new { t = "err", where = "smtc", msg = e.Message }); }

        using var en = new MMDeviceEnumerator();
        while (!quit.IsSet)
        {
            try
            {
                var dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                if (dev.ID != devId)
                {
                    devId = dev.ID;
                    devApo = ApoOn(dev.ID);
                    Emit(DescribeDevice(dev));
                    loop.Restart(dev);
                }
                // Same output, Equalizer APO switched on for it (ApoSetup, 9 Oct 2026): say so, or the app keeps
                // showing "not working" until the next device change.
                else if (ApoOn(dev.ID) != devApo) { devApo = !devApo; Emit(DescribeDevice(dev)); }
                // Switching APO on restarts the Windows audio service, which ends our loopback stream.
                if (loop.Dead && Environment.TickCount64 - lastRevive > 3000) { lastRevive = Environment.TickCount64; loop.Restart(dev); }
            }
            catch (Exception e) { Emit(new { t = "err", where = "device", msg = e.Message }); devId = null; }

            try
            {
                var v = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).AudioEndpointVolume;
                var r = v.VolumeRange;
                var o = new { t = "vol", db = Math.Round(v.MasterVolumeLevel, 2), s = Math.Round(v.MasterVolumeLevelScalar, 4),
                    mute = v.Mute, min = Math.Round(r.MinDecibels, 2), max = Math.Round(r.MaxDecibels, 2) };
                var key = JsonSerializer.Serialize(o);
                if (key != volKey) { volKey = key; Emit(o); }
            }
            catch (Exception e) { Emit(new { t = "err", where = "volume", msg = e.Message }); }

            if (smtc != null)
            {
                try
                {
                    var np = NowPlaying(smtc);
                    var key = JsonSerializer.Serialize(np);
                    if (key != npKey) { npKey = key; Emit(np); }
                }
                catch (Exception e) { Emit(new { t = "err", where = "np", msg = e.Message }); }
            }
            quit.Wait(500);
        }
        loop.Stop();
        return 0;
    }

    /// Sets the default output's slider to [arg] dB (clamped to the device's range). Own enumerator: this runs on
    /// the stdin thread, and Core Audio objects are not shared across threads here.
    static void SetVolume(string arg)
    {
        try
        {
            var db = double.Parse(arg, System.Globalization.CultureInfo.InvariantCulture);
            using var en = new MMDeviceEnumerator();
            var v = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).AudioEndpointVolume;
            var r = v.VolumeRange;
            v.MasterVolumeLevel = (float)Math.Clamp(db, r.MinDecibels, r.MaxDecibels);
        }
        catch (Exception e) { Emit(new { t = "err", where = "setvol", msg = e.Message }); }
    }

    /** Equalizer APO's own record that it is installed on this output (written by Device Selector and by ApoSetup). */
    static bool ApoOn(string id)
    {
        var guid = id.Contains('}') ? id[id.LastIndexOf('{')..] : id;
        try { using var k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\EqualizerAPO\Child APOs\" + guid); return k != null; } catch { return false; }
    }

    static object DescribeDevice(MMDevice d)
    {
        uint form = 10; // UnknownFormFactor
        string enumName = "";
        try { form = (uint)d.Properties[new PropertyKey(new Guid("1da5d803-d492-4edd-8c23-e0c0ffee7f0e"), 0)].Value; } catch { }
        try { enumName = d.Properties[new PropertyKey(new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), 24)].Value as string ?? ""; } catch { }
        string[] forms = { "remote", "speakers", "lineout", "headphones", "mic", "headset", "handset", "digital", "spdif", "hdmi", "unknown" };
        bool apo = ApoOn(d.ID);
        return new
        {
            t = "dev", id = d.ID, name = d.FriendlyName,
            form = form < forms.Length ? forms[form] : "unknown",
            bt = enumName.StartsWith("BTH", StringComparison.OrdinalIgnoreCase),
            apo,
        };
    }

    static object NowPlaying(GlobalSystemMediaTransportControlsSessionManager m)
    {
        var s = m.GetCurrentSession();
        if (s == null) return new { t = "np", app = (string?)null, title = (string?)null, artist = (string?)null, album = (string?)null, genres = Array.Empty<string>(), playing = false };
        var p = s.TryGetMediaPropertiesAsync().AsTask().Result;
        var playing = s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        return new { t = "np", app = s.SourceAppUserModelId, title = p?.Title, artist = p?.Artist, album = p?.AlbumTitle, genres = p?.Genres?.ToArray() ?? Array.Empty<string>(), playing };
    }

    /// Loopback of the default output. Keeps the last N mono samples; every hop (fs/20) emits one frame.
    sealed class Loopback
    {
        WasapiLoopbackCapture? cap;
        readonly float[] ring = new float[N];
        int filled, sinceEmit, pos;
        double sumSq, peak, midSq, sideSq;
        int fs = 48000, hop = 2400;
        readonly double[] window = Enumerable.Range(0, N).Select(i => 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (N - 1))).ToArray();

        public void Restart(MMDevice dev)
        {
            Stop();
            try
            {
                var c = new WasapiLoopbackCapture(dev);
                var f = c.WaveFormat;
                if (f.Encoding != WaveFormatEncoding.IeeeFloat && !(f.Encoding == WaveFormatEncoding.Extensible && f.BitsPerSample == 32))
                    Emit(new { t = "err", where = "loopback", msg = $"unexpected format {f}" });
                fs = f.SampleRate; hop = fs / 20; filled = 0; pos = 0; sinceEmit = 0; sumSq = 0; peak = 0; midSq = 0; sideSq = 0;
                int ch = f.Channels;
                c.DataAvailable += (_, e) => OnData(e.Buffer, e.BytesRecorded, ch);
                c.RecordingStopped += (_, e) =>
                {
                    if (!stopping) Dead = true; // not us: the audio service went away (restart, device gone)
                    if (e.Exception != null) Emit(new { t = "err", where = "loopback", msg = e.Exception.Message });
                };
                stopping = false; Dead = false;
                c.StartRecording();
                cap = c;
            }
            catch (Exception e) { Dead = true; Emit(new { t = "err", where = "loopback", msg = e.Message }); } // retried by the main loop
        }

        public volatile bool Dead;
        volatile bool stopping;

        public void Stop()
        {
            stopping = true;
            try { cap?.StopRecording(); cap?.Dispose(); } catch { }
            cap = null;
        }

        void OnData(byte[] buf, int bytes, int ch)
        {
            int frames = bytes / 4 / ch;
            for (int i = 0; i < frames; i++)
            {
                float s = 0;
                for (int c = 0; c < ch; c++)
                {
                    float v = BitConverter.ToSingle(buf, (i * ch + c) * 4);
                    s += v;
                    double a = Math.Abs(v); if (a > peak) peak = a;
                }
                if (ch >= 2)
                {
                    float l = BitConverter.ToSingle(buf, i * ch * 4), r = BitConverter.ToSingle(buf, (i * ch + 1) * 4);
                    midSq += (l + r) * (l + r) / 4; sideSq += (l - r) * (l - r) / 4;
                }
                s /= ch;
                ring[pos] = s; pos = (pos + 1) % N; // circular: pos is the oldest sample
                if (filled < N) filled++;
                sumSq += s * s;
                if (++sinceEmit >= hop) { EmitFrame(); sinceEmit = 0; sumSq = 0; peak = 0; midSq = 0; sideSq = 0; }
            }
        }

        void EmitFrame()
        {
            double rms = 10 * Math.Log10(sumSq / hop + 1e-20);
            double pk = peak;
            double? sd = midSq > 1e-12 ? Math.Round(Math.Clamp(10 * Math.Log10((sideSq + 1e-20) / midSq), -60, 20), 1) : null;
            if (rms < -80 || filled < N) { Emit(new { t = "lvl", rms = Math.Round(rms, 1), peak = Math.Round(pk, 4), sd }); return; }
            var re = new double[N]; var im = new double[N];
            for (int i = 0; i < N; i++) re[i] = ring[(pos + i) % N] * window[i];
            Fft(re, im);
            var m = new double[N / 2 + 1];
            for (int k = 0; k <= N / 2; k++) m[k] = Math.Round(Math.Sqrt(re[k] * re[k] + im[k] * im[k]), 6);
            Emit(new { t = "fft", fs, rms = Math.Round(rms, 1), peak = Math.Round(pk, 4), sd, m });
        }

        static void Fft(double[] re, double[] im)
        {
            int n = re.Length;
            for (int i = 1, j = 0; i < n; i++)
            {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) { (re[i], re[j]) = (re[j], re[i]); (im[i], im[j]) = (im[j], im[i]); }
            }
            for (int len = 2; len <= n; len <<= 1)
            {
                double ang = -2 * Math.PI / len, wr = Math.Cos(ang), wi = Math.Sin(ang);
                for (int i = 0; i < n; i += len)
                {
                    double cr = 1, ci = 0;
                    for (int k = 0; k < len / 2; k++)
                    {
                        int a = i + k, b = i + k + len / 2;
                        double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                        double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                    }
                }
            }
        }
    }
}
