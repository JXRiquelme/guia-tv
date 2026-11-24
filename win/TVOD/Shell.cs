using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace TVOD;

/// <summary>
/// Everything drawn over the video: channel info, channel list, guide, catalog, menus.
/// A see-through window that follows the main window. All app state lives here.
/// </summary>
public partial class Shell : Window
{
    public const string GuideUrl = "https://raw.githubusercontent.com/JXRiquelme/guia-tv/main/guia.xml";
    public const string StartChannel = "Canal 13";

    readonly MainWindow main;
    Mpv mpv;

    // layers
    readonly Grid root = new();
    readonly TextBlock statusText = Ui.Txt("", 22, Ui.White, true, 4);
    readonly Border statusBox;
    readonly TextBlock statsText = Ui.Txt("", 14, Ui.White, false, 3);
    readonly Border statsBox;
    readonly TextBlock digitsText = Ui.Txt("", 54, Ui.White, true);
    readonly TextBlock toastText = Ui.Txt("", 17, Ui.White, false, 3);
    readonly Border toastBox;
    readonly StackPanel topBar = new() { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 18, 0, 0) };
    readonly Border shutter = new() { Background = Brushes.Black, Visibility = Visibility.Collapsed };

    readonly DispatcherTimer infoTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    readonly DispatcherTimer clockTimer = new() { Interval = TimeSpan.FromSeconds(30) };
    readonly DispatcherTimer healthTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    readonly DispatcherTimer cursorTimer = new() { Interval = TimeSpan.FromSeconds(3) };

    string audioLang = Store.Get("audio_lang", "es");
    string subLang = Store.Get("sub_lang", "off");
    bool showStats = Store.GetBool("show_stats");
    int volume = Math.Clamp(Store.GetInt("volume", 100), 0, 130);

    public Shell(MainWindow owner)
    {
        main = owner;
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        ResizeMode = ResizeMode.NoResize;
        Title = "TVOD";
        FontFamily = new FontFamily("Segoe UI Variable Text, Segoe UI");
        UseLayoutRounding = true;
        TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);

        root.Background = Ui.Hit;   // catches clicks over the video
        Content = root;
        root.Children.Add(shutter);

        statsBox = Ui.Box(statsText, Ui.Glass, 10, 10);
        statsBox.HorizontalAlignment = HorizontalAlignment.Left;
        statsBox.VerticalAlignment = VerticalAlignment.Top;
        statsBox.Margin = new Thickness(18);
        statsBox.Visibility = Visibility.Collapsed;
        statsBox.IsHitTestVisible = false;
        root.Children.Add(statsBox);

        digitsText.HorizontalAlignment = HorizontalAlignment.Right;
        digitsText.VerticalAlignment = VerticalAlignment.Top;
        digitsText.Margin = new Thickness(0, 24, 36, 0);
        digitsText.IsHitTestVisible = false;
        root.Children.Add(digitsText);

        statusText.TextAlignment = TextAlignment.Center;
        statusText.MaxWidth = 640;
        statusBox = Ui.Box(statusText, Ui.Glass, 16, 22);
        statusBox.HorizontalAlignment = HorizontalAlignment.Center;
        statusBox.VerticalAlignment = VerticalAlignment.Center;
        statusBox.Visibility = Visibility.Collapsed;
        statusBox.IsHitTestVisible = false;
        root.Children.Add(statusBox);

        BuildInfo();
        BuildVodBar();
        root.Children.Add(topBar);
        BuildPanel();
        BuildEpg();
        BuildBrowser();
        BuildDialogLayer();

        toastText.TextAlignment = TextAlignment.Center;
        toastBox = Ui.Box(toastText, Ui.Glass, 12, 14);
        toastBox.HorizontalAlignment = HorizontalAlignment.Center;
        toastBox.VerticalAlignment = VerticalAlignment.Top;
        toastBox.Margin = new Thickness(0, 80, 0, 0);
        toastBox.Visibility = Visibility.Collapsed;
        toastBox.IsHitTestVisible = false;
        root.Children.Add(toastBox);

        infoTimer.Tick += (_, _) => { infoTimer.Stop(); HideInfo(); };
        toastTimer.Tick += (_, _) => { toastTimer.Stop(); toastBox.Visibility = Visibility.Collapsed; };
        clockTimer.Tick += (_, _) => { ClockTick(); };
        healthTimer.Tick += (_, _) => HealthTick();
        cursorTimer.Tick += (_, _) => { cursorTimer.Stop(); if (main.Fullscreen && !browserOpen && dialogStack.Count == 0) Cursor = Cursors.None; };

        PreviewKeyDown += (_, e) => HandleKey(e);
        PreviewMouseWheel += (_, e) => HandleWheel(e);
        MouseMove += (_, _) => { Cursor = null; cursorTimer.Stop(); cursorTimer.Start(); };
        root.MouseLeftButtonDown += VideoMouseDown;
    }

    /// <summary>Called once the window is on screen (the video window exists by then).</summary>
    public void Start()
    {
        try
        {
            mpv = new Mpv(main.Video.Hwnd, Dispatcher);
        }
        catch (Exception e)
        {
            Status("No se pudo iniciar el reproductor.\n" + e.Message);
            return;
        }
        mpv.OnReady = PlaybackReady;
        mpv.OnEndFile = PlaybackEnded;
        mpv.SetVolume(volume);
        Cloud.Shared.OnApplied = () =>
        {
            // another device saved: its "Seguir viendo", "Ver más tarde" and channel options
            history = Store.Load<List<Watch>>("history") ?? new();
            later = Store.Load<List<VodItem>>("later") ?? new();
            if (browserOpen && openSeries == null) RefreshGrid();
            UpdateInfo();
        };
        Cloud.Shared.OnStatus = () => RefreshAccountRow?.Invoke();
        Cloud.Shared.StartPolling();
        Cloud.Shared.Refresh();
        Activated += (_, _) => Cloud.Shared.Refresh();
        clockTimer.Start();
        healthTimer.Start();
        StartLive();
    }

    public void Closing()
    {
        try { SaveVodProgress(); } catch { }
        mpv?.Close();
    }

    // ------------------------------------------------------------ small helpers

    void Status(string text)
    {
        statusText.Text = text ?? "";
        statusBox.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
    }

    void Toast(string text, double seconds = 3)
    {
        toastText.Text = text;
        toastBox.Visibility = Visibility.Visible;
        toastTimer.Stop();
        toastTimer.Interval = TimeSpan.FromSeconds(seconds);
        toastTimer.Start();
    }

    static void Show(UIElement e, bool on) => e.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
    static bool Visible(UIElement e) => e.Visibility == Visibility.Visible;

    void After(double seconds, Action a)
    {
        var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(seconds) };
        t.Tick += (_, _) => { t.Stop(); a(); };
        t.Start();
    }

    void ApplyLanguages() => mpv?.ApplyLanguages(audioLang, subLang);

    void ClockTick()
    {
        UpdateInfo();
        if (Visible(epg)) RenderEpg();
        var f = GuideFile;
        if (File.Exists(f) && (DateTime.Now - File.GetLastWriteTime(f)).TotalHours > 3) RefreshGuide(true);
    }

    void UpdateStats()
    {
        if (!showStats || mpv == null) { Show(statsBox, false); return; }
        Show(statsBox, true);
        int w = (int)mpv.GetDouble("video-params/w", 0), h = (int)mpv.GetDouble("video-params/h", 0);
        if (w <= 0 || h <= 0) { statsText.Text = "Sin video"; return; }
        var fps = mpv.GetDouble("estimated-vf-fps", 0);
        if (fps <= 0) fps = mpv.GetDouble("container-fps", 0);
        // by width too: cinema crops (1920×804) are still 1080p
        var label = h >= 2000 || w >= 3800 ? "4K" : h >= 1000 || w >= 1900 ? "1080p" : h >= 700 || w >= 1260 ? "720p" : $"{h}p";
        var text = $"{label}  ·  {w}×{h}" + (fps > 0 ? $"  ·  {Math.Round(fps)} fps" : "");
        var cache = mpv.GetDouble("demuxer-cache-duration");
        var speed = mpv.GetDouble("cache-speed");
        var rate = mpv.GetDouble("video-bitrate");
        var line = new List<string>();
        if (speed >= 0) line.Add($"Velocidad {speed * 8 / 1_000_000:0.0} Mbps");
        if (rate > 0) line.Add($"Video {rate / 1_000_000:0.0} Mbps");
        if (cache >= 0) line.Add($"Buffer {(int)cache} s");
        if (line.Count > 0) text += "\n" + string.Join("  ·  ", line);
        statsText.Text = text;
    }

    // ------------------------------------------------------------ top buttons

    void BuildTopBar()
    {
        topBar.Children.Clear();
        void Add(string text, Action a)
        {
            var b = Ui.Btn(text, a, Ui.Glass, 14);
            b.Margin = new Thickness(4, 0, 4, 0);
            b.Padding = new Thickness(14, 8, 14, 8);
            topBar.Children.Add(b);
        }
        if (vod == null)
        {
            Add("Canales", () => { HideInfo(); OpenPanel(); });
            Add("Guía", () => { HideInfo(); OpenEpg(); });
            Add("Películas", () => OpenBrowser(false));
            Add("Series", () => OpenBrowser(true));
            var ch = CurrentChannel;
            if (ch != null && ch.Alts.Count > 0) Add(ch.Options[AltIndex(ch)].Label, ShowOptionsMenu);
        }
        Add(main.Mini ? "Ventana normal" : "Ventana pequeña", () => { HideInfo(); main.ToggleMini(); });
        Add(main.Fullscreen ? "Salir de pantalla completa" : "Pantalla completa", () => { main.ToggleFullscreen(); BuildTopBar(); });
        Add("Configuración", OpenSettings);
    }

    void ShowTop(bool on)
    {
        if (on) BuildTopBar();
        Show(topBar, on && !main.Mini);
    }

    // ------------------------------------------------------------ mouse

    void VideoMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource != root && e.OriginalSource != shutter) return;
        if (main.Mini)
        {
            if (e.ClickCount == 2) { main.ToggleMini(); return; }
            main.DragMini();
            return;
        }
        if (e.ClickCount == 2) { main.ToggleFullscreen(); return; }
        TapVideo();
    }

    /// <summary>Click on the video.</summary>
    void TapVideo()
    {
        if (Visible(panel)) { ClosePanel(); return; }
        if (Visible(epg)) { CloseEpg(); return; }
        if (vod != null)
        {
            if (Visible(vodBar)) { Show(vodBar, false); ShowTop(false); }
            else { ShowTop(true); FlashVodBar(); }
            return;
        }
        if (Visible(info)) HideInfo(); else FlashInfo(true);
    }

    public void HandleWheel(MouseWheelEventArgs e)
    {
        if (browserOpen || dialogStack.Count > 0 || Visible(panel) || Visible(epg)) return;
        volume = Math.Clamp(volume + (e.Delta > 0 ? 5 : -5), 0, 130);
        mpv?.SetVolume(volume);
        Store.Set("volume", volume.ToString());
        Toast($"Volumen {volume} %", 1.2);
        e.Handled = true;
    }

    // ------------------------------------------------------------ keyboard

    static bool TextFocused() => Keyboard.FocusedElement is TextBox or PasswordBox;

    public void HandleKey(KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        if (key == Key.Escape) { Back(); e.Handled = true; return; }
        if (TextFocused())
        {
            if (key == Key.Enter && Keyboard.FocusedElement == panelSearch) { PlaySelectedPanel(); e.Handled = true; }
            else if ((key == Key.Down || key == Key.Up) && Keyboard.FocusedElement == panelSearch) { MovePanel(key == Key.Down ? 1 : -1); e.Handled = true; }
            return;
        }
        if (key == Key.F11 || (key == Key.F && Keyboard.Modifiers == ModifierKeys.None)) { main.ToggleFullscreen(); e.Handled = true; return; }
        if (dialogStack.Count > 0) return;
        e.Handled = true;
        if (Visible(epg))
        {
            switch (key)
            {
                case Key.Up: EpgMoveRow(-1); break;
                case Key.Down: EpgMoveRow(1); break;
                case Key.Left: EpgMoveTime(-1); break;
                case Key.Right: EpgMoveTime(1); break;
                case Key.PageUp: EpgMoveRow(-8); break;
                case Key.PageDown: EpgMoveRow(8); break;
                case Key.Enter: { var rows = EpgRows; if (epgRow >= 0 && epgRow < rows.Count) PlayChannel(rows[epgRow]); break; }
                default: e.Handled = false; break;
            }
            return;
        }
        if (Visible(panel))
        {
            switch (key)
            {
                case Key.Up: MovePanel(-1); break;
                case Key.Down: MovePanel(1); break;
                case Key.PageUp: MovePanel(-8); break;
                case Key.PageDown: MovePanel(8); break;
                case Key.Enter: PlaySelectedPanel(); break;
                case Key.Left: ClosePanel(); break;
                default: e.Handled = false; break;
            }
            return;
        }
        if (browserOpen) { e.Handled = false; return; }
        if (vod != null)
        {
            switch (key)
            {
                case Key.Space: case Key.Enter: TogglePause(); break;
                case Key.Left: Seek(-10); break;
                case Key.Right: Seek(10); break;
                case Key.Up: case Key.Down: FlashVodBar(); break;
                default: e.Handled = false; break;
            }
            return;
        }
        switch (key)
        {
            case Key.Down: Zap(1); break;
            case Key.Up: Zap(-1); break;
            case Key.PageDown: Zap(1); break;
            case Key.PageUp: Zap(-1); break;
            case Key.Left: case Key.Enter: TogglePanel(); break;
            case Key.Right: case Key.G: OpenEpg(); break;
            case Key.Space: case Key.I: FlashInfo(true); break;
            case Key.O: NextOption(); break;
            default:
                int d = key >= Key.D0 && key <= Key.D9 ? key - Key.D0 : key >= Key.NumPad0 && key <= Key.NumPad9 ? key - Key.NumPad0 : -1;
                if (d >= 0) TypeDigit(d); else e.Handled = false;
                break;
        }
    }

    string digits = "";
    DispatcherTimer digitTimer;

    void TypeDigit(int n)
    {
        if (digits.Length >= 3) digits = "";
        digits += n;
        digitsText.Text = digits;
        digitTimer?.Stop();
        digitTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        digitTimer.Tick += (_, _) =>
        {
            digitTimer.Stop();
            if (int.TryParse(digits, out var v) && v >= 1 && v <= channels.Count) Play(v - 1);
            digits = "";
            digitsText.Text = "";
        };
        digitTimer.Start();
    }

    void Back()
    {
        if (dialogStack.Count > 0) { CloseDialog(); return; }
        if (Visible(epg)) CloseEpg();
        else if (Visible(panel)) ClosePanel();
        else if (browserOpen) BrowserBack();
        else if (vod != null) LeaveVod();
        else if (main.Mini) main.ToggleMini();
        else if (main.Fullscreen) main.ToggleFullscreen();
        else if (Visible(info)) HideInfo();
    }
}
