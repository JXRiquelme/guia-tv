using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Xml;

namespace TVOD;

// ---------------------------------------------------------------- live channels and guide

public class Alt
{
    public string Label { get; set; } = "";
    public string Url { get; set; } = "";
}

public class Channel
{
    public int Number;
    public string Name = "";
    public string TvgId = "";
    public string Logo = "";
    public string Url = "";
    /// <summary>Other streams of the same channel (#EXTALT lines or "(Op. N)" entries).</summary>
    public List<Alt> Alts = new();
    public List<Alt> Options => new[] { new Alt { Label = "Opción 1", Url = Url } }.Concat(Alts).ToList();
}

public class Prog
{
    public DateTime Start;   // UTC
    public DateTime Stop;    // UTC
    public string Title = "";
    public string Desc = "";
}

public static class Search
{
    public static string Normalize(string s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        var d = s.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(d.Length);
        foreach (var c in d)
            if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(char.ToLowerInvariant(c));
        return sb.ToString();
    }
}

public static class Playlist
{
    static readonly Regex AttrRx = new("([\\w-]+)=\"([^\"]*)\"");
    static readonly Regex OpRx = new("\\s*\\((?:Op\\.?|Opci[oó]n)\\s*(\\d+)\\)\\s*$", RegexOptions.IgnoreCase);

    public static List<Channel> Parse(string text)
    {
        var outList = new List<Channel>();
        var alts = new List<List<Alt>>();
        int target = -1;
        string pending = null;
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith("#EXTALT:", StringComparison.OrdinalIgnoreCase))
            {
                var body = line.Substring(8);
                var bar = body.LastIndexOf('|');
                if (target >= 0 && bar > 0)
                {
                    var u = body.Substring(bar + 1).Trim();
                    if (u.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                        alts[target].Add(new Alt { Label = body.Substring(0, bar).Trim(), Url = u });
                }
            }
            else if (line.StartsWith("#EXTINF", StringComparison.OrdinalIgnoreCase))
            {
                pending = line;
            }
            else if (!line.StartsWith("#") && pending != null)
            {
                var info = pending;
                pending = null;
                if (!line.StartsWith("http", StringComparison.OrdinalIgnoreCase)) continue;
                var attrs = new Dictionary<string, string>();
                foreach (Match m in AttrRx.Matches(info)) attrs[m.Groups[1].Value.ToLowerInvariant()] = m.Groups[2].Value;
                var name = DisplayName(info);
                if (name.Length == 0) name = attrs.GetValueOrDefault("tvg-name") ?? $"Canal {outList.Count + 1}";
                // "DAZN LaLiga 1 (Op. 2)" is not listed: it becomes an option of "DAZN LaLiga 1"
                var op = OpRx.Match(name);
                if (op.Success)
                {
                    var b = name.Substring(0, op.Index).Trim();
                    var owner = outList.FindIndex(c => string.Equals(c.Name, b, StringComparison.OrdinalIgnoreCase));
                    if (owner >= 0)
                    {
                        alts[owner].Add(new Alt { Label = "Opción " + op.Groups[1].Value, Url = line });
                        target = -1;
                        continue;
                    }
                }
                outList.Add(new Channel
                {
                    Number = outList.Count + 1, Name = name, TvgId = attrs.GetValueOrDefault("tvg-id") ?? "",
                    Logo = attrs.GetValueOrDefault("tvg-logo") ?? "", Url = line
                });
                alts.Add(new List<Alt>());
                target = outList.Count - 1;
            }
        }
        string[] tags = { "(FHD)", "(HD)", "(SD)", "(4K)" };
        for (int i = 0; i < outList.Count; i++)
        {
            if (alts[i].Count == 0) continue;
            var seen = new HashSet<string> { outList[i].Url };
            var list = new List<Alt>();
            foreach (var a in alts[i])
            {
                if (!seen.Add(a.Url)) continue;
                var tag = tags.FirstOrDefault(t => a.Label.ToUpperInvariant().Contains(t));
                list.Add(new Alt { Label = $"Opción {list.Count + 2}" + (tag != null ? " " + tag : ""), Url = a.Url });
            }
            outList[i].Alts = list;
        }
        return outList;
    }

    static string DisplayName(string line)
    {
        bool quoted = false;
        for (int i = 0; i < line.Length; i++)
        {
            if (line[i] == '"') quoted = !quoted;
            if (line[i] == ',' && !quoted) return line.Substring(i + 1).Trim();
        }
        return "";
    }
}

