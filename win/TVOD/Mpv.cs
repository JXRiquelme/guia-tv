using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Threading;

namespace TVOD;

/// <summary>Audio / subtitle track reported by mpv.</summary>
public class Track
{
    public int Id;
    public string Type = "";
    public string Title = "";
    public bool Selected;
}

/// <summary>Thin wrapper around libmpv (libmpv-2.dll next to the exe). Used from the UI thread.</summary>
public sealed class Mpv
{
    const string Lib = "libmpv-2.dll";
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_create();
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_initialize(IntPtr h);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_set_option_string(IntPtr h, byte[] name, byte[] value);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_set_property_string(IntPtr h, byte[] name, byte[] value);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_get_property_string(IntPtr h, byte[] name);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern void mpv_free(IntPtr p);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_command(IntPtr h, IntPtr[] args);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_observe_property(IntPtr h, ulong id, byte[] name, int format);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_wait_event(IntPtr h, double timeout);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern void mpv_terminate_destroy(IntPtr h);

    [StructLayout(LayoutKind.Sequential)] struct MpvEvent { public int Id; public int Error; public ulong Reply; public IntPtr Data; }
    [StructLayout(LayoutKind.Sequential)] struct MpvEndFile { public int Reason; public int Error; }
    [StructLayout(LayoutKind.Sequential)] struct MpvProperty { public IntPtr Name; public int Format; public IntPtr Data; }

    const int EventShutdown = 1, EventEndFile = 7, EventPlaybackRestart = 21, EventPropertyChange = 22;
    const int FormatFlag = 3, FormatDouble = 5;
    const int ReasonEof = 0, ReasonError = 4;

    public const string UserAgent = "VLC/3.0.20 LibVLC/3.0.20";

    readonly IntPtr h;
    readonly Dispatcher ui;
    bool closed;

    public double Position, Duration;
    public bool Paused, Buffering;

    /// <summary>error = playback failed, eof = reached the end of a movie.</summary>
    public Action<bool, bool> OnEndFile;
    public Action OnReady;

    static byte[] U(string s) => Encoding.UTF8.GetBytes(s + "\0");

