using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace TVOD;

enum GridMode { Category, ContinueAll, LaterAll, Search }

public partial class Shell
{
    Watch vod;
    List<Watch> history = Store.Load<List<Watch>>("history") ?? new();
    List<VodItem> later = Store.Load<List<VodItem>>("later") ?? new();
    readonly HashSet<string> plainTs = new();
    bool vodHls;
    DispatcherTimer saveTimer;

    bool browserOpen;
    bool browserSeries;
    readonly Dictionary<bool, List<Category>> categories = new();
    readonly Dictionary<bool, string> selectedCat = new();
    readonly Dictionary<string, List<VodItem>> itemCache = new();
    readonly Dictionary<bool, List<VodItem>> catalogCache = new();
    HashSet<string> hiddenMovieCats = Store.GetSet("hide_vod");
    HashSet<string> hiddenSeriesCats = Store.GetSet("hide_series");
    List<VodItem> gridItems = new();
    GridMode gridMode = GridMode.Category;
    string gridLabel = "";
    int loadToken;

    VodItem openSeries;
    List<Episode> episodes = new();
    int season;
    string continueEpisode;

    XtreamAccount Xtream()
    {
        if (playlistAccount != null) return playlistAccount;
        var saved = Store.Get("xtream");
        if (saved != null)
        {
            try
            {
                var a = System.Text.Json.JsonSerializer.Deserialize<XtreamAccount>(saved);
                if (a?.Valid == true) return a;
            }
            catch { }
        }
        return Bundled.Account;
    }

    static string VodUrl(XtreamAccount acc, Watch w, string ext) => w.Episode ? acc.EpisodeUrl(w.StreamId, ext) : acc.MovieUrl(w.StreamId, ext);

    // ------------------------------------------------------------ movies & series playback

    void PlayVod(Watch w)
    {
        var acc = Xtream();
        if (acc == null || mpv == null) return;
        SaveVodProgress();
        zapTimer?.Stop();
        CloseBrowserView();
        ClosePanel();
        CloseEpg();
        Show(info, false);
        Show(shutter, true);
        mpv.SetAspect("no");
        aspect = "no";
        recoverAttempts = 0;
        stallSince = null;
        playToken++;
        var token = playToken;
        var saved = history.FirstOrDefault(h => h.Id == w.Id);
        var resume = saved != null && !saved.Finished && saved.Position > 10 ? saved.Position : 0;
        var entry = w.Copy();
        entry.Position = resume;
        entry.Duration = saved?.Duration ?? 0;
        vod = entry;
        RecordHistory(entry);
        Status(resume > 0 ? $"Cargando…\nContinuando desde {Ui.Clock(resume)}" : null);
        ApplyLanguages();
        // .ts films: the same title as HLS (.m3u8) resumes straight at the minute; if the service
        // has no HLS version, the .ts file is used.
        var hls = w.Ext.Equals("ts", StringComparison.OrdinalIgnoreCase) && !plainTs.Contains(w.Id);
        vodHls = hls;
        var url = VodUrl(acc, w, hls ? "m3u8" : w.Ext);
        // one connection at a time: close what was playing, then open straight at the resume point
        mpv.Stop();
        recovering = true;
        After(0.6, () =>
        {
            if (token != playToken) return;
            recovering = false;
            mpv.Load(url, resume);
        });
        vodTitle.Text = w.Title;
        vodSubtitle.Text = w.Subtitle;
        FlashVodBar();
        saveTimer?.Stop();
        saveTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
        saveTimer.Tick += (_, _) => { if (vod != null && !mpv.Paused) SaveVodProgress(); };
        saveTimer.Start();
    }

    void SaveVodProgress()
    {
        if (vod == null || mpv == null) return;
        if (mpv.Position > 0) vod.Position = mpv.Position;
        if (mpv.Duration > 0) vod.Duration = mpv.Duration;
        RecordHistory(vod);
    }

    void RecordHistory(Watch w)
    {
        var entry = w.Copy();
        entry.Updated = DateTime.UtcNow;
        history.RemoveAll(h => h.Id == w.Id);
        history.Insert(0, entry);
        if (history.Count > 60) history = history.Take(60).ToList();
        Store.Save("history", history);
    }

    void LeaveVod()
    {
        SaveVodProgress();
        saveTimer?.Stop();
        vod = null;
        Show(vodBar, false);
        ShowTop(false);
        Status(null);
        mpv?.Stop();
        Show(shutter, true);
        OpenBrowserView();
    }

    // ------------------------------------------------------------ player bar

    Border vodBar;
    TextBlock vodTitle, vodSubtitle, vodTime, vodDuration;
    Grid vodTrack;
    Border vodFill;
    Button vodPause;
    string aspect = "no";
    DispatcherTimer vodBarTimer;