/// <summary>Streams the XMLTV file keeping only now-2h .. now+36h for the wanted channels.</summary>
public static class GuideParser
{
    static DateTime? Time(string s)
    {
        // "20261124143000 +0000"
        if (string.IsNullOrEmpty(s) || s.Length < 14) return null;
        if (!DateTime.TryParseExact(s.Substring(0, 14), "yyyyMMddHHmmss", CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var t)) return null;
        var rest = s.Substring(14).Trim();
        if (rest.Length >= 5 && (rest[0] == '+' || rest[0] == '-') &&
            int.TryParse(rest.Substring(1, 2), out var hh) && int.TryParse(rest.Substring(3, 2), out var mm))
        {
            var off = new TimeSpan(hh, mm, 0);
            t = rest[0] == '+' ? t - off : t + off;
        }
        return DateTime.SpecifyKind(t, DateTimeKind.Utc);
    }

    public static Dictionary<string, List<Prog>> Parse(Stream data, HashSet<string> wanted)
    {
        var now = DateTime.UtcNow;
        var from = now.AddHours(-2);
        var to = now.AddHours(36);
        var result = new Dictionary<string, List<Prog>>();
        var settings = new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, IgnoreComments = true, IgnoreWhitespace = true };
        try
        {
            using var r = XmlReader.Create(data, settings);
            while (r.Read())
            {
                if (r.NodeType != XmlNodeType.Element || r.Name != "programme") continue;
                var ch = r.GetAttribute("channel");
                if (ch == null || !wanted.Contains(ch)) continue;
                var s = Time(r.GetAttribute("start"));
                var e = Time(r.GetAttribute("stop"));
                if (s == null || e == null || e <= from || s >= to) continue;
                string title = "", desc = "";
                if (!r.IsEmptyElement)
                {
                    int depth = r.Depth;
                    r.Read();
                    // ReadElementContentAsString already moves to the next node: no extra Read() after it
                    while (!r.EOF && !(r.NodeType == XmlNodeType.EndElement && r.Depth == depth))
                    {
                        if (r.NodeType == XmlNodeType.Element && r.Name == "title" && title.Length == 0)
                        {
                            try { title = r.ReadElementContentAsString().Trim(); } catch { r.Skip(); }
                            continue;
                        }
                        if (r.NodeType == XmlNodeType.Element && r.Name == "desc" && desc.Length == 0)
                        {
                            try { desc = r.ReadElementContentAsString().Trim(); } catch { r.Skip(); }
                            if (desc.Length > 400) desc = desc.Substring(0, 400);
                            continue;
                        }
                        r.Read();
                    }
                }
                if (!result.TryGetValue(ch, out var list)) result[ch] = list = new List<Prog>();
                list.Add(new Prog { Start = s.Value, Stop = e.Value, Title = title.Length == 0 ? "Sin título" : title, Desc = desc });
            }
        }
        catch { /* a cut file: keep what was read */ }
        foreach (var l in result.Values) l.Sort((a, b) => a.Start.CompareTo(b.Start));
        return result;
    }

    public static (Prog now, Prog next) NowNext(List<Prog> list, DateTime? at = null)
    {
        if (list == null || list.Count == 0) return (null, null);
        var t = at ?? DateTime.UtcNow;
        var i = list.FindIndex(p => p.Start <= t && p.Stop > t);
        if (i >= 0) return (list[i], i + 1 < list.Count ? list[i + 1] : null);
        return (null, list.FirstOrDefault(p => p.Start > t));
    }
}

// ---------------------------------------------------------------- network

