// The install logic on a fake registry. Each case names what it proves; a run prints every case so a selftest that
// ran nothing cannot pass silently (a test whose output does not name its own cases proves nothing).
namespace ApoSetup;

public sealed class MemReg : IReg
{
    public readonly Dictionary<string, Dictionary<string, object>> Keys = new(StringComparer.OrdinalIgnoreCase);
    Dictionary<string, object>? K(string p) => Keys.TryGetValue(p, out var k) ? k : null;
    public bool KeyExists(string p) => Keys.ContainsKey(p);
    public IEnumerable<string> SubKeys(string p) => Keys.Keys
        .Where(k => k.StartsWith(p + "\\", StringComparison.OrdinalIgnoreCase) && !k[(p.Length + 1)..].Contains('\\'))
        .Select(k => k[(p.Length + 1)..]).ToList();
    public bool ValueExists(string p, string n) => K(p)?.ContainsKey(n) == true;
    public string? ReadString(string p, string n) => K(p)?.GetValueOrDefault(n) switch { string s => s, string[] m => m[0], _ => null };
    public uint? ReadDword(string p, string n) => K(p)?.GetValueOrDefault(n) as uint?;
    public void CreateKey(string p) { if (!Keys.ContainsKey(p)) Keys[p] = new(StringComparer.OrdinalIgnoreCase); }
    public void DeleteKey(string p) => Keys.Remove(p);
    public void WriteString(string p, string n, string v) => Need(p)[n] = v;
    public void WriteMulti(string p, string n, string v) => Need(p)[n] = new[] { v };
    public void DeleteValue(string p, string n) => K(p)?.Remove(n);
    Dictionary<string, object> Need(string p) => K(p) ?? throw new InvalidOperationException("no key " + p); // real registry: OpenSubKey(p, true)! throws too
    public void Set(string p, string n, object v) { CreateKey(p); Keys[p][n] = v; }
    /** Parents Windows refuses to create under until ownership is taken (as on the MMDevices device keys). */
    public readonly HashSet<string> Locked = new(StringComparer.OrdinalIgnoreCase);
    public readonly List<string> OwnershipTaken = new();
    public bool CreateKeyTakingOwnership(string parent, string path)
    {
        if (!Keys.ContainsKey(parent)) throw new InvalidOperationException("missing key " + parent);
        var took = Locked.Remove(parent);
        if (took) OwnershipTaken.Add(parent);
        CreateKey(path);
        return took;
    }
}

public static class SelfTest
{
    const string G = "{11111111-2222-3333-4444-555555555555}";
    static string Key => Plan.Render + "\\" + G;
    static string Fx => Key + @"\FxProperties";
    static string Child => Plan.ChildApos + "\\" + G;
    const string Combined = "{b3f8fa53-0004-438e-9003-51a46e139bfc},41";
    const string Multi = "{d04e05a6-594b-4fb6-a80d-01af5eed7d1d},13";
    const string DisableEnh = "{1da5d803-d492-4edd-8c23-e0c0ffee7f0e},5";
    const string X = "{AAAAAAAA-0000-0000-0000-000000000001}", Y = "{BBBBBBBB-0000-0000-0000-000000000002}";

    static MemReg Dev(uint state, params (string n, object v)[] fx)
    {
        var r = new MemReg();
        r.Set(Key, "DeviceState", state);
        r.Set(Key + @"\Properties", "{b3f8fa53-0004-438e-9003-51a46e139bfc},6", "Test Buds");
        r.CreateKey(Fx);
        foreach (var (n, v) in fx) r.Set(Fx, n, v);
        return r;
    }

    static int fails;
    static void Check(string name, bool ok, string detail = "")
    {
        Console.WriteLine($"{(ok ? "PASS" : "FAIL")} {name}{(ok || detail == "" ? "" : "  <- " + detail)}");
        if (!ok) fails++;
    }