    void BuildVodBar()
    {
        vodTitle = Ui.Txt("", 22, Ui.White, true);
        vodSubtitle = Ui.Txt("", 15, Ui.Dim);
        vodTime = Ui.Txt("00:00", 14, Ui.Dim);
        vodDuration = Ui.Txt("00:00", 14, Ui.Dim);
        vodTrack = new Grid { Height = 18, Background = Brushes.Transparent, Cursor = Cursors.Hand, Margin = new Thickness(14, 0, 14, 0) };
        vodTrack.Children.Add(new Border { Height = 6, CornerRadius = new CornerRadius(3), Background = Ui.Line });
        vodFill = new Border { Height = 6, CornerRadius = new CornerRadius(3), Background = Ui.Accent, HorizontalAlignment = HorizontalAlignment.Left, Width = 0 };
        vodTrack.Children.Add(vodFill);
        vodTrack.MouseLeftButtonDown += (_, e) =>
        {
            e.Handled = true;
            if (mpv == null || mpv.Duration <= 0) return;
            var frac = e.GetPosition(vodTrack).X / Math.Max(1, vodTrack.ActualWidth);
            mpv.SeekTo(Math.Clamp(frac, 0, 1) * mpv.Duration);
            FlashVodBar();
        };
        var timeRow = new Grid { Margin = new Thickness(0, 12, 0, 10) };
        timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        timeRow.Children.Add(vodTime);
        Grid.SetColumn(vodTrack, 1); timeRow.Children.Add(vodTrack);
        Grid.SetColumn(vodDuration, 2); timeRow.Children.Add(vodDuration);
        vodPause = Ui.Btn("Pausa", TogglePause);
        var buttons = Ui.H(8,
            Ui.Btn("−10 s", () => Seek(-10)),
            vodPause,
            Ui.Btn("+10 s", () => Seek(10)),
            Ui.Btn("−1 min", () => Seek(-60)),
            Ui.Btn("+1 min", () => Seek(60)),
            Ui.Btn("Audio y subtítulos", ShowTracksMenu),
            Ui.Btn("Formato", ShowAspectMenu),
            Ui.Btn("Volver", LeaveVod));
        buttons.HorizontalAlignment = HorizontalAlignment.Center;
        var content = Ui.V(2, vodTitle, vodSubtitle, timeRow, buttons);
        vodBar = Ui.Box(content, Ui.Glass, 18, 20);
        vodBar.VerticalAlignment = VerticalAlignment.Bottom;
        vodBar.Margin = new Thickness(28, 0, 28, 28);
        vodBar.Visibility = Visibility.Collapsed;
        vodBar.MouseLeftButtonDown += (_, e) => { e.Handled = true; FlashVodBar(); };
        root.Children.Add(vodBar);
    }

    void UpdateVodBar()
    {
        if (mpv == null || vod == null) return;
        vodTime.Text = Ui.Clock(mpv.Position);
        vodDuration.Text = mpv.Duration > 0 ? Ui.Clock(mpv.Duration) : "--:--";
        var frac = mpv.Duration > 0 ? mpv.Position / mpv.Duration : 0;
        vodFill.Width = Math.Clamp(frac, 0, 1) * Math.Max(0, vodTrack.ActualWidth);
        ((TextBlock)vodPause.Content).Text = mpv.Paused ? "Reproducir" : "Pausa";
    }

    void FlashVodBar()
    {
        if (vod == null) return;
        UpdateVodBar();
        Show(vodBar, !main.Mini);
        vodBarTimer?.Stop();
        vodBarTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        vodBarTimer.Tick += (_, _) =>
        {
            if (mpv != null && mpv.Paused) return;
            if (vodBar.IsMouseOver) return;
            vodBarTimer.Stop();
            Show(vodBar, false);
            ShowTop(false);
        };
        vodBarTimer.Start();
    }

    void Seek(double s)
    {
        mpv?.SeekBy(s);
        FlashVodBar();
    }

    void TogglePause()
    {
        if (mpv == null) return;
        mpv.TogglePause();
        if (!mpv.Paused) SaveVodProgress();
        After(0.1, UpdateVodBar);
        FlashVodBar();
    }

    // ------------------------------------------------------------ catalog

    Border browser;
    StackPanel chipsRow;
    ScrollViewer chipsScroll;
    ListBox gridList;
    TextBlock gridMessage;
    TextBox searchBox;
    Button tabMovies, tabSeries;
    Border seriesView;
    DispatcherTimer searchDebounce;
    const double PosterW = 168, PosterH = 252;

