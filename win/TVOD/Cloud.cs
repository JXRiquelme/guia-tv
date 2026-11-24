using System.Diagnostics;
using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;

namespace TVOD;

/// <summary>
/// Google account + Supabase: "Seguir viendo" (history), "Ver más tarde" and the option chosen for
/// each channel, the same on the TV, the phone, the Mac and this PC.
/// Cloud row (public.user_data), shared with the other apps:
///   history: [{episode,id,ext,title,subtitle,poster,seriesId,position ms,duration ms,updated epoch ms}]
///   later: [{series,id,title,poster,ext,year,rating,cat}]   channel_options: {channel name: option index}
/// </summary>
public sealed class Cloud
{
    public static readonly Cloud Shared = new();

    const string Url = "https://wojgakvfcxeuywcwsktw.supabase.co";
    const string Key = "sb_publishable_qiJFYfj_9JnfqBSa-VGuEA_eS4XkL0b";
    public const string Redirect = "aeriolite://login";

    public string Email => Store.Get("cloud_email");
    public bool SignedIn => Store.Get("cloud_refresh") != null;
    public bool Busy;
    public string LastError;
    public DateTime? LastOK;

    /// <summary>Called on the UI thread after another device's copy replaced the local lists.</summary>
    public Action OnApplied;
    /// <summary>Called on the UI thread whenever the account state or the sync result changes.</summary>
    public Action OnStatus;
    Action<string> pendingSignIn;

