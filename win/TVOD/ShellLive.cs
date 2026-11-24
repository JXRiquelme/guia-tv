using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace TVOD;

public partial class Shell
{
    List<Channel> channels = new();
    int current;
    Dictionary<string, List<Prog>> guide = new();
    bool guideLoaded;
    HashSet<string> hiddenChannels = Store.GetSet("hide_ch");
    XtreamAccount playlistAccount;
    int? altOverride;
    int playToken;
    DispatcherTimer zapTimer;

    string ListUrl => Store.Get("list") ?? Bundled.List ?? "";
    static string ListFile => System.IO.Path.Combine(Store.CacheDir, "list.m3u");
    static string GuideFile => System.IO.Path.Combine(Store.CacheDir, "guia.xml");

    Channel CurrentChannel => current >= 0 && current < channels.Count ? channels[current] : null;
    List<Prog> Programs(Channel ch) => ch != null && guide.TryGetValue(ch.TvgId, out var l) ? l : null;

    // ------------------------------------------------------------ live data

    void StartLive()
    {
        if (string.IsNullOrEmpty(ListUrl)) { OpenSetup(null); return; }
        var text = File.Exists(ListFile) ? File.ReadAllText(ListFile) : "";
        var cached = Playlist.Parse(text);
        playlistAccount = XtreamAccount.FromPlaylist(text);
        if (cached.Count > 0)
        {
            channels = cached;
            Play(StartIndex());
            RefreshGuide(false);
            RefreshList(true);
        }
        else
        {
            Status("Cargando canales…");
            RefreshList(false);
        }
    }

    int StartIndex()
    {
        // reopens on the last channel watched (its chosen option is remembered per channel)
        var last = Store.Get("last_channel");
        var i = last != null ? channels.FindIndex(c => c.Name == last) : -1;
        if (i >= 0) return i;
        i = channels.FindIndex(c => string.Equals(c.Name, StartChannel, StringComparison.OrdinalIgnoreCase));
        if (i >= 0) return i;
        i = channels.FindIndex(c => c.Name.Contains(StartChannel, StringComparison.OrdinalIgnoreCase));
        return Math.Max(0, i);
    }

    async void RefreshList(bool silent)
    {
        byte[] data;
        try { data = await Net.Get(ListUrl); }
        catch (Exception e)
        {
            if (!silent) { Status(null); OpenSetup($"No se pudo descargar la lista ({e.Message})."); }
            return;
        }
        var text = Encoding.UTF8.GetString(data);
        var list = Playlist.Parse(text);
        playlistAccount = XtreamAccount.FromPlaylist(text);
        if (list.Count == 0)
        {
            if (!silent) { Status(null); OpenSetup("La dirección no contiene una lista M3U válida."); }
            return;
        }
        try { File.WriteAllBytes(ListFile, data); } catch { }
        var same = list.Count == channels.Count && list.Zip(channels).All(p => p.First.Url == p.Second.Url && p.First.Name == p.Second.Name
            && p.First.Alts.Count == p.Second.Alts.Count);
        if (same) return;
        var playing = CurrentChannel;
        channels = list;
        RefreshGuide(false);
        var keep = playing != null ? list.FindIndex(c => c.Url == playing.Url || c.Name == playing.Name) : -1;
        if (keep >= 0) current = keep;
        else if (vod == null && !browserOpen) { Status(null); Play(StartIndex()); }
        else current = StartIndex();
        if (Visible(panel)) RenderPanel();
    }

    async void RefreshGuide(bool force)
    {
        var wanted = new HashSet<string>(channels.Select(c => c.TvgId).Where(t => t.Length > 0));
        if (wanted.Count == 0) return;
        var file = GuideFile;
        var age = File.Exists(file) ? (DateTime.Now - File.GetLastWriteTime(file)).TotalHours : double.MaxValue;
        if (force || age > 3)
        {
            try
            {
                var data = await Net.Get(GuideUrl, slow: true);
                if (data.Length > 0) await File.WriteAllBytesAsync(file, data);
            }
            catch { }
        }
        if (!File.Exists(file)) return;
        var map = await Task.Run(() =>
        {
            try { using var s = File.OpenRead(file); return GuideParser.Parse(s, wanted); }
            catch { return new Dictionary<string, List<Prog>>(); }
        });
        guide = map;
        guideLoaded = true;
        UpdateInfo();
        if (Visible(panel)) RenderPanel();
        if (Visible(epg)) RenderEpg();
    }