    void BuildBrowser()
    {
        tabMovies = Ui.Btn("Películas", () => OpenBrowser(false), null, 17);
        tabSeries = Ui.Btn("Series", () => OpenBrowser(true), null, 17);
        var tv = Ui.Btn("◀  TV en vivo", CloseBrowser, null, 17);
        var left = Ui.H(10, tv, tabMovies, tabSeries);
        searchBox = new TextBox { Width = 300 };
        var hint = Ui.Txt("Buscar…", 16, Ui.Faint);
        hint.IsHitTestVisible = false;
        hint.Margin = new Thickness(14, 0, 0, 0);
        hint.VerticalAlignment = VerticalAlignment.Center;
        searchBox.TextChanged += (_, _) =>
        {
            Show(hint, searchBox.Text.Length == 0);
            searchDebounce?.Stop();
            if (searchBox.Text.Trim().Length < 2) { if (gridMode == GridMode.Search && searchBox.Text.Length == 0) SelectCategory(selectedCat.GetValueOrDefault(browserSeries)); return; }
            searchDebounce = new DispatcherTimer { Interval = TimeSpan.FromSeconds(0.8) };
            searchDebounce.Tick += (_, _) => { searchDebounce.Stop(); RunSearch(); };
            searchDebounce.Start();
        };
        searchBox.KeyDown += (_, e) => { if (e.Key == Key.Enter) { searchDebounce?.Stop(); RunSearch(); } };
        var searchGrid = new Grid();
        searchGrid.Children.Add(searchBox);
        searchGrid.Children.Add(hint);
        var right = Ui.H(10, searchGrid, Ui.Btn("Categorías", () => OpenCategoryFilter(browserSeries)), Ui.Btn("Configuración", OpenSettings));
        right.HorizontalAlignment = HorizontalAlignment.Right;
        var header = new Grid { Margin = new Thickness(0, 0, 0, 14) };
        header.Children.Add(left);
        header.Children.Add(right);

        chipsRow = new StackPanel { Orientation = Orientation.Horizontal };
        chipsScroll = new ScrollViewer
        {
            Content = chipsRow, HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Margin = new Thickness(0, 0, 0, 10)
        };
        chipsScroll.PreviewMouseWheel += (_, e) => { chipsScroll.ScrollToHorizontalOffset(chipsScroll.HorizontalOffset - e.Delta); e.Handled = true; };

        gridList = Ui.List();
        gridList.SizeChanged += (s, e) => { if (e.WidthChanged && Math.Abs(e.NewSize.Width - e.PreviousSize.Width) > 40) RefreshGrid(); };
        gridMessage = Ui.Txt("", 18, Ui.Dim, false, 4);
        gridMessage.HorizontalAlignment = HorizontalAlignment.Center;
        gridMessage.VerticalAlignment = VerticalAlignment.Center;
        gridMessage.TextAlignment = TextAlignment.Center;
        seriesView = new Border { Background = Brushes.Transparent, Visibility = Visibility.Collapsed };
        var content = new Grid();
        content.Children.Add(gridList);
        content.Children.Add(gridMessage);
        content.Children.Add(seriesView);

        var g = new Grid();
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        g.Children.Add(header);
        Grid.SetRow(chipsScroll, 1); g.Children.Add(chipsScroll);
        Grid.SetRow(content, 2); g.Children.Add(content);
        browser = new Border
        {
            Child = g, Background = new SolidColorBrush(Color.FromRgb(0x0E, 0x0F, 0x11)), Padding = new Thickness(28, 22, 14, 0),
            Visibility = Visibility.Collapsed
        };
        browser.MouseLeftButtonDown += (_, e) => e.Handled = true;
        root.Children.Add(browser);
    }

    void OpenBrowserView()
    {
        browserOpen = true;
        Show(browser, true);
        Show(info, false);
        ShowTop(false);
        RefreshGrid();
    }

    void CloseBrowserView()
    {
        browserOpen = false;
        Show(browser, false);
    }

    void OpenBrowser(bool series)
    {
        if (Xtream() == null)
        {
            OpenAccount(series);
            return;
        }
        ClosePanel();
        CloseEpg();
        HideInfo();
        zapTimer?.Stop();
        if (vod != null) { SaveVodProgress(); vod = null; Show(vodBar, false); saveTimer?.Stop(); }
        mpv?.Stop();
        Show(shutter, true);
        Status(null);
        if (browserSeries != series || !browserOpen)
        {
            browserSeries = series;
            CloseSeries();
            searchBox.Text = "";
            gridMode = GridMode.Category;
        }
        tabMovies.Background = series ? Ui.ButtonBg : Ui.Accent;
        tabSeries.Background = series ? Ui.Accent : Ui.ButtonBg;
        OpenBrowserView();
        if (!categories.ContainsKey(series)) LoadCategories();
        else { BuildChips(); SelectCategory(selectedCat.GetValueOrDefault(series) ?? DefaultCategory()?.Id); }
    }

    void CloseBrowser()
    {
        CloseBrowserView();
        CloseSeries();
        if (channels.Count > 0) Play(current);
    }

    HashSet<string> HiddenCats(bool series) => series ? hiddenSeriesCats : hiddenMovieCats;
    List<Category> VisibleCats => (categories.GetValueOrDefault(browserSeries) ?? new()).Where(c => !HiddenCats(browserSeries).Contains(c.Id)).ToList();

    Category DefaultCategory()
    {
        var list = VisibleCats;
        string K(Category c) => Search.Normalize(c.Name).Trim();
        if (browserSeries) return list.FirstOrDefault(c => K(c) == "hbo") ?? list.FirstOrDefault(c => K(c).Contains("hbo")) ?? list.FirstOrDefault();
        return list.FirstOrDefault(c => K(c) == "estrenos") ?? list.FirstOrDefault(c => K(c).Contains("estreno") && !K(c).Contains("cam")) ?? list.FirstOrDefault();
    }

