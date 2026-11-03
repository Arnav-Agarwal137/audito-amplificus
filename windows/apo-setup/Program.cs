// ApoSetup: switch Equalizer APO on for every playback output (see ApoSetup.csproj for why).
//   plan          what it would do, read-only, no admin
//   apply         do it (admin); restarts the Windows audio service only if something changed
//   setup         task-install, then apply: the one command the app runs elevated
//   install F     portable copy: run the script F (install-apo.ps1: download + install Equalizer APO, then setup).
//                 Elevated through THIS exe so Windows' prompt names "Audito Amplificus sound setup", not PowerShell
//   undo          take OUR installs off again (admin); outputs the person ticked by hand are never touched
//   task-install  copy itself to Program Files and register a SYSTEM task: at boot and whenever a device is
//                 plugged in or connected (Kernel-PnP events 400/410), so new headphones are switched on by themselves
//   task-remove   remove that task
//   selftest      the logic on a fake registry, including a reproduction of what Device Selector wrote
//   check-sample  compare our install logic against a device Device Selector itself installed (read-only)
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using Microsoft.Win32;
using ApoSetup;

var cmd = args.Length > 0 ? args[0] : "plan";
try
{
    return cmd switch
    {
        "plan" => PlanCmd(),
        "apply" => Admin() ? Apply() : 3,
        "setup" => Admin() ? (TaskInstall() == 0 ? Apply() : 6) : 3, // what the app's button runs: both, one permission tap
        "install" => Admin() ? InstallScript(args.Length > 1 ? args[1] : "") : 3,
        "undo" => Admin() ? Undo() : 3,
        "task-install" => Admin() ? TaskInstall() : 3,
        "task-remove" => Admin() ? Schtasks("/Delete", "/TN", Names.Task, "/F") : 3,
        "selftest" => SelfTest.Run(),
        "check-sample" => CheckSample(),
        _ => Usage(),
    };
}
catch (Exception e)
{
    Log($"FAILED {cmd}: {e.GetType().Name}: {e.Message}");
    return 1;
}

static int Usage() { Console.WriteLine("usage: ApoSetup plan|setup|install <script>|apply|undo|task-install|task-remove|selftest|check-sample"); return 2; }