    readonly SemaphoreSlim gate = new(1, 1);
    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(20) };
    System.Windows.Threading.DispatcherTimer pushTimer;
    System.Windows.Threading.DispatcherTimer pollTimer;

    bool Dirty { get => Store.GetBool("cloud_dirty"); set => Store.SetBool("cloud_dirty", value); }

    static void Ui(Action a) => Application.Current?.Dispatcher.BeginInvoke(a);

    /// <summary>While the app is in front: look for changes from other devices every 20 s (a tiny request).</summary>
    public void StartPolling()
    {
        pollTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(20) };
        pollTimer.Tick += (_, _) => { if (Application.Current?.MainWindow?.IsActive == true || AnyActive()) Refresh(); };
        pollTimer.Start();
    }

    static bool AnyActive() => Application.Current.Windows.Cast<Window>().Any(w => w.IsActive);

    // ------------------------------------------------------------ sign in / out

    public void SignIn(Action<string> done)
    {
        pendingSignIn = done;
        var u = $"{Url}/auth/v1/authorize?provider=google&redirect_to={Uri.EscapeDataString(Redirect)}";
        try { Process.Start(new ProcessStartInfo(u) { UseShellExecute = true }); }
        catch (Exception e) { pendingSignIn = null; done($"No se pudo abrir el navegador: {e.Message}"); }
    }

    /// <summary>The browser came back to aeriolite://login#access_token=… (through Windows).</summary>
    public async void Complete(string url)
    {
        var done = pendingSignIn ?? (_ => { });
        pendingSignIn = null;
        var i = url.IndexOfAny(new[] { '#', '?' });
        var p = new Dictionary<string, string>();
        if (i >= 0)
            foreach (var pair in url.Substring(i + 1).Split('&'))
            {
                var kv = pair.Split('=', 2);
                if (kv.Length == 2) p[Uri.UnescapeDataString(kv[0])] = Uri.UnescapeDataString(kv[1].Replace('+', ' '));
            }
        if (!p.TryGetValue("access_token", out var access) || !p.TryGetValue("refresh_token", out var refresh))
        {
            done(p.TryGetValue("error_description", out var ed) ? $"No se pudo iniciar sesión: {ed}" : "No se pudo iniciar sesión.");
            return;
        }
        SaveSession(access, refresh, double.TryParse(p.GetValueOrDefault("expires_in"), out var ex) ? ex : 3600);
        Busy = true;
        OnStatus?.Invoke();
        string err = null;
        await gate.WaitAsync();
        try
        {
            var user = JsonNode.Parse(await Request("/auth/v1/user", HttpMethod.Get, true));
            Store.Set("cloud_user", user?["id"]?.GetValue<string>());
            Store.Set("cloud_email", user?["email"]?.GetValue<string>());
            await FirstSync();
        }
        catch (Exception e) { err = $"No se pudo iniciar sesión: {e.Message}"; }
        finally { gate.Release(); }
        Busy = false;
        if (err == null) { LastOK = DateTime.Now; LastError = null; }
        OnStatus?.Invoke();
        done(err);
    }

    public void SignOut()
    {
        foreach (var k in new[] { "cloud_access", "cloud_refresh", "cloud_expires", "cloud_email", "cloud_user", "cloud_stamp", "cloud_dirty" })
            Store.Remove(k);
        OnStatus?.Invoke();
    }

    // ------------------------------------------------------------ sync

    /// <summary>A list or a channel option changed here: send it a moment later.</summary>
    public void MarkDirty()
    {
        if (!SignedIn) return;
        Dirty = true;
        Ui(() =>
        {
            pushTimer?.Stop();
            pushTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            pushTimer.Tick += (_, _) => { pushTimer.Stop(); _ = Run(Push); };
            pushTimer.Start();
        });
    }

    public void Refresh()
    {
        if (!SignedIn) return;
        _ = Run(PullOrPush);
    }

    public Task<string> SyncNow() => Run(PullOrPush);

    /// <summary>Flushes a pending change (closing the app).</summary>
    public void Flush()
    {
        if (!SignedIn || !Dirty) return;
        try { Task.Run(async () => { await gate.WaitAsync(); try { await Push(); } finally { gate.Release(); } }).Wait(5000); } catch { }
    }

    async Task<string> Run(Func<Task> block)
    {
        if (!await gate.WaitAsync(0)) return null;   // one at a time; a skipped poll is not a problem
        try
        {
            await block();
            Ui(() => { LastOK = DateTime.Now; LastError = null; OnStatus?.Invoke(); });
            return null;
        }
        catch (Exception e)
        {
            var m = e.Message;
            Ui(() => { LastError = m; OnStatus?.Invoke(); });
            return m;
        }
        finally { gate.Release(); }
    }

    async Task PullOrPush()
    {
        // cheap check first: only the date of the last save
        if (!Dirty)
        {
            var head = JsonNode.Parse(await Request("/rest/v1/user_data?select=updated_at", HttpMethod.Get, true)) as JsonArray;
            var s = head?.FirstOrDefault()?["updated_at"]?.GetValue<string>();
            if (s != null && s == Store.Get("cloud_stamp")) return;
        }
        var remote = await Fetch();
        var stamp = Store.Get("cloud_stamp") ?? "";
        if (remote == null) { await Push(); return; }
        var changed = (remote["updated_at"]?.GetValue<string>() ?? "") != stamp;
        if (changed && !Dirty) Apply(remote);
        else if (changed && Dirty) { Apply(Merge(remote)); await Push(); }
        else if (Dirty) await Push();
    }

    async Task FirstSync()
    {
        var r = await Fetch();
        if (r != null) Apply(Merge(r));
        await Push();
    }

    async Task<JsonObject> Fetch()
    {
        var a = JsonNode.Parse(await Request("/rest/v1/user_data?select=history,later,channel_options,updated_at", HttpMethod.Get, true)) as JsonArray;
        return a?.FirstOrDefault() as JsonObject;
    }

    async Task Push()
    {
        var user = Store.Get("cloud_user");
        if (user == null) return;
        var doc = Local();
        doc["user_id"] = user;
        var now = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
        doc["updated_at"] = now;
        var body = new JsonArray(doc).ToJsonString();
        var saved = JsonNode.Parse(await Request("/rest/v1/user_data?on_conflict=user_id", HttpMethod.Post, true, body,
            "resolution=merge-duplicates,return=representation")) as JsonArray;
        Store.Set("cloud_stamp", saved?.FirstOrDefault()?["updated_at"]?.GetValue<string>() ?? now);
        Dirty = false;
    }

    // ------------------------------------------------------------ local <-> cloud format

    static long Ms(DateTime t) => (long)(t.ToUniversalTime() - DateTime.UnixEpoch).TotalMilliseconds;

    JsonObject Local()
    {
        var history = new JsonArray();
        foreach (var w in Store.Load<List<Watch>>("history") ?? new())
            history.Add(new JsonObject
            {
                ["episode"] = w.Episode, ["id"] = w.StreamId, ["ext"] = w.Ext, ["title"] = w.Title, ["subtitle"] = w.Subtitle,
                ["poster"] = w.Poster, ["seriesId"] = w.SeriesId, ["position"] = (long)(w.Position * 1000),
                ["duration"] = (long)(w.Duration * 1000), ["updated"] = Ms(w.Updated)
            });
        var later = new JsonArray();
        foreach (var v in Store.Load<List<VodItem>>("later") ?? new())
            later.Add(new JsonObject
            {
                ["series"] = v.Series, ["id"] = v.StreamId, ["title"] = v.Title, ["poster"] = v.Poster, ["ext"] = v.Ext,
                ["year"] = v.Year, ["rating"] = v.Rating, ["cat"] = v.CategoryId
            });
        var options = new JsonObject();
        foreach (var k in Store.Keys.Where(k => k.StartsWith("alt_")))
            options[k.Substring(4)] = Store.GetInt(k);
        return new JsonObject { ["history"] = history, ["later"] = later, ["channel_options"] = options };
    }

    static string S(JsonNode n, string k)
    {
        var v = n?[k];
        if (v == null) return "";
        try
        {
            return v.GetValueKind() switch
            {
                JsonValueKind.String => v.GetValue<string>(),
                JsonValueKind.Number => v.ToJsonString(),
                _ => ""
            };
        }
        catch { return ""; }
    }

    static double D(JsonNode n, string k) => double.TryParse(S(n, k), NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : 0;
    static bool B(JsonNode n, string k) { try { return n?[k]?.GetValue<bool>() ?? false; } catch { return false; } }

    JsonObject Merge(JsonObject remote)
    {
        var mine = Local();
        var byKey = new Dictionary<string, JsonNode>();
        void Add(JsonNode list)
        {
            if (list is not JsonArray a) return;
            foreach (var o in a)
            {
                if (o == null) continue;
                var k = (B(o, "episode") ? "e:" : "m:") + S(o, "id");
                var upd = D(o, "updated");
                if (byKey.TryGetValue(k, out var old) && D(old, "updated") >= upd) continue;
                byKey[k] = o;
            }
        }
        Add(remote["history"]);
        Add(mine["history"]);
        var history = new JsonArray(byKey.Values.OrderByDescending(o => D(o, "updated")).Take(60).Select(o => o.DeepClone()).ToArray());
        var later = new JsonArray();
        var seen = new HashSet<string>();
        foreach (var list in new[] { remote["later"], mine["later"] })
            if (list is JsonArray a)
                foreach (var o in a)
                    if (o != null && seen.Add(B(o, "series") + S(o, "id"))) later.Add(o.DeepClone());
        var options = (mine["channel_options"] as JsonObject)?.DeepClone() as JsonObject ?? new JsonObject();
        if (remote["channel_options"] is JsonObject ro)
            foreach (var kv in ro) options[kv.Key] = kv.Value?.DeepClone();
        return new JsonObject
        {
            ["history"] = history, ["later"] = later, ["channel_options"] = options,
            ["updated_at"] = remote["updated_at"]?.DeepClone() ?? ""
        };
    }

    void Apply(JsonObject doc)
    {
        var history = new List<Watch>();
        if (doc["history"] is JsonArray ha)
            foreach (var o in ha)
            {
                if (o == null) continue;
                var ext = S(o, "ext");
                history.Add(new Watch
                {
                    Episode = B(o, "episode"), StreamId = S(o, "id"), Ext = ext.Length == 0 ? "mp4" : ext, Title = S(o, "title"),
                    Subtitle = S(o, "subtitle"), Poster = S(o, "poster"), SeriesId = S(o, "seriesId"),
                    Position = D(o, "position") / 1000, Duration = D(o, "duration") / 1000,
                    Updated = DateTime.UnixEpoch.AddMilliseconds(D(o, "updated"))
                });
            }
        var later = new List<VodItem>();
        if (doc["later"] is JsonArray la)
            foreach (var o in la)
            {
                if (o == null) continue;
                later.Add(new VodItem
                {
                    Series = B(o, "series"), StreamId = S(o, "id"), Title = S(o, "title"), Poster = S(o, "poster"),
                    Ext = S(o, "ext"), Year = S(o, "year"), Rating = S(o, "rating"), CategoryId = S(o, "cat")
                });
            }
        var options = new Dictionary<string, int>();
        if (doc["channel_options"] is JsonObject co)
            foreach (var kv in co)
                if (int.TryParse(kv.Value?.ToJsonString(), out var n)) options[kv.Key] = n;
        var stamp = S(doc, "updated_at");
        Ui(() =>
        {
            Store.Save("history", history, sync: false);
            Store.Save("later", later, sync: false);
            foreach (var k in Store.Keys.Where(k => k.StartsWith("alt_"))) Store.Remove(k);
            foreach (var kv in options) Store.Set("alt_" + kv.Key, kv.Value.ToString(CultureInfo.InvariantCulture));
            if (stamp.Length > 0) Store.Set("cloud_stamp", stamp);
            OnApplied?.Invoke();
        });
    }

    // ------------------------------------------------------------ Supabase REST

    void SaveSession(string access, string refresh, double expiresIn)
    {
        Store.Set("cloud_access", access);
        Store.Set("cloud_refresh", refresh);
        Store.Set("cloud_expires", (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + (long)expiresIn).ToString(CultureInfo.InvariantCulture));
    }

    async Task<string> Token()
    {
        var refresh = Store.Get("cloud_refresh") ?? throw new Exception("Sin sesión");
        long.TryParse(Store.Get("cloud_expires"), out var exp);
        var access = Store.Get("cloud_access");
        if (DateTimeOffset.UtcNow.ToUnixTimeSeconds() < exp - 60 && access != null) return access;
        var body = new JsonObject { ["refresh_token"] = refresh }.ToJsonString();
        var r = JsonNode.Parse(await Request("/auth/v1/token?grant_type=refresh_token", HttpMethod.Post, false, body));
        var a = r?["access_token"]?.GetValue<string>();
        var nr = r?["refresh_token"]?.GetValue<string>();
        if (a == null || nr == null) throw new Exception("Sesión vencida");
        SaveSession(a, nr, D(r, "expires_in") is var e && e > 0 ? e : 3600);
        return a;
    }

    async Task<string> Request(string path, HttpMethod method, bool auth, string body = null, string prefer = null)
    {
        using var req = new HttpRequestMessage(method, Url + path);
        // the publishable key goes only as apikey; Authorization carries the user's session
        req.Headers.TryAddWithoutValidation("apikey", Key);
        if (auth) req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + await Token());
        if (prefer != null) req.Headers.TryAddWithoutValidation("Prefer", prefer);
        if (body != null) req.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var resp = await http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        if (!resp.IsSuccessStatusCode)
        {
            string msg = null;
            try
            {
                var o = JsonNode.Parse(text);
                msg = S(o, "msg"); if (msg.Length == 0) msg = S(o, "message"); if (msg.Length == 0) msg = S(o, "error_description");
            }
            catch { }
            throw new Exception(string.IsNullOrEmpty(msg) ? $"HTTP {(int)resp.StatusCode}" : msg);
        }
        return text.Length == 0 ? "[]" : text;
    }
}
