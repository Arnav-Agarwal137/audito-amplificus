// Port of Equalizer APO 1.4.2 DeviceAPOInfo.cpp load() + install() + uninstall() with the Device Selector's DEFAULT
// choices (pre-mix and post-mix on, keep the driver's own effects as children, auto-adjust on). Nothing here touches
// the real registry by itself: every read and write goes through IReg, so the selftest runs it on a fake one.
namespace ApoSetup;

public interface IReg
{
    bool KeyExists(string path);
    IEnumerable<string> SubKeys(string path);
    bool ValueExists(string path, string name);
    string? ReadString(string path, string name); // REG_SZ, or the first string of a REG_MULTI_SZ; null if absent
    uint? ReadDword(string path, string name);
    void CreateKey(string path);
    void DeleteKey(string path);
    void WriteString(string path, string name, string value);
    void WriteMulti(string path, string name, string value);
    void DeleteValue(string path, string name);
    /** Create [path] (a direct child of [parent]). If Windows refuses, take ownership of [parent] for Administrators and
     *  give them full access, then create it: Equalizer APO 1.4.2 install() + RegistryHelper takeOwnership/makeWritable.
     *  Returns true when ownership had to be taken. */
    bool CreateKeyTakingOwnership(string parent, string path);
}

public enum Mode { LfxGfx = 0, SfxMfx = 1, SfxEfx = 2 } // same order as Device Selector's combo box

public sealed record Device(string Guid, string Name, uint State, string Verdict, Mode? Mode = null, bool NewEffectsKey = false)
{
    public bool Absent => (State & 4) != 0;
}

public static class Plan
{
    public const string Render = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render";
    public const string ChildApos = @"SOFTWARE\EqualizerAPO\Child APOs";
    public const string Pre = "{EACD2258-FCAC-4FF4-B36D-419E924A6D79}";  // EQUALIZERAPO_PRE_MIX_GUID
    public const string Post = "{EC1CC9CE-FAED-4822-828A-82A81A6F018F}"; // EQUALIZERAPO_POST_MIX_GUID
    public const string NoKey = "!KEY", NoValue = "!VALUE";
    const string Fx = "{d04e05a6-594b-4fb6-a80d-01af5eed7d1d}";
    public static readonly string Lfx = Fx + ",1", Gfx = Fx + ",2", Sfx = Fx + ",5", Mfx = Fx + ",6", Efx = Fx + ",7";
    static readonly string MultiSfx = Fx + ",13", MultiMfx = Fx + ",14", MultiEfx = Fx + ",15";
    public static readonly string[] Slots = { Lfx, Gfx, Sfx, Mfx, Efx };
    const int L = 0, G = 1, S = 2, M = 3, E = 4;
    const string SfxModes = "{d3993a3f-99c2-4402-b5ec-a92a0367664b},5", MfxModes = "{d3993a3f-99c2-4402-b5ec-a92a0367664b},6",
        EfxModes = "{d3993a3f-99c2-4402-b5ec-a92a0367664b},7", DefaultMode = "{C18E2F7E-933D-4965-B7D1-1EEF228D2AF3}";
    const string FxTitle = "{b725f130-47ef-101a-a5f1-02608c9eebac},10"; // fxTitleValueName
    const string DisableEnh = "{1da5d803-d492-4edd-8c23-e0c0ffee7f0e},5";
    const string Combined = "{b3f8fa53-0004-438e-9003-51a46e139bfc},41";
    const string ConnName = "{a45c254e-df1c-4efd-8020-67d146a850e0},2", DevName = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6";

    static bool IsOurs(string? v) => v != null && (v.Equals(Pre, StringComparison.OrdinalIgnoreCase) || v.Equals(Post, StringComparison.OrdinalIgnoreCase));

