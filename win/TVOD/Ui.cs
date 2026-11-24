using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace TVOD;

/// <summary>An item of a virtualized list: knows how to draw itself.</summary>
public class Row
{
    public Func<FrameworkElement> Build;
    public object Tag;
    public Row(Func<FrameworkElement> build, object tag = null) { Build = build; Tag = tag; }
}

/// <summary>Draws the Row it is given (lists recycle these, so rows are built only when on screen).</summary>
public class ViewHost : Border
{
    public ViewHost()
    {
        DataContextChanged += (_, _) => Child = (DataContext as Row)?.Build?.Invoke();
    }
}

public static class Ui
{
    public static readonly Brush White = Brushes.White;
    public static readonly Brush Dim = Frozen(Color.FromArgb(0xC8, 255, 255, 255));
    public static readonly Brush Faint = Frozen(Color.FromArgb(0x85, 255, 255, 255));
    public static readonly Brush Accent = Frozen(Color.FromRgb(0x93, 0x33, 0xEA));
    public static readonly Brush AccentLight = Frozen(Color.FromRgb(0xC0, 0x84, 0xFC));
    public static readonly Brush Gold = Frozen(Color.FromRgb(0xFF, 0xD2, 0x7A));
    public static readonly Brush PanelBg = Frozen(Color.FromArgb(0xEE, 0x10, 0x11, 0x14));
    public static readonly Brush Card = Frozen(Color.FromRgb(0x1C, 0x1D, 0x21));
    public static readonly Brush CardLight = Frozen(Color.FromRgb(0x26, 0x28, 0x2D));
    public static readonly Brush Glass = Frozen(Color.FromArgb(0xB8, 0x0E, 0x0F, 0x11));
    public static readonly Brush ButtonBg = Frozen(Color.FromArgb(0x26, 255, 255, 255));
    public static readonly Brush Line = Frozen(Color.FromArgb(0x22, 255, 255, 255));
    public static readonly Brush Hit = Frozen(Color.FromArgb(1, 0, 0, 0));

    static Brush Frozen(Color c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }

    public static TextBlock Txt(string text, double size = 15, Brush color = null, bool bold = false, int lines = 1)
    {
        var t = new TextBlock
        {
            Text = text ?? "", FontSize = size, Foreground = color ?? White,
            FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
            TextTrimming = TextTrimming.CharacterEllipsis,
            TextWrapping = lines > 1 ? TextWrapping.Wrap : TextWrapping.NoWrap,
        };
        if (lines > 1) t.MaxHeight = size * 1.36 * lines;
        return t;
    }

    public static Button Btn(object content, Action click, Brush bg = null, double size = 15)
    {
        var b = new Button { Content = content is string s ? Txt(s, size) : content, FontSize = size };
        if (bg != null) b.Background = bg;
        b.Click += (_, _) => click?.Invoke();
        return b;
    }

    public static StackPanel H(double gap, params UIElement[] items) => Stack(Orientation.Horizontal, gap, items);
    public static StackPanel V(double gap, params UIElement[] items) => Stack(Orientation.Vertical, gap, items);

    static StackPanel Stack(Orientation o, double gap, UIElement[] items)
    {
        var p = new StackPanel { Orientation = o };
        foreach (var it in items)
        {
            if (it == null) continue;
            if (p.Children.Count > 0 && it is FrameworkElement fe)
            {
                var m = fe.Margin;
                fe.Margin = o == Orientation.Horizontal ? new Thickness(m.Left + gap, m.Top, m.Right, m.Bottom) : new Thickness(m.Left, m.Top + gap, m.Right, m.Bottom);
            }
            p.Children.Add(it);
        }
        return p;
    }

    public static Border Box(UIElement child, Brush bg, double radius = 14, double pad = 0) =>
        new() { Child = child, Background = bg, CornerRadius = new CornerRadius(radius), Padding = new Thickness(pad) };

    /// <summary>Image filled in from the web (cached on disk), decoded at the size it is shown.</summary>
    public static Image Picture(string url, double w, double h, Stretch stretch = Stretch.UniformToFill)
    {
        var img = new Image { Width = w, Height = h, Stretch = stretch };
        RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
        ImageLoader.Set(img, url, (int)(w * 1.5));
        return img;
    }