// Already elevated, so the script and the ApoSetup setup it runs ask nothing more. Windows' own powershell.exe by full
// path (never whatever PATH finds first).
static int InstallScript(string script)
{
    if (!File.Exists(script)) { Log($"install: no script at '{script}'"); return 2; }
    var self = Process.GetCurrentProcess().MainModule!.FileName;
    var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe"))
        { UseShellExecute = false, CreateNoWindow = true };
    foreach (var a in new[] { "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, "-ApoSetup", self }) psi.ArgumentList.Add(a);
    using var p = Process.Start(psi)!;
    p.WaitForExit();
    Log($"install: script exit {p.ExitCode}");
    return p.ExitCode;
}

static bool Admin()
{
    if (new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) return true;
    Console.WriteLine("needs administrator rights (Windows asks for permission)");
    return false;
}

static string DataDir()
{
    var d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AuditoAmplificus");
    Directory.CreateDirectory(d);
    return d;
}

// Not a cleanup aid only: undo must never take off what the person ticked in Device Selector themselves.
static string OursFile() => Path.Combine(DataDir(), "apo-installed-by-audito.txt");

static void Log(string s)
{
    var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {s}";
    Console.WriteLine(line);
    try
    {
        var f = Path.Combine(DataDir(), "apo-setup.log");
        if (File.Exists(f) && new FileInfo(f).Length > 256 * 1024) File.Move(f, f + ".old", true);
        File.AppendAllText(f, line + Environment.NewLine);
    }
    catch { } // reporting must never be able to fail the operation
}

static int PlanCmd()
{
    var r = new WinReg();
    foreach (var d in Plan.All(r))
        Console.WriteLine($"{(d.NewEffectsKey ? "install (new effects key)" : d.Verdict),-48} {(d.Mode?.ToString() ?? ""),-7} {(d.Absent ? "absent " : "present")} {d.Name} {d.Guid}");
    Console.WriteLine($"protected audiodg off: {ProtectedDgOff(r)}; Equalizer APO registered: {Registered(r)}");
    return 0;
}

static bool ProtectedDgOff(IReg r) => r.ReadDword(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Audio", "DisableProtectedAudioDG") == 1;
static bool Registered(IReg r) => Registry.ClassesRoot.OpenSubKey(@"AudioEngine\AudioProcessingObjects\" + Plan.Pre) != null
    && Registry.ClassesRoot.OpenSubKey(@"AudioEngine\AudioProcessingObjects\" + Plan.Post) != null;

static int Apply()
{
    var r = new WinReg();
    if (!Registered(r)) { Log("Equalizer APO is not installed (its APO is not registered): nothing done"); return 4; }
    if (!ProtectedDgOff(r)) Log("warning: DisableProtectedAudioDG is not 1; Equalizer APO's installer normally sets it");
    var changed = 0;
    for (int pass = 0; pass < 2; pass++) // a new device's keys can appear a few seconds after the plug-in event
    {
        foreach (var d in Plan.All(r).ToList())
        {
            if (d.Verdict == "install" && d.Mode is Mode m)
            {
                var did = Plan.Install(r, d.Guid, m);
                File.AppendAllText(OursFile(), d.Guid + Environment.NewLine);
                Log($"switched on: {d.Name} {d.Guid} as {m}: {string.Join("; ", did)}");
                changed++;
            }
            else if (d.Verdict.StartsWith("fix:"))
            {
                var did = new List<string>();
                Plan.EnableEnhancements(r, Plan.Render + "\\" + d.Guid + @"\FxProperties", did);
                Log($"fixed: {d.Name}: {string.Join("; ", did)}");
                changed++;
            }
        }
        if (pass == 0 && Environment.GetCommandLineArgs().Contains("--wait")) Thread.Sleep(8000);
    }
    if (changed > 0) RestartAudio(); else Log("apply: every output already on, nothing changed");
    return 0;
}

static int Undo()
{
    var r = new WinReg();
    var ours = File.Exists(OursFile()) ? File.ReadAllLines(OursFile()).Where(s => s.Length > 0).Distinct().ToList() : new();
    var failed = new List<string>();
    foreach (var g in ours)
    {
        // one device failing must not leave every device after it switched on
        try { Log($"undo {g}: {string.Join("; ", Plan.Uninstall(r, g))}"); }
        catch (Exception e) { failed.Add(g); Log($"undo {g} FAILED: {e.GetType().Name}: {e.Message}"); }
    }
    if (failed.Count > 0) File.WriteAllLines(OursFile(), failed); else File.Delete(OursFile()); // keep what is still ours
    if (failed.Count > 0) { if (ours.Count > failed.Count) RestartAudio(); return 1; }
    if (ours.Count > 0) RestartAudio();
    return 0;
}

static void RestartAudio()
{
    // Device Selector's own device test restarts only AudioSrv (DeviceTestThread.cpp); a reboot is not needed.
    using var s = new ServiceController("Audiosrv");
    if (s.Status != ServiceControllerStatus.Stopped) { s.Stop(); s.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(20)); }
    s.Start();
    s.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(20));
    Log("Windows audio service restarted so the change takes effect now");
}

static int CheckSample()
{
    var r = new WinReg();
    var any = false;
    foreach (var g in r.SubKeys(Plan.ChildApos))
    {
        if (!r.KeyExists(Plan.Render + "\\" + g)) { Console.WriteLine($"NOT EXAMINED {g}: device no longer in Windows"); continue; }
        any = true;
        var (ok, report) = SelfTest.Reproduce(r, g);
        Console.WriteLine($"{(ok ? "MATCH" : "DIFFERS")} {g}");
        foreach (var line in report) Console.WriteLine("   " + line);
        if (!ok) return 1;
    }
    if (!any) { Console.WriteLine("NOT EXAMINED: no device installed by Device Selector to compare with"); return 5; }
    return 0;
}


static int TaskInstall()
{
    // The task runs as SYSTEM, so what it runs must sit where only administrators can write (never a user folder).
    var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Audito Amplificus", "apo-setup");
    Directory.CreateDirectory(dir);
    var src = AppContext.BaseDirectory;
    foreach (var f in Directory.GetFiles(src))
        File.Copy(f, Path.Combine(dir, Path.GetFileName(f)), true);
    var dotnet = Process.GetCurrentProcess().MainModule!.FileName;
    var dll = Path.Combine(dir, "ApoSetup.dll");
    if (!Path.GetFileName(dotnet).Equals("dotnet.exe", StringComparison.OrdinalIgnoreCase))
    { dll = ""; dotnet = Path.Combine(dir, "ApoSetup.exe"); }
    string Esc(string s) => System.Security.SecurityElement.Escape(s)!;
    string Ev(int id) => $"<EventTrigger><Enabled>true</Enabled><Delay>PT5S</Delay><Subscription>{Esc(
        $"<QueryList><Query Id=\"0\" Path=\"Microsoft-Windows-Kernel-PnP/Configuration\"><Select Path=\"Microsoft-Windows-Kernel-PnP/Configuration\">*[System[EventID={id}]]</Select></Query></QueryList>")}</Subscription></EventTrigger>";
    var xml = $"""
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo><Description>Switches Equalizer APO on for new headphones and speakers, so Audito Amplificus works on them.</Description></RegistrationInfo>
          <Triggers><BootTrigger><Enabled>true</Enabled><Delay>PT30S</Delay></BootTrigger>{Ev(400)}{Ev(410)}</Triggers>
          <Principals><Principal id="Author"><UserId>S-1-5-18</UserId><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
          <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
            <ExecutionTimeLimit>PT2M</ExecutionTimeLimit><Enabled>true</Enabled><Hidden>true</Hidden>
          </Settings>
          <Actions Context="Author"><Exec><Command>{Esc(dotnet)}</Command><Arguments>{Esc((dll == "" ? "" : $"\"{dll}\" ") + "apply --wait")}</Arguments></Exec></Actions>
        </Task>
        """;
    var tmp = Path.Combine(DataDir(), "task.xml");
    File.WriteAllText(tmp, xml, System.Text.Encoding.Unicode);
    var rc = Schtasks("/Create", "/TN", Names.Task, "/XML", tmp, "/F");
    Log($"task-install: {(rc == 0 ? "registered" : "FAILED rc=" + rc)} -> {dotnet} {dll}");
    return rc;
}

static int Schtasks(params string[] a)
{
    var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "schtasks.exe")) { UseShellExecute = false };
    foreach (var x in a) psi.ArgumentList.Add(x);
    using var p = Process.Start(psi)!;
    p.WaitForExit();
    return p.ExitCode;
}