    /** What to do for one playback output. Absent devices (Bluetooth / dongle not connected now) ARE included, unlike
     *  Device Selector: that is the whole point, his headphones were never connected when it ran. */
    public static Device Look(IReg r, string guid)
    {
        var key = Render + "\\" + guid;
        var state = r.ReadDword(key, "DeviceState") ?? 0;
        var name = $"{r.ReadString(key + @"\Properties", DevName)} ({r.ReadString(key + @"\Properties", ConnName)})";
        if ((state & 2) != 0 || (state & 0x10000000) != 0) return new(guid, name, state, "skip: disabled in Windows");
        var fx = key + @"\FxProperties";
        // No effects key: Device Selector lists these as "APO can be installed (experimental)" and installs them when
        // ticked by creating FxProperties (taking ownership if needed). His tap 31 Oct 2026: do that for other PCs too.
        if (!r.KeyExists(fx)) return new(guid, name, state, "install", DefaultMode_(r, key), NewEffectsKey: true);
        if (Slots.Any(s => IsOurs(r.ReadString(fx, s))))
            return new(guid, name, state, IsEnhDisabled(r, fx) ? "fix: Windows audio enhancements are off" : "ok: already on");
        return new(guid, name, state, "install", DefaultMode_(r, key));
    }

    static bool IsEnhDisabled(IReg r, string fx) => r.ValueExists(fx, DisableEnh) && (r.ReadDword(fx, DisableEnh) ?? 1) != 0;

    /** DeviceAPOInfo.cpp load(), Windows 8.1+ branch. */
    public static Mode DefaultMode_(IReg r, string key)
    {
        var fx = key + @"\FxProperties";
        bool Has(string n) => r.ValueExists(fx, n);
        if ((Has(Lfx) || Has(Gfx)) && !Has(Sfx) && !Has(Mfx) && !Has(Efx) && !Has(MultiSfx) && !Has(MultiMfx) && !Has(MultiEfx))
            return Mode.LfxGfx;
        // bluetooth devices may be combined in Windows 11, EFX will not work then (upstream comment)
        if (r.ValueExists(key + @"\Properties", Combined)) return Mode.SfxMfx;
        return Mode.SfxEfx;
    }