    List<Channel> VisibleChannels
    {
        get
        {
            var q = Search.Normalize(panelSearch.Text.Trim());
            return channels.Where(c => !hiddenChannels.Contains(c.Name) && (q.Length == 0 || Search.Normalize(c.Name).Contains(q))).ToList();
        }
    }

    // ------------------------------------------------------------ live playback

    void Play(int index)
    {
        if (channels.Count == 0 || mpv == null) return;
        zapTimer?.Stop();
        SaveVodProgress();
        vod = null;
        Show(vodBar, false);
        Show(shutter, true);
        mpv.SetAspect("no");
        current = Math.Clamp(index, 0, channels.Count - 1);
        recoverAttempts = 0;
        stallSince = null;
        altOverride = null;
        playToken++;
        Status(null);
        ApplyLanguages();
        Store.Set("last_channel", channels[current].Name);
        mpv.Load(LiveUrl());
        FlashInfo(false);
    }

    int AltIndex(Channel ch) => Math.Clamp(Store.GetInt("alt_" + ch.Name), 0, ch.Alts.Count);

    string LiveUrl()
    {
        var ch = CurrentChannel;
        if (ch == null) return "";
        var opts = ch.Options;
        if (altOverride is int o && o >= 0 && o < opts.Count) return opts[o].Url;
        return opts[AltIndex(ch)].Url;
    }

    void ChooseOption(int i)
    {
        var ch = CurrentChannel;
        if (vod != null || ch == null || i < 0 || i >= ch.Options.Count) return;
        if (i != AltIndex(ch))
        {
            Store.Set("alt_" + ch.Name, i.ToString());
            Cloud.Shared.MarkDirty();
            Play(current);
            Toast($"{ch.Name}  ·  {ch.Options[i].Label}");
        }
        else FlashInfo(true);
    }

    /// <summary>"O" key: next option of the current channel.</summary>
    void NextOption()
    {
        var ch = CurrentChannel;
        if (ch == null || ch.Alts.Count == 0) { Toast("Este canal tiene una sola opción."); return; }
        ChooseOption((AltIndex(ch) + 1) % ch.Options.Count);
    }

    int Neighbor(int step)
    {
        var order = Enumerable.Range(0, channels.Count).Where(i => !hiddenChannels.Contains(channels[i].Name)).ToList();
        if (order.Count == 0) order = Enumerable.Range(0, channels.Count).ToList();
        var pos = order.IndexOf(current);
        if (pos < 0) return order.FirstOrDefault(i => i > current, order[0]);
        return order[((pos + step) % order.Count + order.Count) % order.Count];
    }