    public static int Run()
    {
        fails = 0;
        // 1. his speakers, exactly as Device Selector found them (driver had only multi-SFX/MFX/EFX) and in the mode it
        //    used: the result must equal what is in his registry now (read 9 Oct 2026, check-sample does it live)
        {
            var r = Dev(1, (Multi, X));
            Plan.Install(r, G, Mode.SfxMfx);
            Check("speakers sample: SFX=pre, MFX=post", r.ReadString(Fx, Plan.Sfx) == Plan.Pre && r.ReadString(Fx, Plan.Mfx) == Plan.Post);
            Check("speakers sample: originals recorded as !VALUE", Plan.Slots.All(s => r.ReadString(Child, s) == Plan.NoValue));
            Check("speakers sample: child records empty, version 2, silent-buffer false",
                r.ReadString(Child, "PreMixChild") == "" && r.ReadString(Child, "PostMixChild") == ""
                && r.ReadString(Child, "Version") == "2" && r.ReadString(Child, "AllowSilentBufferModification") == "false");
            Check("speakers sample: driver's multi-SFX left alone", r.ReadString(Fx, Multi) == X);
        }
        // 2. Bluetooth buds Windows 11 has "combined": SFX/MFX, the driver's own effects kept as children
        {
            var r = Dev(4, (Plan.Sfx, X), (Plan.Mfx, Y));
            r.Set(Key + @"\Properties", Combined, "x");
            var d = Plan.Look(r, G);
            Check("combined BT: absent device still installed", d.Verdict == "install" && d.Absent, d.Verdict);
            Check("combined BT: mode SfxMfx", d.Mode == Mode.SfxMfx, d.Mode?.ToString() ?? "null");
            Plan.Install(r, G, d.Mode!.Value);
            Check("combined BT: pre child = driver SFX, post child = driver MFX",
                r.ReadString(Child, "PreMixChild") == X && r.ReadString(Child, "PostMixChild") == Y);
            Check("combined BT: SFX=pre MFX=post", r.ReadString(Fx, Plan.Sfx) == Plan.Pre && r.ReadString(Fx, Plan.Mfx) == Plan.Post);
        }
        // 3. dongle / non-combined BT with SFX+MFX: SFX/EFX, MFX untouched, EFX gets post-mix
        {
            var r = Dev(1, (Plan.Sfx, X), (Plan.Mfx, Y));
            var d = Plan.Look(r, G);
            Check("dongle: mode SfxEfx", d.Mode == Mode.SfxEfx, d.Mode?.ToString() ?? "null");
            Plan.Install(r, G, d.Mode!.Value);
            Check("dongle: SFX=pre, EFX=post, MFX still the driver's",
                r.ReadString(Fx, Plan.Sfx) == Plan.Pre && r.ReadString(Fx, Plan.Efx) == Plan.Post && r.ReadString(Fx, Plan.Mfx) == Y);
            Check("dongle: pre child = driver SFX, no post child", r.ReadString(Child, "PreMixChild") == X && r.ReadString(Child, "PostMixChild") == "");
            Check("dongle: EFX processing mode added", r.ValueExists(Fx, "{d3993a3f-99c2-4402-b5ec-a92a0367664b},7"));
        }
        // 4. a driver with only LFX/GFX keeps that pair
        {
            var r = Dev(1, (Plan.Lfx, X), (Plan.Gfx, Y));
            var d = Plan.Look(r, G);
            Check("lfx/gfx driver: mode LfxGfx", d.Mode == Mode.LfxGfx, d.Mode?.ToString() ?? "null");
            Plan.Install(r, G, d.Mode!.Value);
            Check("lfx/gfx driver: LFX=pre GFX=post, children = driver's",
                r.ReadString(Fx, Plan.Lfx) == Plan.Pre && r.ReadString(Fx, Plan.Gfx) == Plan.Post
                && r.ReadString(Child, "PreMixChild") == X && r.ReadString(Child, "PostMixChild") == Y);
        }
        // 5. never touch: disabled outputs, outputs already on
        {
            var dis = Dev(0x10000000 | 1, (Plan.Sfx, X));
            Check("disabled output skipped", Plan.Look(dis, G).Verdict.StartsWith("skip"));
            var on = Dev(1, (Plan.Sfx, Plan.Pre), (Plan.Mfx, Plan.Post));
            Check("already on: left alone", Plan.Look(on, G).Verdict == "ok: already on");
            var onLower = Dev(1, (Plan.Sfx, Plan.Pre.ToLowerInvariant()));
            Check("already on, lower-case guid: still recognised", Plan.Look(onLower, G).Verdict == "ok: already on");
            var enhOff = Dev(1, (Plan.Sfx, Plan.Pre), (DisableEnh, 1u));
            Check("already on but enhancements off: fixed", Plan.Look(enhOff, G).Verdict.StartsWith("fix:"));
        }
        // 6. a second run changes nothing (the task fires on every device event)
        {
            var r = Dev(1, (Plan.Sfx, X), (Plan.Mfx, Y));
            Plan.Install(r, G, Plan.Look(r, G).Mode!.Value);
            Check("second look says already on", Plan.Look(r, G).Verdict == "ok: already on");
        }
        // 7. undo puts the driver's own effects back exactly
        {
            var r = Dev(1, (Plan.Sfx, X), (Plan.Mfx, Y));
            var before = Plan.Slots.Select(s => r.ReadString(Fx, s)).ToArray();
            Plan.Install(r, G, Mode.SfxEfx);
            Plan.Uninstall(r, G);
            var after = Plan.Slots.Select(s => r.ReadString(Fx, s)).ToArray();
            Check("undo restores every slot", before.SequenceEqual(after), string.Join(",", after));
            Check("undo removes the child record", !r.KeyExists(Child));
        }
        // 8. the reproduction check itself must be able to say DIFFERS (a check that cannot fail is no check)
        {
            var r = Dev(1, (Multi, X));
            Plan.Install(r, G, Mode.SfxMfx);
            Check("reproduce: matches its own install", Reproduce(r, G).ok);
            r.WriteString(Child, "PreMixChild", Y);
            Check("reproduce: a tampered record DIFFERS", !Reproduce(r, G).ok);
        }
        // 9. an output with NO effects key (his tap 31 Oct 2026; Device Selector: "APO can be installed (experimental)")
        {
            var r = Dev(4); r.DeleteKey(Fx); r.Locked.Add(Key);
            var d = Plan.Look(r, G);
            Check("no effects key: installed, flagged as a new key", d.Verdict == "install" && d.NewEffectsKey, d.Verdict);
            Check("no effects key: default mode SfxEfx (upstream load(), nothing to keep)", d.Mode == Mode.SfxEfx, d.Mode?.ToString() ?? "null");
            var did = Plan.Install(r, G, d.Mode!.Value);
            Check("no effects key: ownership taken of the DEVICE key only when refused", r.OwnershipTaken.SequenceEqual(new[] { Key }), string.Join(",", r.OwnershipTaken));
            Check("no effects key: SFX=pre, EFX=post, title Equalizer APO",
                r.ReadString(Fx, Plan.Sfx) == Plan.Pre && r.ReadString(Fx, Plan.Efx) == Plan.Post
                && r.ReadString(Fx, "{b725f130-47ef-101a-a5f1-02608c9eebac},10") == "Equalizer APO");
            Check("no effects key: originals recorded as !KEY, no children",
                Plan.Slots.All(s => r.ReadString(Child, s) == Plan.NoKey) && r.ReadString(Child, "PreMixChild") == "" && r.ReadString(Child, "PostMixChild") == "");
            Check("no effects key: the log line says so", did.Any(x => x.StartsWith("effects key created (ownership")), string.Join("; ", did));
            Check("no effects key: second look says already on", Plan.Look(r, G).Verdict == "ok: already on");
            Check("no effects key: reproduce matches", Reproduce(r, G).ok, string.Join(" | ", Reproduce(r, G).report));
            Plan.Uninstall(r, G);
            Check("no effects key: undo deletes the key it made, and the child record", !r.KeyExists(Fx) && !r.KeyExists(Child));
            var open = Dev(1); open.DeleteKey(Fx);
            Plan.Install(open, G, Mode.SfxEfx);
            Check("no effects key, not locked: no ownership taken", open.OwnershipTaken.Count == 0 && open.KeyExists(Fx));
        }
        // 10. undo after Windows removed a device we had switched on: no throw, record dropped, the next device still undone
        {
            var r = Dev(1, (Plan.Sfx, X), (Plan.Mfx, Y));
            Plan.Install(r, G, Mode.SfxEfx);
            foreach (var k in r.Keys.Keys.Where(k => k.StartsWith(Key, StringComparison.OrdinalIgnoreCase)).ToList()) r.DeleteKey(k);
            List<string>? did = null; Exception? err = null;
            try { did = Plan.Uninstall(r, G); } catch (Exception e) { err = e; }
            Check("removed device: undo does not throw", err == null, err?.Message ?? "");
            Check("removed device: its record is dropped", !r.KeyExists(Child) && did?.Any(x => x.Contains("no longer")) == true);
        }
        Console.WriteLine(fails == 0 ? "SELFTEST PASS" : $"SELFTEST FAIL ({fails})");
        return fails == 0 ? 0 : 1;
    }