    /// <summary>
    /// The first time the categories arrive, the adult ones (the service's last two, plus any whose
    /// name says so) start switched off. Done once: turning them back on is respected.
    /// </summary>
    void HideAdultByDefault(bool series, List<Category> list)
    {
        var done = series ? "adult_off_series" : "adult_off_vod";
        if (Store.GetBool(done) || list.Count == 0) return;
        var rx = new Regex("adult|xxx|\\+ ?18|porn|er[oó]tic", RegexOptions.IgnoreCase);
        var set = HiddenCats(series);
        foreach (var c in list.Skip(Math.Max(0, list.Count - 2))) set.Add(c.Id);
        foreach (var c in list.Where(c => rx.IsMatch(c.Name))) set.Add(c.Id);
        Store.SetSet(series ? "hide_series" : "hide_vod", set);
        Store.SetBool(done, true);
    }

    async void LoadCategories()
    {
        var acc = Xtream();
        if (acc == null) return;
        var series = browserSeries;
        var token = ++loadToken;
        GridMessage("Cargando categorías…");
        try
        {
            var cats = await Xtream_.Categories(acc, series);
            HideAdultByDefault(series, cats);
            categories[series] = cats;
            if (token != loadToken || series != browserSeries) return;
            BuildChips();
            SelectCategory(selectedCat.GetValueOrDefault(series) ?? DefaultCategory()?.Id);
        }
        catch (Exception e)
        {
            if (token == loadToken) GridMessage($"No se pudo cargar: {e.Message}");
        }
    }

    void BuildChips()
    {
        chipsRow.Children.Clear();
        var sel = selectedCat.GetValueOrDefault(browserSeries);
        foreach (var c in VisibleCats)
        {
            var cat = c;
            var b = Ui.Btn(c.Name, () => SelectCategory(cat.Id), gridMode == GridMode.Category && c.Id == sel ? Ui.Accent : Ui.ButtonBg, 15);
            b.Margin = new Thickness(0, 0, 8, 0);
            b.Padding = new Thickness(16, 7, 16, 7);
            b.Tag = c.Id;
            chipsRow.Children.Add(b);
        }
    }

    void MarkChips()
    {
        var sel = selectedCat.GetValueOrDefault(browserSeries);
        foreach (Button b in chipsRow.Children)
        {
            var on = gridMode == GridMode.Category && Equals(b.Tag, sel);
            b.Background = on ? Ui.Accent : Ui.ButtonBg;
            if (on) b.BringIntoView();
        }
    }

    void GridMessage(string text)
    {
        gridMessage.Text = text ?? "";
        Show(gridMessage, !string.IsNullOrEmpty(text));
    }

    async void SelectCategory(string id)
    {
        CloseSeries();
        var cat = VisibleCats.FirstOrDefault(c => c.Id == id) ?? DefaultCategory();
        if (cat == null)
        {
            gridItems = new();
            gridMode = GridMode.Category;
            RefreshGrid();
            GridMessage((categories.GetValueOrDefault(browserSeries) ?? new()).Count == 0
                ? "El proveedor no tiene categorías aquí." : "Todas las categorías están ocultas (usa Categorías).");
            return;
        }
        var series = browserSeries;
        selectedCat[series] = cat.Id;
        gridMode = GridMode.Category;
        gridLabel = cat.Name;
        MarkChips();
        var key = $"{series}:{cat.Id}";
        if (itemCache.TryGetValue(key, out var cached))
        {
            gridItems = cached;
            RefreshGrid();
            GridMessage(cached.Count == 0 ? "Categoría vacía." : null);
            return;
        }
        var acc = Xtream();
        if (acc == null) return;
        var token = ++loadToken;
        gridItems = new();
        RefreshGrid();
        GridMessage($"Cargando {cat.Name}…");
        try
        {
            var list = await Xtream_.Items(acc, series, cat.Id);
            itemCache[key] = list;
            if (token != loadToken || series != browserSeries) return;
            gridItems = list;
            RefreshGrid();
            GridMessage(list.Count == 0 ? "Categoría vacía." : null);
        }
        catch (Exception e)
        {
            if (token == loadToken) GridMessage($"No se pudo cargar: {e.Message}");
        }
    }

    /// <summary>For series: one entry each, the most advanced episode started (season, then number).</summary>
    List<Watch> ContinueList
    {
        get
        {
            var outList = new List<Watch>();
            var at = new Dictionary<string, int>();
            foreach (var w in history)
            {
                if (w.Episode != browserSeries || w.Finished) continue;
                if (!w.Episode) { outList.Add(w); continue; }
                var key = w.SeriesId.Length == 0 ? w.StreamId : w.SeriesId;
                if (at.TryGetValue(key, out var i)) { if (EpisodeOrder(w) > EpisodeOrder(outList[i])) outList[i] = w; }
                else { at[key] = outList.Count; outList.Add(w); }
            }
            return outList;
        }
    }