    /// <summary>↑↓ zapping: the banner follows each press, the stream opens when presses stop.</summary>
    void Zap(int step)
    {
        if (channels.Count == 0 || vod != null) return;
        current = Neighbor(step);
        mpv?.Stop();
        Show(shutter, true);
        Status(null);
        FlashInfo(false);
        zapTimer?.Stop();
        var target = current;
        zapTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(0.6) };
        zapTimer.Tick += (_, _) => { zapTimer.Stop(); Play(target); };
        zapTimer.Start();
    }

    void PlayChannel(Channel ch)
    {
        var i = channels.FindIndex(c => c.Number == ch.Number);
        if (i < 0) return;
        ClosePanel();
        CloseEpg();
        if (i != current || vod != null || mpv?.Get("path") == null) Play(i); else FlashInfo(false);
    }

    void ToggleHiddenChannel(string name)
    {
        if (!hiddenChannels.Remove(name)) hiddenChannels.Add(name);
        Store.SetSet("hide_ch", hiddenChannels);
    }

    // ------------------------------------------------------------ self-healing playback
    // Checked every second: if the picture is stuck (buffering / not moving) for a few seconds,
    // the stream is reopened quietly; every 3rd try a live channel moves to its next option.

    DateTime? stallSince;
    double lastPos = -1;
    int recoverAttempts;
    bool recovering;

    void HealthTick()
    {
        UpdateStats();
        if (vod != null) UpdateVodBar();
        if (mpv == null || recovering || vod != null || CurrentChannel == null || mpv.Paused || browserOpen || dialogShowsSetup)
        { stallSince = null; return; }
        if (zapTimer?.IsEnabled == true) { stallSince = null; return; }
        var pos = mpv.Position;
        var moving = Math.Abs(pos - lastPos) > 0.05;
        lastPos = pos;
        if (moving && !mpv.Buffering) { stallSince = null; return; }
        var now = DateTime.Now;
        stallSince ??= now;
        double limit = recoverAttempts == 0 ? 10 : 6;
        if ((now - stallSince.Value).TotalSeconds >= limit) Recover();
    }

    void Recover()
    {
        recoverAttempts++;
        stallSince = DateTime.Now;
        recovering = true;
        var token = playToken;
        if (vod != null && Xtream() is XtreamAccount acc)
        {
            var w = vod;
            var pos = mpv.Position > 1 ? mpv.Position : w.Position;
            if (recoverAttempts >= 2) Status("Recuperando la conexión…");
            mpv.Stop();
            After(1.5, () =>
            {
                if (token != playToken) return;
                recovering = false;
                mpv.Load(VodUrl(acc, w, vodHls ? "m3u8" : w.Ext), pos);
            });
            return;
        }
        var ch = CurrentChannel;
        if (ch == null) { recovering = false; return; }
        if (recoverAttempts % 3 == 0 && ch.Alts.Count > 0)
            altOverride = ((altOverride ?? AltIndex(ch)) + 1) % ch.Options.Count;
        if (recoverAttempts >= 3 * ch.Options.Count + 3) Status($"Sin señal en {ch.Name}.\nSigue intentando… o prueba otro canal.");
        else if (recoverAttempts >= 3) Status("Recuperando la señal…");
        mpv.Stop();   // frees the (single) connection
        After(recoverAttempts > 6 ? 4 : 0.8, () =>
        {
            if (token != playToken || vod != null) return;
            recovering = false;
            mpv.Load(LiveUrl());
        });
    }

    void PlaybackReady()
    {
        recoverAttempts = 0;
        stallSince = null;
        Status(null);
        Show(shutter, false);
    }

    void PlaybackEnded(bool error, bool eof)
    {
        if (vod != null)
        {
            var w = vod;
            if (eof)
            {
                var done = w.Copy();
                done.Position = mpv.Duration > 0 ? mpv.Duration : w.Duration;
                done.Duration = done.Position;
                RecordHistory(done);
                var i = w.Episode && openSeries != null ? episodes.FindIndex(e => e.Id == w.StreamId) : -1;
                if (i >= 0 && i + 1 < episodes.Count) PlayVod(EpisodeWatch(openSeries, episodes[i + 1]));
                else LeaveVod();
            }
            else if (error && vodHls && !plainTs.Contains(w.Id))
            {
                // the service has no HLS copy of this title: the .ts file it is
                plainTs.Add(w.Id);
                PlayVod(w);
            }
            else if (error && !recovering) Recover();
        }
        else if ((error || eof) && !recovering && !browserOpen) Recover();
    }

    // ------------------------------------------------------------ channel banner

    Border info;
    Border infoLogo;
    TextBlock infoNumber, infoName, infoOption, infoNow, infoNext, infoClock;
    Grid infoProgress;
    StackPanel infoNowRow;

    void BuildInfo()
    {
        infoNumber = Ui.Txt("", 26, Ui.AccentLight, true);
        infoName = Ui.Txt("", 26, Ui.White, true);
        infoOption = Ui.Txt("", 14, Ui.Faint);
        infoOption.VerticalAlignment = VerticalAlignment.Bottom;
        infoOption.Margin = new Thickness(0, 0, 0, 4);
        infoNow = Ui.Txt("", 17, Ui.White);
        infoNext = Ui.Txt("", 15, Ui.Faint);
        infoClock = Ui.Txt("", 22, Ui.Dim, true);
        infoProgress = Ui.Progress(0, 360);
        infoLogo = new Border { Width = 132, Height = 76, Margin = new Thickness(0, 0, 22, 0) };
        infoNowRow = Ui.H(14, infoNow, infoProgress);
        infoProgress.VerticalAlignment = VerticalAlignment.Center;
        var texts = Ui.V(6, Ui.H(12, infoNumber, infoName, infoOption), infoNowRow, infoNext);
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(infoLogo);
        Grid.SetColumn(texts, 1);
        g.Children.Add(texts);
        infoClock.VerticalAlignment = VerticalAlignment.Top;
        Grid.SetColumn(infoClock, 2);
        g.Children.Add(infoClock);
        info = Ui.Box(g, Ui.Glass, 18, 20);
        info.VerticalAlignment = VerticalAlignment.Bottom;
        info.Margin = new Thickness(28, 0, 28, 28);
        info.Visibility = Visibility.Collapsed;
        info.MouseLeftButtonDown += (_, e) => { e.Handled = true; OpenEpg(); };
        root.Children.Add(info);
    }

    string infoLogoUrl;

    void UpdateInfo()
    {
        var ch = CurrentChannel;
        if (ch == null || info == null) return;
        infoNumber.Text = ch.Number.ToString();
        infoName.Text = ch.Name;
        infoOption.Text = ch.Alts.Count > 0 ? (altOverride is int o && o < ch.Options.Count ? ch.Options[o].Label + " (automática)" : ch.Options[AltIndex(ch)].Label) + "  ·  O cambia" : "";
        if (infoLogoUrl != ch.Logo)
        {
            infoLogoUrl = ch.Logo;
            infoLogo.Child = Ui.Poster(ch.Logo, 132, 76, 8, Stretch.Uniform);
            ((Border)infoLogo.Child).Background = Brushes.Transparent;
        }
        var (now, next) = GuideParser.NowNext(Programs(ch));
        if (now != null)
        {
            infoNow.Text = $"{Ui.HHmm(now.Start)} – {Ui.HHmm(now.Stop)}   {now.Title}";
            var frac = (DateTime.UtcNow - now.Start).TotalSeconds / Math.Max(1, (now.Stop - now.Start).TotalSeconds);
            infoNowRow.Children.Remove(infoProgress);
            infoProgress = Ui.Progress(frac, 260);
            infoProgress.VerticalAlignment = VerticalAlignment.Center;
            infoProgress.Margin = new Thickness(14, 0, 0, 0);
            infoNowRow.Children.Add(infoProgress);
        }
        else
        {
            infoNow.Text = guideLoaded ? "Sin información de programación" : "Cargando guía…";
            infoProgress.Visibility = Visibility.Collapsed;
        }
        infoNext.Text = next != null ? $"Luego: {Ui.HHmm(next.Start)}  {next.Title}" : "";
        infoClock.Text = DateTime.Now.ToString("HH:mm");
    }

    void FlashInfo(bool withButtons)
    {
        if (vod != null || browserOpen) return;
        UpdateInfo();
        Show(info, !main.Mini);
        if (withButtons) ShowTop(true);
        infoTimer.Stop();
        infoTimer.Interval = TimeSpan.FromSeconds(withButtons ? 6 : 5);
        infoTimer.Start();
    }

    void HideInfo()
    {
        Show(info, false);
        if (vod == null) ShowTop(false);
    }

    // ------------------------------------------------------------ channel list (left)

    Border panel;
    TextBox panelSearch;
    ListBox panelList;
    TextBlock panelTitle;
    int panelSelection;
    List<Channel> panelItems = new();

    void BuildPanel()
    {
        panelTitle = Ui.Txt("Canales", 24, Ui.White, true);
        panelSearch = new TextBox { Margin = new Thickness(0, 12, 0, 10) };
        panelSearch.TextChanged += (_, _) => { panelSelection = 0; RenderPanel(); };
        var hint = Ui.Txt("Buscar canal…", 16, Ui.Faint);
        hint.IsHitTestVisible = false;
        hint.Margin = new Thickness(14, 12, 0, 10);
        hint.VerticalAlignment = VerticalAlignment.Center;
        panelSearch.TextChanged += (_, _) => Show(hint, panelSearch.Text.Length == 0);
        var searchGrid = new Grid();
        searchGrid.Children.Add(panelSearch);
        searchGrid.Children.Add(hint);
        panelList = Ui.List();
        panelList.PreviewMouseLeftButtonUp += (_, e) =>
        {
            if (ItemsControl.ContainerFromElement(panelList, (DependencyObject)e.OriginalSource) is ListBoxItem it
                && panelList.ItemContainerGenerator.IndexFromContainer(it) is int i && i >= 0 && i < panelItems.Count)
                PlayChannel(panelItems[i]);
        };
        var g = new Grid();
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        g.Children.Add(panelTitle);
        Grid.SetRow(searchGrid, 1);
        g.Children.Add(searchGrid);
        Grid.SetRow(panelList, 2);
        g.Children.Add(panelList);
        panel = new Border
        {
            Child = g, Background = Ui.PanelBg, Width = 470, HorizontalAlignment = HorizontalAlignment.Left,
            Padding = new Thickness(18, 20, 10, 12), Visibility = Visibility.Collapsed
        };
        panel.MouseLeftButtonDown += (_, e) => e.Handled = true;
        root.Children.Add(panel);
    }

    void OpenPanel()
    {
        if (channels.Count == 0) return;
        CloseEpg();
        HideInfo();
        panelSearch.Text = "";
        Show(panel, true);
        RenderPanel();
        var i = panelItems.FindIndex(c => c.Number == CurrentChannel?.Number);
        panelSelection = Math.Max(0, i);
        SelectPanel();
        Keyboard.Focus(this);
    }

    void ClosePanel() { if (Visible(panel)) { Show(panel, false); Keyboard.Focus(this); } }
    void TogglePanel() { if (Visible(panel)) ClosePanel(); else OpenPanel(); }

    void RenderPanel()
    {
        panelItems = VisibleChannels;
        panelTitle.Text = $"Canales  ·  {panelItems.Count}";
        var now = DateTime.UtcNow;
        panelList.ItemsSource = panelItems.Select(ch => new Row(() => ChannelRow(ch, now), ch)).ToList();
        SelectPanel();
    }

    void SelectPanel()
    {
        if (panelItems.Count == 0) return;
        panelSelection = Math.Clamp(panelSelection, 0, panelItems.Count - 1);
        panelList.SelectedIndex = panelSelection;
        panelList.ScrollIntoView(panelList.SelectedItem);
    }

    void MovePanel(int step)
    {
        panelSelection += step;
        SelectPanel();
    }

    void PlaySelectedPanel()
    {
        if (panelSelection >= 0 && panelSelection < panelItems.Count) PlayChannel(panelItems[panelSelection]);
    }

    FrameworkElement ChannelRow(Channel ch, DateTime now)
    {
        var (p, _) = GuideParser.NowNext(Programs(ch), now);
        var playing = ch.Number == CurrentChannel?.Number && vod == null;
        var num = Ui.Txt(ch.Number.ToString(), 15, playing ? Ui.AccentLight : Ui.Faint, true);
        num.Width = 38;
        num.VerticalAlignment = VerticalAlignment.Center;
        var logo = Ui.Poster(ch.Logo, 78, 44, 6, Stretch.Uniform);
        logo.Background = Brushes.Transparent;
        var name = Ui.Txt(ch.Name, 16, playing ? Ui.AccentLight : Ui.White, true);
        var now1 = Ui.Txt(p != null ? $"{Ui.HHmm(p.Start)}  {p.Title}" : "", 13, Ui.Faint);
        var texts = Ui.V(2, name, now1);
        texts.VerticalAlignment = VerticalAlignment.Center;
        texts.Margin = new Thickness(12, 0, 6, 0);
        var g = new Grid { Height = 60, Background = Brushes.Transparent };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.Children.Add(num);
        Grid.SetColumn(logo, 1);
        g.Children.Add(logo);
        Grid.SetColumn(texts, 2);
        g.Children.Add(texts);
        g.Margin = new Thickness(8, 0, 4, 0);
        var menu = new ContextMenu();
        var hide = new MenuItem { Header = "Ocultar este canal" };
        hide.Click += (_, _) => { ToggleHiddenChannel(ch.Name); RenderPanel(); };
        menu.Items.Add(hide);
        if (ch.Alts.Count > 0 && ch.Number == CurrentChannel?.Number)
        {
            var opt = new MenuItem { Header = "Opciones de señal…" };
            opt.Click += (_, _) => ShowOptionsMenu();
            menu.Items.Add(opt);
        }
        g.ContextMenu = menu;
        return g;
    }

    // ------------------------------------------------------------ guide (right)

    Border epg;
    ListBox epgList;
    Canvas epgRuler;
    TextBlock epgTitle, epgDetail, epgDetailTime;
    int epgRow;
    DateTime epgCursor = DateTime.UtcNow;
    DateTime epgWindowStart = DateTime.UtcNow;
    const double EpgNameW = 200;
    const double EpgSpanHours = 3;

    List<Channel> EpgRows
    {
        get
        {
            var r = channels.Where(c => !hiddenChannels.Contains(c.Name)).ToList();
            return r.Count == 0 ? channels : r;
        }
    }

    void BuildEpg()
    {
        epgTitle = Ui.Txt("Guía", 24, Ui.White, true);
        epgDetail = Ui.Txt("", 15, Ui.Dim, false, 3);
        epgDetailTime = Ui.Txt("", 18, Ui.White, true);
        var prev = Ui.Btn("◀  Antes", () => { epgWindowStart = epgWindowStart.AddHours(-1); RenderEpg(); }, null, 14);
        var next = Ui.Btn("Después  ▶", () => { epgWindowStart = epgWindowStart.AddHours(1); RenderEpg(); }, null, 14);
        var nowB = Ui.Btn("Ahora", () => { epgCursor = DateTime.UtcNow; ResetEpgWindow(); RenderEpg(); }, null, 14);
        var buttons = Ui.H(8, prev, nowB, next);
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        var head = new Grid();
        head.Children.Add(epgTitle);
        head.Children.Add(buttons);
        var detail = Ui.V(4, epgDetailTime, epgDetail);
        detail.Margin = new Thickness(0, 10, 0, 12);
        detail.MinHeight = 86;
        epgRuler = new Canvas { Height = 26, Margin = new Thickness(EpgNameW, 0, 0, 4), ClipToBounds = true };
        epgList = Ui.List();
        epgList.PreviewMouseLeftButtonUp += (_, e) =>
        {
            if (ItemsControl.ContainerFromElement(epgList, (DependencyObject)e.OriginalSource) is ListBoxItem it
                && epgList.ItemContainerGenerator.IndexFromContainer(it) is int i && i >= 0)
            {
                var rows = EpgRows;
                if (i < rows.Count) PlayChannel(rows[i]);
            }
        };
        var g = new Grid();
        for (int i = 0; i < 3; i++) g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        g.Children.Add(head);
        Grid.SetRow(detail, 1); g.Children.Add(detail);
        Grid.SetRow(epgRuler, 2); g.Children.Add(epgRuler);
        Grid.SetRow(epgList, 3); g.Children.Add(epgList);
        epg = new Border
        {
            Child = g, Background = Ui.PanelBg, HorizontalAlignment = HorizontalAlignment.Right,
            Padding = new Thickness(20, 20, 12, 12), Visibility = Visibility.Collapsed
        };
        epg.MouseLeftButtonDown += (_, e) => e.Handled = true;
        root.Children.Add(epg);
        SizeChanged += (_, _) => { epg.Width = Math.Max(560, ActualWidth * 0.74); if (Visible(epg)) RenderEpg(); };
    }

    void ResetEpgWindow()
    {
        var half = 1800L;
        var t = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        epgWindowStart = DateTimeOffset.FromUnixTimeSeconds(t / half * half - half).UtcDateTime;
    }

    void OpenEpg()
    {
        if (vod != null || channels.Count == 0) return;
        ClosePanel();
        HideInfo();
        epgRow = Math.Max(0, EpgRows.FindIndex(c => c.Number == CurrentChannel?.Number));
        epgCursor = DateTime.UtcNow;
        ResetEpgWindow();
        if (guide.Count == 0) RefreshGuide(false);
        epg.Width = Math.Max(560, ActualWidth * 0.74);
        Show(epg, true);
        RenderEpg();
        Keyboard.Focus(this);
    }

    void CloseEpg() { if (Visible(epg)) { Show(epg, false); Keyboard.Focus(this); } }

    Prog EpgProgram(int row, DateTime t)
    {
        var rows = EpgRows;
        if (row < 0 || row >= rows.Count) return null;
        return Programs(rows[row])?.FirstOrDefault(p => p.Start <= t && p.Stop > t);
    }

    void EpgMoveRow(int step)
    {
        epgRow = Math.Clamp(epgRow + step, 0, Math.Max(0, EpgRows.Count - 1));
        RenderEpg();
    }

    void EpgMoveTime(int dir)
    {
        var rows = EpgRows;
        var list = epgRow < rows.Count ? Programs(rows[epgRow]) : null;
        var cur = EpgProgram(epgRow, epgCursor);
        DateTime next;
        if (dir > 0)
        {
            var from = cur?.Stop ?? epgCursor.AddSeconds(1);
            next = (list?.FirstOrDefault(p => p.Start >= from)?.Start ?? epgCursor.AddMinutes(30)).AddSeconds(1);
        }
        else
        {
            var to = cur?.Start ?? epgCursor;
            next = list?.LastOrDefault(p => p.Stop <= to)?.Start ?? epgCursor.AddMinutes(-30);
        }
        var now = DateTime.UtcNow;
        epgCursor = next < now.AddHours(-2) ? now.AddHours(-2) : next > now.AddHours(36) ? now.AddHours(36) : next;
        var span = TimeSpan.FromHours(EpgSpanHours);
        if (epgCursor < epgWindowStart) epgWindowStart = epgCursor.AddMinutes(-10);
        if (epgCursor > epgWindowStart + span - TimeSpan.FromMinutes(15)) epgWindowStart = epgCursor - span / 3;
        RenderEpg();
    }

    double EpgTimelineWidth => Math.Max(200, epg.Width - 32 - EpgNameW - 14);

    void RenderEpg()
    {
        var rows = EpgRows;
        if (rows.Count == 0) return;
        epgRow = Math.Clamp(epgRow, 0, rows.Count - 1);
        var w = EpgTimelineWidth;
        var span = EpgSpanHours * 3600;
        var ws = epgWindowStart;
        var local = ws.ToLocalTime();
        epgTitle.Text = "Guía  ·  " + (local.Date == DateTime.Today ? "Hoy" : local.Date == DateTime.Today.AddDays(1) ? "Mañana" : local.ToString("dddd d"));
        // time ruler every 30 min
        epgRuler.Children.Clear();
        var first = new DateTime(ws.Ticks - ws.Ticks % TimeSpan.FromMinutes(30).Ticks, DateTimeKind.Utc);
        for (var t = first; t < ws.AddSeconds(span); t = t.AddMinutes(30))
        {
            if (t < ws) continue;
            var x = (t - ws).TotalSeconds / span * w;
            var label = Ui.Txt(Ui.HHmm(t), 13, Ui.Faint, true);
            Canvas.SetLeft(label, x + 4);
            epgRuler.Children.Add(label);
            var tick = new Rectangle { Width = 1, Height = 26, Fill = Ui.Line };
            Canvas.SetLeft(tick, x);
            epgRuler.Children.Add(tick);
        }
        var cur = EpgProgram(epgRow, epgCursor);
        epgDetailTime.Text = cur != null ? $"{rows[epgRow].Name}  ·  {Ui.HHmm(cur.Start)} – {Ui.HHmm(cur.Stop)}  {cur.Title}" : rows[epgRow].Name;
        epgDetail.Text = cur?.Desc ?? "";
        var sel = epgRow;
        var cursor = epgCursor;
        epgList.ItemsSource = rows.Select((ch, i) => new Row(() => EpgRowView(ch, i == sel, cursor, ws, w, span), ch)).ToList();
        epgList.SelectedIndex = sel;
        epgList.ScrollIntoView(epgList.SelectedItem);
    }

    FrameworkElement EpgRowView(Channel ch, bool selected, DateTime cursor, DateTime ws, double w, double span)
    {
        var g = new Grid { Height = 52 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(EpgNameW - 4) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(w) });
        var playing = ch.Number == CurrentChannel?.Number;
        var name = Ui.Txt($"{ch.Number}  {ch.Name}", 14, playing ? Ui.AccentLight : Ui.White, true);
        name.VerticalAlignment = VerticalAlignment.Center;
        name.Margin = new Thickness(8, 0, 8, 0);
        g.Children.Add(name);
        var canvas = new Canvas { ClipToBounds = true, Width = w, Height = 52 };
        Grid.SetColumn(canvas, 1);
        g.Children.Add(canvas);
        var we = ws.AddSeconds(span);
        var list = Programs(ch);
        if (list == null || list.Count == 0)
        {
            var b = new Border { Width = w - 4, Height = 44, Background = Ui.Card, CornerRadius = new CornerRadius(6), Child = Ui.Txt("Sin información", 13, Ui.Faint) };
            ((TextBlock)b.Child).Margin = new Thickness(10, 12, 6, 0);
            Canvas.SetTop(b, 4);
            canvas.Children.Add(b);
        }
        else
        {
            foreach (var p in list)
            {
                if (p.Stop <= ws || p.Start >= we) continue;
                var x0 = Math.Max(0, (p.Start - ws).TotalSeconds / span * w);
                var x1 = Math.Min(w, (p.Stop - ws).TotalSeconds / span * w);
                var isCursor = selected && p.Start <= cursor && p.Stop > cursor;
                var onAir = p.Start <= DateTime.UtcNow && p.Stop > DateTime.UtcNow;
                var t = Ui.Txt(p.Title, 13, isCursor ? Ui.White : onAir ? Ui.White : Ui.Dim, isCursor);
                t.Margin = new Thickness(8, 0, 4, 0);
                t.VerticalAlignment = VerticalAlignment.Center;
                var block = new Border
                {
                    Width = Math.Max(2, x1 - x0 - 3), Height = 44, CornerRadius = new CornerRadius(6), Child = t,
                    Background = isCursor ? Ui.Accent : onAir ? Ui.CardLight : Ui.Card,
                    ToolTip = $"{Ui.HHmm(p.Start)} – {Ui.HHmm(p.Stop)}  {p.Title}"
                };
                Canvas.SetLeft(block, x0);
                Canvas.SetTop(block, 4);
                canvas.Children.Add(block);
            }
        }
        var nowX = (DateTime.UtcNow - ws).TotalSeconds / span * w;
        if (nowX > 0 && nowX < w)
        {
            var line = new Rectangle { Width = 2, Height = 52, Fill = Ui.Gold };
            Canvas.SetLeft(line, nowX);
            canvas.Children.Add(line);
        }
        return g;
    }
}