    /** Rebuilds the device as it was before Device Selector touched it (originals from its child record), runs OUR
     *  install in the mode Device Selector used, and compares slots and child record with what is there. */
    public static (bool ok, List<string> report) Reproduce(IReg live, string guid)
    {
        var rep = new List<string>();
        var key = Plan.Render + "\\" + guid;
        var fx = key + @"\FxProperties";
        var child = Plan.ChildApos + "\\" + guid;
        if (!live.KeyExists(fx)) return (false, new() { "no FxProperties" });
        var mem = new MemReg();
        mem.Set(key, "DeviceState", live.ReadDword(key, "DeviceState") ?? 0u);
        // an effects key Device Selector (or we) created records !KEY: rebuild the device WITHOUT it, as it was
        if (live.ReadString(child, Plan.Slots[0]) != Plan.NoKey) mem.CreateKey(fx);
        foreach (var s in Plan.Slots)
        {
            var o = live.ReadString(child, s);
            if (o != null && o != Plan.NoValue && o != Plan.NoKey) mem.Set(fx, s, o);
        }
        if (live.ValueExists(fx, Multi)) mem.Set(fx, Multi, live.ReadString(fx, Multi)!);
        var mode = live.ReadString(fx, Plan.Lfx) == Plan.Pre ? Mode.LfxGfx
            : string.Equals(live.ReadString(fx, Plan.Efx), Plan.Post, StringComparison.OrdinalIgnoreCase) ? Mode.SfxEfx : Mode.SfxMfx;
        rep.Add($"mode used: {mode}; our default for it would be {Plan.DefaultMode_(mem, key)}");
        Plan.Install(mem, guid, mode);
        var ok = true;
        foreach (var s in Plan.Slots)
        {
            var a = live.ReadString(fx, s); var b = mem.ReadString(fx, s);
            if (!string.Equals(a, b, StringComparison.OrdinalIgnoreCase)) { ok = false; rep.Add($"slot {s}: live {a ?? "-"} vs ours {b ?? "-"}"); }
        }
        foreach (var n in Plan.Slots.Concat(new[] { "PreMixChild", "PostMixChild", "AllowSilentBufferModification", "Version" }))
        {
            var a = live.ReadString(child, n); var b = mem.ReadString(child, n);
            if (a != b) { ok = false; rep.Add($"child {n}: live {a ?? "-"} vs ours {b ?? "-"}"); }
        }
        rep.Add(ok ? "every slot and child value identical" : "see differences above");
        return (ok, rep);
    }
}