sealed class WinReg : IReg
{
    static readonly RegistryKey Hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
    public bool KeyExists(string p) { using var k = Hklm.OpenSubKey(p); return k != null; }
    public IEnumerable<string> SubKeys(string p) { using var k = Hklm.OpenSubKey(p); return k?.GetSubKeyNames() ?? Array.Empty<string>(); }
    public bool ValueExists(string p, string n) { using var k = Hklm.OpenSubKey(p); return k?.GetValueNames().Contains(n) == true; }
    public string? ReadString(string p, string n)
    {
        using var k = Hklm.OpenSubKey(p);
        return k?.GetValue(n) switch { string s => s, string[] m => m.FirstOrDefault(), _ => null };
    }
    public uint? ReadDword(string p, string n) { using var k = Hklm.OpenSubKey(p); return k?.GetValue(n) is int i ? unchecked((uint)i) : null; }
    // Administrators hold only SetValue + ReadKey on the MMDevices device keys (Audiosrv, AudioEndpointBuilder and
    // TrustedInstaller hold the rest). OpenSubKey(p, true) asks for KEY_WRITE, which includes CreateSubKey, so Windows
    // refused it (SecurityException, 9 Oct 2026). Ask only for what a value write needs, as Equalizer APO does.
    static RegistryKey OpenForValues(string p) =>
        Hklm.OpenSubKey(p, RegistryKeyPermissionCheck.ReadWriteSubTree, RegistryRights.QueryValues | RegistryRights.SetValue) ?? throw new InvalidOperationException("missing key " + p);
    public void CreateKey(string p) { using var _ = Hklm.CreateSubKey(p); }
    public void DeleteKey(string p) => Hklm.DeleteSubKeyTree(p, false);
    public void WriteString(string p, string n, string v) { using var k = OpenForValues(p); k.SetValue(n, v, RegistryValueKind.String); }
    public void WriteMulti(string p, string n, string v) { using var k = OpenForValues(p); k.SetValue(n, new[] { v }, RegistryValueKind.MultiString); }
    public void DeleteValue(string p, string n) { using var k = OpenForValues(p); k.DeleteValue(n, false); }