public static class Net
{
    public static readonly HttpClient Client = Make(TimeSpan.FromSeconds(30));
    public static readonly HttpClient Long = Make(TimeSpan.FromSeconds(120));

    static HttpClient Make(TimeSpan timeout)
    {
        var h = new HttpClientHandler { AutomaticDecompression = System.Net.DecompressionMethods.All };
        var c = new HttpClient(h) { Timeout = timeout };
        c.DefaultRequestHeaders.UserAgent.ParseAdd(Mpv.UserAgent);
        return c;
    }

    public static async Task<byte[]> Get(string url, bool slow = false)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out _)) throw new Exception("Dirección inválida");
        using var resp = await (slow ? Long : Client).GetAsync(url);
        if (!resp.IsSuccessStatusCode) throw new Exception($"El servidor respondió {(int)resp.StatusCode}");
        return await resp.Content.ReadAsByteArrayAsync();
    }
}

// ---------------------------------------------------------------- movies & series (Xtream)

public class XtreamAccount
{
    public string Base { get; set; } = "";
    public string User { get; set; } = "";
    public string Pass { get; set; } = "";

    [JsonIgnore] public bool Valid => Base.StartsWith("http") && User.Length > 0 && Pass.Length > 0;
    string Root => Base.EndsWith("/") ? Base.TrimEnd('/') : Base;
    static string Enc(string s) => Uri.EscapeDataString(s);

    public string Api(string action, params (string, string)[] extra)
    {
        var s = $"{Root}/player_api.php?username={Enc(User)}&password={Enc(Pass)}&action={action}";
        foreach (var (k, v) in extra) s += $"&{k}={Enc(v)}";
        return s;
    }

    public string MovieUrl(string id, string ext) => $"{Root}/movie/{Enc(User)}/{Enc(Pass)}/{id}.{(string.IsNullOrEmpty(ext) ? "mp4" : ext)}";
    public string EpisodeUrl(string id, string ext) => $"{Root}/series/{Enc(User)}/{Enc(Pass)}/{id}.{(string.IsNullOrEmpty(ext) ? "mp4" : ext)}";

    /// <summary>Optional line in the M3U:  #XTREAM https://server:port usuario clave</summary>
    public static XtreamAccount FromPlaylist(string text)
    {
        foreach (var raw in text.Split('\n').Take(50))
        {
            var line = raw.Trim();
            if (!line.StartsWith("#XTREAM", StringComparison.OrdinalIgnoreCase)) continue;
            var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 4)
            {
                var acc = new XtreamAccount { Base = parts[1], User = parts[2], Pass = parts[3] };
                return acc.Valid ? acc : null;
            }
        }
        return null;
    }
}

public class Category
{
    public string Id = "";
    public string Name = "";
}

public class VodItem
{
    public bool Series { get; set; }
    public string StreamId { get; set; } = "";
    public string Title { get; set; } = "";
    public string Poster { get; set; } = "";
    public string Ext { get; set; } = "mp4";
    public string Year { get; set; } = "";
    public string Rating { get; set; } = "";
    public string CategoryId { get; set; } = "";
    [JsonIgnore] public string Id => (Series ? "s:" : "m:") + StreamId;
    string key;
    [JsonIgnore] public string SearchKey => key ??= Search.Normalize(Title);
}

public class Episode
{
    public string Id = "";
    public int Season;
    public int Number;
    public string Title = "";
    public string Ext = "mp4";
    public string Image = "";
}

/// <summary>Something played on demand; also what the history stores.</summary>
public class Watch
{
    public bool Episode { get; set; }
    public string StreamId { get; set; } = "";
    public string Ext { get; set; } = "mp4";
    public string Title { get; set; } = "";
    public string Subtitle { get; set; } = "";
    public string Poster { get; set; } = "";
    public string SeriesId { get; set; } = "";
    public double Position { get; set; }
    public double Duration { get; set; }
    public DateTime Updated { get; set; } = DateTime.UtcNow;
    [JsonIgnore] public string Id => (Episode ? "e:" : "m:") + StreamId;
    [JsonIgnore] public bool Finished => Duration > 0 && Position >= Duration * 0.95;
    public Watch Copy() => (Watch)MemberwiseClone();
}