    /// <summary>Rounded picture with a dark placeholder behind it.</summary>
    public static Border Poster(string url, double w, double h, double radius = 10, Stretch stretch = Stretch.UniformToFill)
    {
        var img = Picture(url, w, h, stretch);
        var b = new Border { Width = w, Height = h, CornerRadius = new CornerRadius(radius), Background = CardLight, ClipToBounds = true, Child = img };
        b.Loaded += (_, _) => b.Clip = new RectangleGeometry(new Rect(0, 0, w, h), radius, radius);
        return b;
    }

    /// <summary>Thin progress bar (0..1).</summary>
    public static Grid Progress(double frac, double width, double height = 4, Brush fill = null)
    {
        var g = new Grid { Width = width, Height = height };
        g.Children.Add(new Border { Background = Line, CornerRadius = new CornerRadius(height / 2) });
        g.Children.Add(new Border
        {
            Background = fill ?? Accent, CornerRadius = new CornerRadius(height / 2), HorizontalAlignment = HorizontalAlignment.Left,
            Width = Math.Max(0, Math.Min(1, frac)) * width
        });
        return g;
    }

    static DataTemplate template;
    /// <summary>The one item template every list uses: a ViewHost that draws its Row.</summary>
    public static DataTemplate RowTemplate => template ??= (DataTemplate)XamlReader.Parse(
        "<DataTemplate xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\" " +
        "xmlns:l=\"clr-namespace:TVOD;assembly=TVOD\"><l:ViewHost/></DataTemplate>");

    public static ListBox List()
    {
        var l = new ListBox { ItemTemplate = RowTemplate };
        VirtualizingPanel.SetIsVirtualizing(l, true);
        VirtualizingPanel.SetVirtualizationMode(l, VirtualizationMode.Recycling);
        VirtualizingPanel.SetScrollUnit(l, ScrollUnit.Pixel);
        ScrollViewer.SetHorizontalScrollBarVisibility(l, ScrollBarVisibility.Disabled);
        // the arrows are handled by the window, not by the list
        l.PreviewKeyDown += (_, e) => { if (e.Key is Key.Up or Key.Down or Key.Left or Key.Right or Key.Enter or Key.Space) e.Handled = false; };
        return l;
    }

    public static string Clock(double s)
    {
        var t = (int)Math.Max(0, s);
        return t >= 3600 ? $"{t / 3600}:{t % 3600 / 60:00}:{t % 60:00}" : $"{t / 60:00}:{t % 60:00}";
    }

    public static string HHmm(DateTime utc) => utc.ToLocalTime().ToString("HH:mm");
}

public static class ImageLoader
{
    static readonly SemaphoreSlim gate = new(6);
    static readonly Dictionary<string, BitmapSource> mem = new();
    static readonly LinkedList<string> order = new();
    static string dir;

    public static void Set(Image img, string url, int width)
    {
        img.Tag = url;
        img.Source = null;
        if (string.IsNullOrWhiteSpace(url) || !url.StartsWith("http", StringComparison.OrdinalIgnoreCase)) return;
        var key = url + "|" + width;
        if (mem.TryGetValue(key, out var bmp)) { img.Source = bmp; return; }
        _ = Load(img, url, width, key);
    }

    static string Hash(string s) => Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(s)));

    static async Task Load(Image img, string url, int width, string key)
    {
        try
        {
            dir ??= Directory.CreateDirectory(Path.Combine(Store.CacheDir, "img")).FullName;
            var file = Path.Combine(dir, Hash(url));
            byte[] data;
            if (File.Exists(file)) data = await File.ReadAllBytesAsync(file);
            else
            {
                await gate.WaitAsync();
                try
                {
                    if (!Equals(img.Tag, url)) return;   // scrolled away while waiting
                    data = await Net.Get(url);
                }
                finally { gate.Release(); }
                try { await File.WriteAllBytesAsync(file, data); } catch { }
            }
            var bmp = await Task.Run(() => Decode(data, width));
            if (bmp == null) return;
            if (!mem.ContainsKey(key))
            {
                mem[key] = bmp;
                order.AddLast(key);
                while (order.Count > 900) { mem.Remove(order.First.Value); order.RemoveFirst(); }
            }
            if (Equals(img.Tag, url)) img.Source = bmp;
        }
        catch { }
    }

    static BitmapSource Decode(byte[] data, int width)
    {
        try
        {
            var b = new BitmapImage();
            b.BeginInit();
            b.CacheOption = BitmapCacheOption.OnLoad;
            b.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            b.StreamSource = new MemoryStream(data);
            if (width > 0) b.DecodePixelWidth = width;
            b.EndInit();
            b.Freeze();
            return b;
        }
        catch { return null; }
    }
}