    public Mpv(IntPtr wid, Dispatcher dispatcher)
    {
        ui = dispatcher;
        h = mpv_create();
        if (h == IntPtr.Zero) throw new Exception("No se pudo iniciar el reproductor (libmpv).");
        var opts = new (string, string)[]
        {
            ("wid", wid.ToInt64().ToString(CultureInfo.InvariantCulture)),
            ("vo", "gpu"),
            ("hwdec", "auto-safe"),
            ("user-agent", UserAgent),
            ("network-timeout", "15"),
            ("cache", "yes"),
            // a minute ahead (the provider sends films slowly); after a stall, gather 8 s before going on
            ("demuxer-max-bytes", "150MiB"),
            ("demuxer-readahead-secs", "60"),
            ("cache-pause-wait", "8"),
            ("keep-open", "no"),
            ("idle", "yes"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("input-cursor", "no"),
            ("cursor-autohide", "no"),
            ("osc", "no"),
            ("osd-level", "0"),
            ("terminal", "no"),
            ("alang", "es,spa,lat,es-419"),
            ("slang", "es,spa,lat,es-419"),
            ("sub-font-size", "46"),
            ("sub-border-size", "2.5"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5"),
        };
        foreach (var (k, v) in opts) mpv_set_option_string(h, U(k), U(v));
        mpv_initialize(h);
        mpv_observe_property(h, 1, U("time-pos"), FormatDouble);
        mpv_observe_property(h, 2, U("duration"), FormatDouble);
        mpv_observe_property(h, 3, U("pause"), FormatFlag);
        mpv_observe_property(h, 4, U("paused-for-cache"), FormatFlag);
        new Thread(Loop) { IsBackground = true, Name = "mpv-events" }.Start();
    }

    void Loop()
    {
        while (true)
        {
            var p = mpv_wait_event(h, -1);
            if (p == IntPtr.Zero) continue;
            var e = Marshal.PtrToStructure<MpvEvent>(p);
            switch (e.Id)
            {
                case EventShutdown:
                    return;
                case EventEndFile:
                    if (e.Data != IntPtr.Zero)
                    {
                        var end = Marshal.PtrToStructure<MpvEndFile>(e.Data);
                        bool err = end.Reason == ReasonError, eof = end.Reason == ReasonEof;
                        if (err || eof) ui.BeginInvoke(() => { Buffering = false; OnEndFile?.Invoke(err, eof); });
                    }
                    break;
                case EventPlaybackRestart:
                    ui.BeginInvoke(() => { Buffering = false; OnReady?.Invoke(); });
                    break;
                case EventPropertyChange:
                    if (e.Data == IntPtr.Zero) break;
                    var prop = Marshal.PtrToStructure<MpvProperty>(e.Data);
                    var name = Marshal.PtrToStringUTF8(prop.Name) ?? "";
                    if (prop.Format == FormatDouble && prop.Data != IntPtr.Zero)
                    {
                        var v = BitConverter.Int64BitsToDouble(Marshal.ReadInt64(prop.Data));
                        ui.BeginInvoke(() => { if (name == "time-pos") Position = v; else if (name == "duration") Duration = v; });
                    }
                    else if (prop.Format == FormatFlag && prop.Data != IntPtr.Zero)
                    {
                        var v = Marshal.ReadInt32(prop.Data) != 0;
                        ui.BeginInvoke(() => { if (name == "pause") Paused = v; else if (name == "paused-for-cache") Buffering = v; });
                    }
                    break;
            }
        }
    }

    public void Close()
    {
        if (closed) return;
        closed = true;
        try { Command("quit"); } catch { }
    }

    // ------------------------------------------------------------ commands

    public void Command(params string[] args)
    {
        if (closed && args.Length > 0 && args[0] != "quit") return;
        var ptrs = new IntPtr[args.Length + 1];
        for (int i = 0; i < args.Length; i++) ptrs[i] = Marshal.StringToCoTaskMemUTF8(args[i]);
        try { mpv_command(h, ptrs); }
        finally { for (int i = 0; i < args.Length; i++) Marshal.FreeCoTaskMem(ptrs[i]); }
    }

    public void Set(string name, string value) { if (!closed) mpv_set_property_string(h, U(name), U(value)); }

    public string Get(string name)
    {
        if (closed) return null;
        var p = mpv_get_property_string(h, U(name));
        if (p == IntPtr.Zero) return null;
        try { return Marshal.PtrToStringUTF8(p); } finally { mpv_free(p); }
    }

    public double GetDouble(string name, double def = -1) =>
        double.TryParse(Get(name), NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : def;

    public void Load(string url, double start = 0)
    {
        Set("start", start > 1 ? ((int)start).ToString(CultureInfo.InvariantCulture) : "none");
        Set("pause", "no");
        Position = 0;
        Duration = 0;
        Buffering = true;
        Command("loadfile", url, "replace");
    }

    public void Stop()
    {
        Command("stop");
        Buffering = false;
    }

    public void TogglePause() => Set("pause", Paused ? "no" : "yes");
    public void SetPaused(bool p) => Set("pause", p ? "yes" : "no");
    public void SeekBy(double s) => Command("seek", s.ToString(CultureInfo.InvariantCulture), "relative");
    public void SeekTo(double s) => Command("seek", s.ToString(CultureInfo.InvariantCulture), "absolute");
    /// <summary>"16:9", "4:3" or "no" (original).</summary>
    public void SetAspect(string v) => Set("video-aspect-override", v);
    public void SetVolume(int v) => Set("volume", v.ToString(CultureInfo.InvariantCulture));

    public List<Track> Tracks(string type)
    {
        int count = int.TryParse(Get("track-list/count"), out var c) ? c : 0;
        var outList = new List<Track>();
        for (int i = 0; i < count; i++)
        {
            if (Get($"track-list/{i}/type") != type || !int.TryParse(Get($"track-list/{i}/id"), out var id)) continue;
            var lang = Get($"track-list/{i}/lang") ?? "";
            var title = Get($"track-list/{i}/title") ?? "";
            var ch = Get($"track-list/{i}/demux-channel-count") ?? "";
            var parts = new List<string>();
            if (title.Length > 0) parts.Add(title);
            if (lang.Length > 0 && lang != "und") parts.Add(LangName(lang));
            if (int.TryParse(ch, out var n) && n > 0) parts.Add(n == 2 ? "estéreo" : n == 6 ? "5.1" : n == 8 ? "7.1" : $"{n} canales");
            var label = string.Join(" · ", parts.Distinct());
            outList.Add(new Track
            {
                Id = id, Type = type, Title = label.Length == 0 ? $"Pista {outList.Count + 1}" : label,
                Selected = Get($"track-list/{i}/selected") == "yes"
            });
        }
        return outList;
    }

    static string LangName(string code)
    {
        var map = new Dictionary<string, string>
        {
            ["spa"] = "Español", ["es"] = "Español", ["lat"] = "Español latino", ["es-419"] = "Español latino",
            ["eng"] = "Inglés", ["en"] = "Inglés", ["por"] = "Portugués", ["pt"] = "Portugués",
            ["fre"] = "Francés", ["fra"] = "Francés", ["fr"] = "Francés", ["ita"] = "Italiano", ["it"] = "Italiano",
            ["ger"] = "Alemán", ["deu"] = "Alemán", ["de"] = "Alemán", ["jpn"] = "Japonés", ["ja"] = "Japonés",
            ["kor"] = "Coreano", ["ko"] = "Coreano", ["chi"] = "Chino", ["zho"] = "Chino",
        };
        return map.TryGetValue(code.ToLowerInvariant(), out var n) ? n : code;
    }

    public void SelectAudio(int id) => Set("aid", id.ToString(CultureInfo.InvariantCulture));
    public void SelectSubtitle(int? id) => Set("sid", id.HasValue ? id.Value.ToString(CultureInfo.InvariantCulture) : "no");

    /// <summary>General language settings, applied before each file (also drops the previous manual choice).</summary>
    public void ApplyLanguages(string audio, string subtitles)
    {
        var langs = new Dictionary<string, string> { ["es"] = "es,spa,lat,es-419", ["en"] = "en,eng" };
        Set("alang", langs.GetValueOrDefault(audio) ?? "");
        Set("aid", "auto");
        if (subtitles == "off") Set("sid", "no");
        else
        {
            Set("slang", langs.GetValueOrDefault(subtitles) ?? "");
            Set("sid", "auto");
        }
    }
}