public static class Xtream
{
    static string Str(JsonElement o, string k)
    {
        if (o.ValueKind != JsonValueKind.Object || !o.TryGetProperty(k, out var v)) return "";
        return v.ValueKind switch
        {
            JsonValueKind.String => v.GetString()?.Trim() ?? "",
            JsonValueKind.Number => v.GetRawText(),
            _ => ""
        };
    }

    static JsonElement Parse(byte[] data)
    {
        try { return JsonDocument.Parse(data).RootElement; }
        catch { throw new Exception("Respuesta inesperada del servidor"); }
    }

    static JsonElement Arr(byte[] data)
    {
        var r = Parse(data);
        if (r.ValueKind != JsonValueKind.Array) throw new Exception("Usuario o clave Xtream incorrectos");
        return r;
    }

    public static async Task<List<Category>> Categories(XtreamAccount acc, bool series)
    {
        var data = await Net.Get(acc.Api(series ? "get_series_categories" : "get_vod_categories"));
        return await Task.Run(() =>
        {
            var outList = new List<Category>();
            foreach (var o in Arr(data).EnumerateArray())
            {
                var id = Str(o, "category_id");
                if (id.Length == 0) continue;
                var name = Str(o, "category_name");
                outList.Add(new Category { Id = id, Name = name.Length == 0 ? id : name });
            }
            return outList;
        });
    }

    static readonly Regex YearRx = new("(19|20)\\d\\d");

    public static async Task<List<VodItem>> Items(XtreamAccount acc, bool series, string category)
    {
        var action = series ? "get_series" : "get_vod_streams";
        var url = category != null ? acc.Api(action, ("category_id", category)) : acc.Api(action);
        var data = await Net.Get(url, slow: category == null);
        return await Task.Run(() =>
        {
            var outList = new List<VodItem>();
            foreach (var o in Arr(data).EnumerateArray())
            {
                var id = Str(o, series ? "series_id" : "stream_id");
                if (id.Length == 0) continue;
                var date = new[] { "releaseDate", "release_date", "releasedate", "year" }.Select(k => Str(o, k)).FirstOrDefault(s => s.Length > 0) ?? "";
                var ym = YearRx.Match(date);
                var rating = Str(o, "rating");
                if (rating == "0" || rating == "0.0") rating = "";
                var ext = Str(o, "container_extension");
                var name = Str(o, "name");
                outList.Add(new VodItem
                {
                    Series = series, StreamId = id, Title = name.Length == 0 ? "Sin título" : name,
                    Poster = Str(o, series ? "cover" : "stream_icon"), Ext = ext.Length == 0 ? "mp4" : ext,
                    Year = ym.Success ? ym.Value : "", Rating = rating, CategoryId = Str(o, "category_id")
                });
            }
            return outList;
        });
    }

    public static async Task<List<Episode>> Episodes(XtreamAccount acc, string seriesId)
    {
        var data = await Net.Get(acc.Api("get_series_info", ("series_id", seriesId)));
        return await Task.Run(() =>
        {
            var root = Parse(data);
            var outList = new List<Episode>();
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("episodes", out var eps)) return outList;
            void Add(int season, JsonElement list)
            {
                int i = 0;
                foreach (var o in list.EnumerateArray())
                {
                    i++;
                    var id = Str(o, "id");
                    if (id.Length == 0) continue;
                    var num = int.TryParse(Str(o, "episode_num"), out var n) ? n : i;
                    var s = int.TryParse(Str(o, "season"), out var sn) ? sn : season;
                    var title = Str(o, "title");
                    var ext = Str(o, "container_extension");
                    var img = "";
                    if (o.TryGetProperty("info", out var info) && info.ValueKind == JsonValueKind.Object)
                    {
                        img = Str(info, "movie_image");
                        if (img.Length == 0) img = Str(info, "cover_big");
                    }
                    outList.Add(new Episode
                    {
                        Id = id, Season = s, Number = num, Title = title.Length == 0 ? $"Episodio {num}" : title,
                        Ext = ext.Length == 0 ? "mp4" : ext, Image = img
                    });
                }
            }
            if (eps.ValueKind == JsonValueKind.Object)
            {
                foreach (var p in eps.EnumerateObject())
                    if (p.Value.ValueKind == JsonValueKind.Array) Add(int.TryParse(p.Name, out var k) ? k : 0, p.Value);
            }
            else if (eps.ValueKind == JsonValueKind.Array)
            {
                int i = 0;
                foreach (var v in eps.EnumerateArray())
                {
                    i++;
                    if (v.ValueKind == JsonValueKind.Array) Add(i, v);
                }
            }
            return outList.OrderBy(e => e.Season).ThenBy(e => e.Number).ToList();
        });
    }
}