    // Equalizer APO 1.4.2 install(): createKey(FxProperties); on refusal takeOwnership(device key) + makeWritable(device
    // key), then createKey again (helpers/RegistryHelper.cpp, read 31 Oct 2026). Same order, same Administrators group.
    public bool CreateKeyTakingOwnership(string parent, string path)
    {
        var name = path[(parent.Length + 1)..];
        if (TryCreate(parent, name)) return false;
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
        using (Privilege.TakeOwnership())
        using (var k = Hklm.OpenSubKey(parent, RegistryKeyPermissionCheck.ReadWriteSubTree, RegistryRights.TakeOwnership)
            ?? throw new InvalidOperationException("missing key " + parent))
        {
            var owner = new RegistrySecurity();
            owner.SetOwner(admins);
            k.SetAccessControl(owner); // only the owner section was set, so only the owner is written
        }
        using (var k = Hklm.OpenSubKey(parent, RegistryKeyPermissionCheck.ReadWriteSubTree, RegistryRights.ReadPermissions | RegistryRights.ChangePermissions)
            ?? throw new InvalidOperationException("missing key " + parent))
        {
            var dacl = k.GetAccessControl(AccessControlSections.Access);
            // upstream: KEY_ALL_ACCESS, SET_ACCESS, SUB_CONTAINERS_AND_OBJECTS_INHERIT for BUILTIN\Administrators
            dacl.SetAccessRule(new RegistryAccessRule(admins, RegistryRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            k.SetAccessControl(dacl);
        }
        if (!TryCreate(parent, name)) throw new InvalidOperationException("could not create " + path + " even after taking ownership");
        return true;
    }

    static bool TryCreate(string parent, string name)
    {
        try
        {
            using var p = Hklm.OpenSubKey(parent, RegistryKeyPermissionCheck.ReadWriteSubTree, RegistryRights.CreateSubKey | RegistryRights.ReadKey);
            if (p == null) throw new InvalidOperationException("missing key " + parent);
            using var _ = p.CreateSubKey(name);
            return true;
        }
        catch (System.Security.SecurityException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
    }
}

// SeTakeOwnershipPrivilege on for one block and off again in Dispose (a process-wide borrow is given back in a finally:
// `using` is that finally). Administrators hold the privilege but it starts disabled.
sealed class Privilege : IDisposable
{
    [System.Runtime.InteropServices.DllImport("advapi32.dll", SetLastError = true)]
    static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [System.Runtime.InteropServices.DllImport("advapi32.dll", SetLastError = true, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    static extern bool LookupPrivilegeValue(string? system, string name, out long luid);
    [System.Runtime.InteropServices.DllImport("advapi32.dll", SetLastError = true)]
    static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TokenPrivileges state, int len, IntPtr prev, IntPtr retLen);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    static extern bool CloseHandle(IntPtr h);
    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, Pack = 4)]
    struct TokenPrivileges { public int Count; public long Luid; public int Attributes; }

    readonly IntPtr token; TokenPrivileges tp;
    Privilege(string name)
    {
        if (!OpenProcessToken(Process.GetCurrentProcess().Handle, 0x0020 | 0x0008, out token)) throw new InvalidOperationException("OpenProcessToken failed");
        try
        {
            if (!LookupPrivilegeValue(null, name, out var luid)) throw new InvalidOperationException("LookupPrivilegeValue failed");
            tp = new TokenPrivileges { Count = 1, Luid = luid, Attributes = 2 /* SE_PRIVILEGE_ENABLED */ };
            if (!AdjustTokenPrivileges(token, false, ref tp, System.Runtime.InteropServices.Marshal.SizeOf<TokenPrivileges>(), IntPtr.Zero, IntPtr.Zero)
                || System.Runtime.InteropServices.Marshal.GetLastWin32Error() != 0)
                throw new InvalidOperationException(name + " could not be enabled");
        }
        catch { CloseHandle(token); throw; }
    }
    public static Privilege TakeOwnership() => new("SeTakeOwnershipPrivilege");
    public void Dispose()
    {
        try { tp.Attributes = 0; AdjustTokenPrivileges(token, false, ref tp, System.Runtime.InteropServices.Marshal.SizeOf<TokenPrivileges>(), IntPtr.Zero, IntPtr.Zero); }
        finally { CloseHandle(token); }
    }
}

static class Names { public const string Task = "Audito Amplificus - headphones"; }