    /** DeviceAPOInfo.cpp install(). Returns the changes made, in words. A device with no FxProperties key gets one
     *  (upstream: createKey, on refusal takeOwnership + makeWritable of the device key), its originals are recorded as
     *  !KEY, and undo deletes the key again. */
    public static List<string> Install(IReg r, string guid, Mode mode)
    {
        var did = new List<string>();
        var key = Render + "\\" + guid;
        var fx = key + @"\FxProperties";
        var child = ChildApos + "\\" + guid;
        r.CreateKey(ChildApos);
        r.CreateKey(child);

        var orig = new string[Slots.Length];
        if (!r.KeyExists(fx))
        {
            var took = r.CreateKeyTakingOwnership(key, fx);
            did.Add(took ? "effects key created (ownership taken for Administrators, as Equalizer APO does)" : "effects key created");
            r.WriteString(fx, FxTitle, "Equalizer APO");
            for (int i = 0; i < Slots.Length; i++) { orig[i] = NoKey; r.WriteString(child, Slots[i], NoKey); }
        }
        else for (int i = 0; i < Slots.Length; i++)
        {
            var v = r.ReadString(fx, Slots[i]);
            orig[i] = v == null || IsOurs(v) ? NoValue : v;
            r.WriteString(child, Slots[i], orig[i]);
        }

        string PreChild()
        {
            var g = mode switch
            {
                Mode.LfxGfx => orig[L] == NoValue && orig[G] == NoValue ? orig[S] : orig[L],
                Mode.SfxMfx => orig[S] == NoValue && orig[M] == NoValue ? orig[L] : orig[S],
                _ => orig[S] == NoValue && orig[E] == NoValue ? orig[L] : orig[S],
            };
            return g is NoKey or NoValue ? "" : g;
        }
        string PostChild()
        {
            var g = mode switch
            {
                Mode.LfxGfx => orig[L] == NoValue && orig[G] == NoValue ? orig[M] : orig[G],
                Mode.SfxMfx => orig[S] == NoValue && orig[M] == NoValue ? orig[G] : orig[M],
                _ => orig[S] == NoValue && orig[E] == NoValue ? orig[G] : orig[E],
            };
            return g is NoKey or NoValue ? "" : g;
        }
        r.WriteString(child, "PreMixChild", PreChild());
        r.WriteString(child, "PostMixChild", PostChild());
        r.WriteString(child, "AllowSilentBufferModification", "false");
        if (r.ValueExists(child, "DisableAutomaticAdjustment")) r.DeleteValue(child, "DisableAutomaticAdjustment");
        r.WriteString(child, "Version", "2");

        void Set(string n, string v) { r.WriteString(fx, n, v); did.Add($"{n} = {v}"); }
        void Del(string n) { if (r.ValueExists(fx, n)) { r.DeleteValue(fx, n); did.Add($"{n} removed (kept as child)"); } }
        void ModeIfMissing(string n) { if (!r.ValueExists(fx, n)) { r.WriteMulti(fx, n, DefaultMode); did.Add($"{n} = default mode"); } }
        switch (mode)
        {
            case Mode.LfxGfx:
                Set(Lfx, Pre); Set(Gfx, Post); Del(Sfx); Del(Mfx); Del(Efx);
                break;
            case Mode.SfxMfx:
                Del(Lfx); Del(Gfx);
                Set(Sfx, Pre); ModeIfMissing(SfxModes);
                Set(Mfx, Post); ModeIfMissing(MfxModes);
                break; // efx untouched
            case Mode.SfxEfx:
                Del(Lfx); Del(Gfx);
                Set(Sfx, Pre); ModeIfMissing(SfxModes);
                Set(Efx, Post); ModeIfMissing(EfxModes);
                break; // mfx untouched
        }
        EnableEnhancements(r, fx, did);
        return did;
    }

    public static void EnableEnhancements(IReg r, string fx, List<string> did)
    {
        if (r.ValueExists(fx, DisableEnh)) { r.DeleteValue(fx, DisableEnh); did.Add("Windows audio enhancements switched back on"); }
    }

    /** DeviceAPOInfo.cpp uninstall(): put the driver's own effects back from the child record, then drop the record. */
    public static List<string> Uninstall(IReg r, string guid)
    {
        var did = new List<string>();
        var fx = Render + "\\" + guid + @"\FxProperties";
        var child = ChildApos + "\\" + guid;
        if (!r.KeyExists(child)) return did;
        // Windows removed the device (an unpaired Bluetooth set): nothing to restore, and writing to its missing key would
        // throw and stop undo for every device after it (found 31 Oct 2026 on his laptop: {2bb092f2...})
        if (!r.KeyExists(Render + "\\" + guid)) { r.DeleteKey(child); did.Add("device no longer in Windows: its record removed"); return did; }
        if (r.ReadString(child, Slots[0]) == NoKey) // we created the effects key: upstream deletes it whole
        {
            if (r.KeyExists(fx)) { r.DeleteKey(fx); did.Add("effects key removed (it did not exist before)"); }
            r.DeleteKey(child);
            return did;
        }
        for (int i = 0; i < Slots.Length; i++)
        {
            var o = r.ReadString(child, Slots[i]);
            if (o == NoValue) { if (r.ValueExists(fx, Slots[i])) { r.DeleteValue(fx, Slots[i]); did.Add($"{Slots[i]} removed"); } }
            else if (!string.IsNullOrEmpty(o) && o != NoKey) { r.WriteString(fx, Slots[i], o); did.Add($"{Slots[i]} = {o} (driver's own, restored)"); }
        }
        r.DeleteKey(child);
        return did;
    }

    public static IEnumerable<Device> All(IReg r) => r.SubKeys(Render).Select(g => Look(r, g));
}