// ---------------------------------------------------------------- local storage (only on this PC)

public static class Store
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TVOD");
    public static string CacheDir { get { var d = Path.Combine(Dir, "cache"); Directory.CreateDirectory(d); return d; } }
    static readonly string PrefsFile = Path.Combine(Dir, "prefs.json");
    static Dictionary<string, string> prefs;
    static readonly JsonSerializerOptions Opts = new() { WriteIndented = false };

    static Dictionary<string, string> P
    {
        get
        {
            if (prefs != null) return prefs;
            Directory.CreateDirectory(Dir);
            try { prefs = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(PrefsFile)); } catch { }
            return prefs ??= new Dictionary<string, string>();
        }
    }

    static void SavePrefs()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var tmp = PrefsFile + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(P, Opts));
            File.Move(tmp, PrefsFile, true);
        }
        catch { }
    }

    public static string Get(string key, string def = null) => P.TryGetValue(key, out var v) ? v : def;
    public static void Set(string key, string value)
    {
        if (value == null) P.Remove(key); else P[key] = value;
        SavePrefs();
    }
    public static void Remove(string key) { if (P.Remove(key)) SavePrefs(); }
    public static int GetInt(string key, int def = 0) => int.TryParse(Get(key), out var v) ? v : def;
    public static bool GetBool(string key, bool def = false) => Get(key) is string s ? s == "1" : def;
    public static void SetBool(string key, bool v) => Set(key, v ? "1" : "0");
    public static IEnumerable<string> Keys => P.Keys.ToList();

    public static HashSet<string> GetSet(string key)
    {
        try { return JsonSerializer.Deserialize<HashSet<string>>(Get(key) ?? "[]") ?? new(); } catch { return new(); }
    }
    public static void SetSet(string key, HashSet<string> v) => Set(key, JsonSerializer.Serialize(v));

    public static T Load<T>(string name) where T : class
    {
        try { return JsonSerializer.Deserialize<T>(File.ReadAllText(Path.Combine(Dir, name + ".json"))); } catch { return null; }
    }

    public static void Save<T>(string name, T value, bool sync = true)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var f = Path.Combine(Dir, name + ".json");
            File.WriteAllText(f + ".tmp", JsonSerializer.Serialize(value, Opts));
            File.Move(f + ".tmp", f, true);
        }
        catch { }
        // the lists that travel with the Google account
        if (sync && (name == "history" || name == "later")) Cloud.Shared.MarkDirty();
    }
}

/// <summary>account.json next to TVOD.exe: the service already filled in (server, user, pass, list).</summary>
public static class Bundled
{
    public static XtreamAccount Account;
    public static string List;

    public static void Load()
    {
        try
        {
            var f = Path.Combine(AppContext.BaseDirectory, "account.json");
            if (!File.Exists(f)) return;
            var o = JsonDocument.Parse(File.ReadAllText(f)).RootElement;
            string S(string k) => o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()?.Trim() ?? "" : "";
            var acc = new XtreamAccount { Base = S("server"), User = S("user"), Pass = S("pass") };
            if (acc.Valid) Account = acc;
            var list = S("list");
            if (list.StartsWith("http")) List = list;
        }
        catch { }
    }
}
