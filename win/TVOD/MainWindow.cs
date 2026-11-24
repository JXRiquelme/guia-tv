using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace TVOD;

/// <summary>Native child window mpv draws the video into.</summary>
public class VideoHost : HwndHost
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateWindowEx(int exStyle, string cls, string name, int style, int x, int y, int w, int h,
        IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
    [DllImport("user32.dll")] static extern bool DestroyWindow(IntPtr hwnd);

    const int WS_CHILD = 0x40000000, WS_VISIBLE = 0x10000000, WS_CLIPCHILDREN = 0x02000000, WS_CLIPSIBLINGS = 0x04000000;
    const int SS_BLACKRECT = 0x00000004;

    public IntPtr Hwnd { get; private set; }

    protected override HandleRef BuildWindowCore(HandleRef parent)
    {
        Hwnd = CreateWindowEx(0, "static", "", WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS | SS_BLACKRECT,
            0, 0, 16, 16, parent.Handle, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        return new HandleRef(this, Hwnd);
    }

    protected override void DestroyWindowCore(HandleRef hwnd) => DestroyWindow(hwnd.Handle);
}

/// <summary>
/// The app window: only the video. Everything drawn over it (menus, guide, catalog) lives in
/// <see cref="Shell"/>, a see-through window kept exactly on top of this one.
/// </summary>
public class MainWindow : Window
{
    public readonly VideoHost Video = new();
    public Shell Ui;
    bool fullscreen;
    WindowState savedState;
    Rect savedBounds;
    public bool Mini { get; private set; }
    Rect miniSaved;
    WindowState miniState;

    public MainWindow()
    {
        Title = "TVOD";
        Width = 1280;
        Height = 760;
        MinWidth = 480;
        MinHeight = 270;
        Background = Brushes.Black;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        try { Icon = BitmapFrame.Create(new Uri("pack://application:,,,/tvod.png")); } catch { }
        Content = Video;
        Loaded += (_, _) =>
        {
            Ui = new Shell(this) { Owner = this };
            Ui.Show();
            Sync();
            Ui.Start();
        };
        LocationChanged += (_, _) => Sync();
        SizeChanged += (_, _) => Sync();
        StateChanged += (_, _) =>
        {
            if (Ui == null) return;
            if (WindowState == WindowState.Minimized) Ui.Hide();
            else { Ui.Show(); Sync(); }
        };
        // keys pressed while this (and not the layer on top) has the focus
        PreviewKeyDown += (_, e) => Ui?.HandleKey(e);
        PreviewMouseWheel += (_, e) => Ui?.HandleWheel(e);
        Closing += (_, _) => Ui?.Closing();
    }

    public void BringToFront()
    {
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        Ui?.Activate();
    }

    /// <summary>Keeps the layer exactly over the video area.</summary>
    public void Sync()
    {
        if (Ui == null || !IsLoaded || WindowState == WindowState.Minimized) return;
        var src = PresentationSource.FromVisual(Video);
        if (src?.CompositionTarget == null) return;
        var topLeft = src.CompositionTarget.TransformFromDevice.Transform(Video.PointToScreen(new Point(0, 0)));
        Ui.Left = topLeft.X;
        Ui.Top = topLeft.Y;
        Ui.Width = Math.Max(1, Video.ActualWidth);
        Ui.Height = Math.Max(1, Video.ActualHeight);
    }

    public bool Fullscreen => fullscreen;

    public void ToggleFullscreen()
    {
        if (Mini) ToggleMini();
        if (!fullscreen)
        {
            savedState = WindowState;
            savedBounds = new Rect(Left, Top, Width, Height);
            fullscreen = true;
            WindowState = WindowState.Normal;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            WindowState = WindowState.Maximized;
        }
        else
        {
            fullscreen = false;
            WindowStyle = WindowStyle.SingleBorderWindow;
            ResizeMode = ResizeMode.CanResize;
            WindowState = WindowState.Normal;
            Left = savedBounds.Left; Top = savedBounds.Top; Width = savedBounds.Width; Height = savedBounds.Height;
            if (savedState == WindowState.Maximized) WindowState = WindowState.Maximized;
        }
        Dispatcher.BeginInvoke(Sync, System.Windows.Threading.DispatcherPriority.Background);
    }

    /// <summary>Small window always on top in a corner (picture in picture).</summary>
    public void ToggleMini()
    {
        if (!Mini)
        {
            if (fullscreen) ToggleFullscreen();
            miniState = WindowState;
            WindowState = WindowState.Normal;
            miniSaved = new Rect(Left, Top, Width, Height);
            Mini = true;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            var area = SystemParameters.WorkArea;
            Width = 480; Height = 270;
            Left = area.Right - Width - 24;
            Top = area.Bottom - Height - 24;
        }
        else
        {
            Mini = false;
            Topmost = false;
            WindowStyle = WindowStyle.SingleBorderWindow;
            ResizeMode = ResizeMode.CanResize;
            Left = miniSaved.Left; Top = miniSaved.Top; Width = miniSaved.Width; Height = miniSaved.Height;
            if (miniState == WindowState.Maximized) WindowState = WindowState.Maximized;
        }
        Dispatcher.BeginInvoke(Sync, System.Windows.Threading.DispatcherPriority.Background);
    }

    /// <summary>Dragging the small window by the picture.</summary>
    public void DragMini()
    {
        try { if (Mouse.LeftButton == MouseButtonState.Pressed) DragMove(); } catch { }
    }
}