    static readonly Regex OrderRx = new("^T(\\d+) · E(\\d+)");
    static int EpisodeOrder(Watch w)
    {
        var m = OrderRx.Match(w.Subtitle ?? "");
        return m.Success ? int.Parse(m.Groups[1].Value) * 10_000 + int.Parse(m.Groups[2].Value) : -1;
    }

    List<VodItem> LaterList => later.Where(v => v.Series == browserSeries).ToList();

    void ShowAll(bool continueRow)
    {
        CloseSeries();
        gridMode = continueRow ? GridMode.ContinueAll : GridMode.LaterAll;
        gridLabel = continueRow ? "Seguir viendo" : "Ver más tarde";
        MarkChips();
        RefreshGrid();
        GridMessage(null);
    }

    int Columns => Math.Max(2, (int)((Math.Max(400, gridList.ActualWidth > 0 ? gridList.ActualWidth : ActualWidth - 60) - 24) / (PosterW + 18)));

    void RefreshGrid()
    {
        if (gridList == null) return;
        var rows = new List<Row>();
        var cols = Columns;
        if (gridMode == GridMode.Category)
        {
            var cont = ContinueList;
            if (cont.Count > 0)
            {
                rows.Add(new Row(() => SectionHeader("Seguir viendo", () => ShowAll(true))));
                rows.Add(new Row(() => Strip(cont.Take(24).Select(w => (FrameworkElement)WatchCard(w)))));
            }
            var lat = LaterList;
            if (lat.Count > 0)
            {
                rows.Add(new Row(() => SectionHeader("Ver más tarde", () => ShowAll(false))));
                rows.Add(new Row(() => Strip(lat.Take(24).Select(v => (FrameworkElement)PosterCard(v)))));
            }
            rows.Add(new Row(() => SectionHeader(gridLabel, null)));
            foreach (var chunk in gridItems.Chunk(cols)) { var c = chunk; rows.Add(new Row(() => PosterRow(c.Select(v => (FrameworkElement)PosterCard(v))))); }
        }
        else if (gridMode == GridMode.ContinueAll)
        {
            var cont = ContinueList;
            rows.Add(new Row(() => SectionHeader(gridLabel, null)));
            foreach (var chunk in cont.Chunk(cols)) { var c = chunk; rows.Add(new Row(() => PosterRow(c.Select(w => (FrameworkElement)WatchCard(w))))); }
            GridMessage(cont.Count == 0 ? "Nada a medio ver." : null);
        }
        else if (gridMode == GridMode.LaterAll)
        {
            var lat = LaterList;
            rows.Add(new Row(() => SectionHeader(gridLabel, null)));
            foreach (var chunk in lat.Chunk(cols)) { var c = chunk; rows.Add(new Row(() => PosterRow(c.Select(v => (FrameworkElement)PosterCard(v))))); }
            GridMessage(lat.Count == 0 ? "La lista está vacía. Clic derecho en un póster → Ver más tarde." : null);
        }
        else
        {
            rows.Add(new Row(() => SectionHeader(gridLabel, null)));
            foreach (var chunk in gridItems.Chunk(cols)) { var c = chunk; rows.Add(new Row(() => PosterRow(c.Select(v => (FrameworkElement)PosterCard(v))))); }
        }
        gridList.ItemsSource = rows;
    }

    FrameworkElement SectionHeader(string text, Action all)
    {
        var g = new Grid { Margin = new Thickness(4, 14, 18, 8) };
        g.Children.Add(Ui.Txt(text, 21, Ui.White, true));
        if (all != null)
        {
            var b = Ui.Btn("Ver todo", all, null, 14);
            b.HorizontalAlignment = HorizontalAlignment.Right;
            b.Padding = new Thickness(12, 4, 12, 4);
            g.Children.Add(b);
        }
        return g;
    }

    FrameworkElement Strip(IEnumerable<FrameworkElement> cards)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var c in cards) { c.Margin = new Thickness(4, 0, 14, 0); p.Children.Add(c); }
        var sv = new ScrollViewer { Content = p, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(0, 0, 0, 8) };
        sv.PreviewMouseWheel += (_, e) =>
        {
            // the page scrolls; shift + wheel moves the row sideways
            if (Keyboard.Modifiers == ModifierKeys.Shift) { sv.ScrollToHorizontalOffset(sv.HorizontalOffset - e.Delta); e.Handled = true; }
        };
        return sv;
    }

    FrameworkElement PosterRow(IEnumerable<FrameworkElement> cards)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 14) };
        foreach (var c in cards) { c.Margin = new Thickness(4, 0, 14, 0); p.Children.Add(c); }
        return p;
    }

    FrameworkElement PosterCard(VodItem v)
    {
        var poster = Ui.Poster(v.Poster, PosterW, PosterH);
        var meta = string.Join("  ·  ", new[] { v.Year, v.Rating.Length > 0 ? "★ " + v.Rating : "" }.Where(s => s.Length > 0));
        var title = Ui.Txt(v.Title, 14, Ui.White, true, 2);
        title.Width = PosterW;
        var stack = Ui.V(6, poster, title, Ui.Txt(meta, 12, Ui.Faint));
        var w = WatchFor(v);
        if (w != null && !w.Finished && w.Duration > 0)
        {
            var pr = Ui.Progress(w.Position / w.Duration, PosterW - 16, 4);
            pr.VerticalAlignment = VerticalAlignment.Bottom;
            pr.Margin = new Thickness(8, 0, 8, 8);
            var over = new Grid();
            stack.Children.Remove(poster);
            over.Children.Add(poster);
            over.Children.Add(pr);
            stack.Children.Insert(0, over);
        }
        var card = new Border { Child = stack, Background = Brushes.Transparent, Cursor = Cursors.Hand, Width = PosterW, ToolTip = v.Title };
        card.MouseLeftButtonUp += (_, e) => { e.Handled = true; OpenItem(v); };
        var menu = new ContextMenu();
        var lat = new MenuItem { Header = IsLater(v) ? "Quitar de Ver más tarde" : "Agregar a Ver más tarde" };
        lat.Click += (_, _) => ToggleLater(v);
        menu.Items.Add(lat);
        if (w != null)
        {
            var rm = new MenuItem { Header = "Quitar de Seguir viendo" };
            rm.Click += (_, _) => RemoveFromHistory(w);
            menu.Items.Add(rm);
        }
        card.ContextMenu = menu;
        return card;
    }

    FrameworkElement WatchCard(Watch w)
    {
        var poster = Ui.Poster(w.Poster, PosterW, PosterH);
        var over = new Grid();
        over.Children.Add(poster);
        if (w.Duration > 0)
        {
            var pr = Ui.Progress(w.Position / w.Duration, PosterW - 16, 4);
            pr.VerticalAlignment = VerticalAlignment.Bottom;
            pr.Margin = new Thickness(8, 0, 8, 8);
            over.Children.Add(pr);
        }
        var title = Ui.Txt(w.Title, 14, Ui.White, true, 2);
        title.Width = PosterW;
        var sub = Ui.Txt(w.Episode ? w.Subtitle : (w.Duration > 0 ? $"Quedan {Math.Max(1, (int)((w.Duration - w.Position) / 60))} min" : w.Subtitle), 12, Ui.Faint);
        sub.Width = PosterW;
        var card = new Border { Child = Ui.V(6, over, title, sub), Background = Brushes.Transparent, Cursor = Cursors.Hand, Width = PosterW, ToolTip = w.Title };
        card.MouseLeftButtonUp += (_, e) => { e.Handled = true; OpenWatch(w); };
        var menu = new ContextMenu();
        var rm = new MenuItem { Header = "Quitar de Seguir viendo" };
        rm.Click += (_, _) => RemoveFromHistory(w);
        menu.Items.Add(rm);
        card.ContextMenu = menu;
        return card;
    }

    Watch WatchFor(VodItem v) => v.Series ? null : history.FirstOrDefault(h => h.Id == "m:" + v.StreamId);

    static Watch MovieWatch(VodItem v) => new() { Episode = false, StreamId = v.StreamId, Ext = v.Ext, Title = v.Title, Subtitle = v.Year, Poster = v.Poster };

    void OpenItem(VodItem v)
    {
        if (v.Series) OpenSeriesView(v);
        else PlayVod(MovieWatch(v));
    }

    void OpenWatch(Watch w)
    {
        // a series in "Seguir viendo": its episodes, standing on the one you were watching
        if (w.Episode && w.SeriesId.Length > 0)
            OpenSeriesView(new VodItem { Series = true, StreamId = w.SeriesId, Title = w.Title, Poster = w.Poster, Ext = w.Ext }, w.StreamId);
        else PlayVod(w);
    }

    bool IsLater(VodItem v) => later.Any(l => l.Id == v.Id);

    void ToggleLater(VodItem v)
    {
        if (IsLater(v)) later.RemoveAll(l => l.Id == v.Id);
        else { later.Insert(0, v); Toast($"“{v.Title}” quedó en Ver más tarde"); }
        Store.Save("later", later);
        if (openSeries == null) RefreshGrid(); else RenderSeriesHeader();
    }

    void RemoveFromHistory(Watch w)
    {
        if (w.Episode && w.SeriesId.Length > 0) history.RemoveAll(h => h.SeriesId == w.SeriesId);
        else history.RemoveAll(h => h.Id == w.Id);
        Store.Save("history", history);
        RefreshGrid();
    }

    async void RunSearch()
    {
        var query = searchBox.Text.Trim();
        var acc = Xtream();
        if (query.Length < 2 || acc == null) return;
        var series = browserSeries;
        CloseSeries();
        gridMode = GridMode.Search;
        MarkChips();
        var token = ++loadToken;
        void ShowResults(List<VodItem> all)
        {
            var q = Search.Normalize(query);
            var hidden = HiddenCats(series);
            var found = all.Where(v => !hidden.Contains(v.CategoryId) && v.SearchKey.Contains(q)).ToList();
            gridItems = found.Take(600).ToList();
            gridLabel = $"Resultados para “{query}” ({found.Count})";
            RefreshGrid();
            GridMessage(found.Count == 0 ? $"Sin resultados para “{query}”." : null);
        }
        if (catalogCache.TryGetValue(series, out var cached)) { ShowResults(cached); return; }
        gridItems = new();
        gridLabel = $"Buscando “{query}”…";
        RefreshGrid();
        GridMessage("Buscando en todo el catálogo…\nLa primera búsqueda tarda un poco.");
        try
        {
            var list = await Xtream_.Items(acc, series, null);
            catalogCache[series] = list;
            if (token != loadToken || series != browserSeries) return;
            ShowResults(list);
        }
        catch (Exception e)
        {
            if (token == loadToken) GridMessage($"No se pudo buscar: {e.Message}");
        }
    }

    void BrowserBack()
    {
        if (openSeries != null) { CloseSeries(); RefreshGrid(); }
        else if (gridMode == GridMode.Search) { searchBox.Text = ""; SelectCategory(selectedCat.GetValueOrDefault(browserSeries)); }
        else if (gridMode != GridMode.Category) SelectCategory(selectedCat.GetValueOrDefault(browserSeries));
        else CloseBrowser();
    }

    // ------------------------------------------------------------ series

    ListBox episodeList;
    StackPanel seasonRow;
    TextBlock seriesInfo;
    Border seriesHeaderHost;

    static Watch EpisodeWatch(VodItem s, Episode e) => new()
    {
        Episode = true, StreamId = e.Id, Ext = e.Ext, Title = s.Title, Subtitle = $"T{e.Season} · E{e.Number}  {e.Title}",
        Poster = s.Poster, SeriesId = s.StreamId
    };

    async void OpenSeriesView(VodItem s, string focus = null)
    {
        var acc = Xtream();
        if (acc == null) return;
        openSeries = s;
        episodes = new();
        continueEpisode = null;
        BuildSeriesView();
        seriesInfo.Text = "Cargando episodios…";
        Show(seriesView, true);
        Show(gridList, false);
        GridMessage(null);
        var token = ++loadToken;
        List<Episode> list;
        string error = null;
        try { list = await Xtream_.Episodes(acc, s.StreamId); }
        catch (Exception e) { list = new(); error = e.Message; }
        if (token != loadToken || openSeries != s) return;
        episodes = list;
        // the most recent unfinished episode of this series (or the one asked for)
        var recent = ContinueListFor(s.StreamId);
        var target = focus ?? recent;
        var resume = list.FirstOrDefault(e => history.FirstOrDefault(h => h.Id == "e:" + e.Id) is Watch h && !h.Finished);
        continueEpisode = list.Any(e => e.Id == target) ? target : resume?.Id;
        var seasons = Seasons;
        season = list.FirstOrDefault(e => e.Id == continueEpisode)?.Season ?? resume?.Season ?? (seasons.Count > 0 ? seasons[0] : 0);
        if (error != null) seriesInfo.Text = $"No se pudo cargar: {error}";
        else
        {
            var n = seasons.Count;
            seriesInfo.Text = list.Count == 0 ? "Esta serie no tiene episodios." : $"{n} temporada{(n == 1 ? "" : "s")}  ·  {list.Count} episodios";
        }
        RenderSeasons();
        RenderEpisodes(true);
    }

    string ContinueListFor(string seriesId)
    {
        Watch best = null;
        foreach (var w in history.Where(h => h.Episode && h.SeriesId == seriesId && !h.Finished))
            if (best == null || EpisodeOrder(w) > EpisodeOrder(best)) best = w;
        return best?.StreamId;
    }

    List<int> Seasons => episodes.Select(e => e.Season).Distinct().OrderBy(x => x).ToList();

    void CloseSeries()
    {
        if (openSeries == null) return;
        loadToken++;
        openSeries = null;
        episodes = new();
        Show(seriesView, false);
        Show(gridList, true);
    }

    void BuildSeriesView()
    {
        seriesInfo = Ui.Txt("", 15, Ui.Dim);
        seasonRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 12) };
        episodeList = Ui.List();
        episodeList.PreviewMouseLeftButtonUp += (_, e) =>
        {
            if (ItemsControl.ContainerFromElement(episodeList, (DependencyObject)e.OriginalSource) is ListBoxItem it
                && it.DataContext is Row r && r.Tag is Episode ep && openSeries != null)
                PlayVod(EpisodeWatch(openSeries, ep));
        };
        seriesHeaderHost = new Border();
        RenderSeriesHeader();
        var left = new Grid();
        left.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        left.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        left.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        left.Children.Add(seriesHeaderHost);
        var seasonsScroll = new ScrollViewer { Content = seasonRow, HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled };
        seasonsScroll.PreviewMouseWheel += (_, e) => { seasonsScroll.ScrollToHorizontalOffset(seasonsScroll.HorizontalOffset - e.Delta); e.Handled = true; };
        Grid.SetRow(seasonsScroll, 1); left.Children.Add(seasonsScroll);
        Grid.SetRow(episodeList, 2); left.Children.Add(episodeList);
        seriesView.Child = left;
    }

    void RenderSeriesHeader()
    {
        if (openSeries == null || seriesHeaderHost == null) return;
        var s = openSeries;
        if (seriesInfo.Parent is Panel old) old.Children.Remove(seriesInfo);
        var back = Ui.Btn("◀  Volver", () => { CloseSeries(); RefreshGrid(); }, null, 15);
        back.HorizontalAlignment = HorizontalAlignment.Left;
        var inLater = IsLater(s);
        var laterB = Ui.Btn(inLater ? "✓ En Ver más tarde" : "+ Ver más tarde", () => ToggleLater(s), inLater ? Ui.Accent : null, 15);
        laterB.HorizontalAlignment = HorizontalAlignment.Left;
        laterB.Margin = new Thickness(0, 12, 0, 0);
        var title = Ui.Txt(s.Title, 30, Ui.White, true, 2);
        title.Margin = new Thickness(0, 12, 0, 0);
        seriesInfo.Margin = new Thickness(0, 6, 0, 0);
        var texts = new StackPanel { Margin = new Thickness(22, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        texts.Children.Add(back);
        texts.Children.Add(title);
        texts.Children.Add(seriesInfo);
        texts.Children.Add(laterB);
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(Ui.Poster(s.Poster, 150, 225));
        row.Children.Add(texts);
        seriesHeaderHost.Child = row;
    }

    void RenderSeasons()
    {
        seasonRow.Children.Clear();
        foreach (var n in Seasons)
        {
            var sn = n;
            var b = Ui.Btn(n == 0 ? "Especiales" : $"Temporada {n}", () => { season = sn; RenderSeasons(); RenderEpisodes(false); }, n == season ? Ui.Accent : Ui.ButtonBg, 15);
            b.Margin = new Thickness(0, 0, 8, 0);
            seasonRow.Children.Add(b);
        }
    }

    void RenderEpisodes(bool scrollToContinue)
    {
        var list = episodes.Where(e => e.Season == season).ToList();
        var rows = list.Select(e => new Row(() => EpisodeRow(e), e)).ToList();
        episodeList.ItemsSource = rows;
        var i = list.FindIndex(e => e.Id == continueEpisode);
        if (scrollToContinue && i >= 0)
        {
            episodeList.SelectedIndex = i;
            Dispatcher.BeginInvoke(() => episodeList.ScrollIntoView(rows[i]), DispatcherPriority.Background);
        }
    }

    FrameworkElement EpisodeRow(Episode e)
    {
        var w = history.FirstOrDefault(h => h.Id == "e:" + e.Id);
        var isContinue = e.Id == continueEpisode;
        var thumb = Ui.Poster(e.Image.Length > 0 ? e.Image : openSeries?.Poster, 192, 108, 8);
        var title = Ui.Txt($"{e.Number}.  {e.Title}", 17, Ui.White, true);
        var texts = Ui.V(6, title);
        if (w != null && w.Duration > 0)
        {
            var state = w.Finished ? "Visto" : $"Vas en {Ui.Clock(w.Position)} de {Ui.Clock(w.Duration)}";
            texts.Children.Add(Ui.Txt(state, 13, Ui.Faint));
            var pr = Ui.Progress(w.Finished ? 1 : w.Position / w.Duration, 260, 4, w.Finished ? Ui.Faint : Ui.Accent);
            pr.HorizontalAlignment = HorizontalAlignment.Left;
            texts.Children.Add(pr);
        }
        if (isContinue)
        {
            var c = Ui.Txt("▶  Continuar aquí", 14, Ui.Gold, true);
            c.Margin = new Thickness(0, 4, 0, 0);
            texts.Children.Add(c);
        }
        texts.Margin = new Thickness(18, 0, 0, 0);
        texts.VerticalAlignment = VerticalAlignment.Center;
        var row = Ui.H(0, thumb, texts);
        var b = new Border
        {
            Child = row, Padding = new Thickness(10), Cursor = Cursors.Hand,
            Background = isContinue ? new SolidColorBrush(Color.FromArgb(0x30, 0xFF, 0xC8, 0x57)) : Brushes.Transparent,
            CornerRadius = new CornerRadius(10)
        };
        return b;
    }
}

/// <summary>Alias so the Shell's own Xtream() account method doesn't hide the API class.</summary>
static class Xtream_
{
    public static Task<List<Category>> Categories(XtreamAccount a, bool s) => TVOD.Xtream.Categories(a, s);
    public static Task<List<VodItem>> Items(XtreamAccount a, bool s, string c) => TVOD.Xtream.Items(a, s, c);
    public static Task<List<Episode>> Episodes(XtreamAccount a, string id) => TVOD.Xtream.Episodes(a, id);
}
