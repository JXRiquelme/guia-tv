using System.IO.Pipes;
using System.Windows;
using Microsoft.Win32;

namespace TVOD;

public partial class App : Application
{
    const string PipeName = "TVOD-single-instance";
    static Mutex single;

    protected override void OnStartup(StartupEventArgs e)
    {
        // Google sign-in comes back as aeriolite://login#…: Windows opens a second TVOD with it,
        // which hands it to the one already running and closes.
        var link = e.Args.FirstOrDefault(a => a.StartsWith("aeriolite:", StringComparison.OrdinalIgnoreCase));
        single = new Mutex(true, "TVOD-single-mutex", out bool first);
        if (!first)
        {
            try
            {
                using var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                c.Connect(3000);
                using var w = new StreamWriter(c);
                w.WriteLine(link ?? "show");
            }
            catch { }
            Shutdown();
            return;
        }
        base.OnStartup(e);
        DispatcherUnhandledException += (_, ev) =>
        {
            ev.Handled = true;
            try { File.AppendAllText(Path.Combine(Store.Dir, "errores.log"), $"{DateTime.Now:s} {ev.Exception}\n\n"); } catch { }
        };
        RegisterLinkScheme();
        Bundled.Load();
        new Thread(ListenPipe) { IsBackground = true }.Start();
        var main = new MainWindow();
        MainWindow = main;
        main.Show();
        if (link != null) Dispatcher.BeginInvoke(() => Cloud.Shared.Complete(link));
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Cloud.Shared.Flush();
        base.OnExit(e);
    }

    void ListenPipe()
    {
        while (true)
        {
            try
            {
                using var s = new NamedPipeServerStream(PipeName, PipeDirection.In);
                s.WaitForConnection();
                using var r = new StreamReader(s);
                var msg = r.ReadLine() ?? "";
                Dispatcher.BeginInvoke(() =>
                {
                    if (MainWindow is MainWindow m) m.BringToFront();
                    if (msg.StartsWith("aeriolite:", StringComparison.OrdinalIgnoreCase)) Cloud.Shared.Complete(msg);
                });
            }
            catch { Thread.Sleep(500); }
        }
    }

    /// <summary>aeriolite:// links open this exe (only for the current Windows user, no admin needed).</summary>
    static void RegisterLinkScheme()
    {
        try
        {
            var exe = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "TVOD.exe");
            using var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\aeriolite");
            k.SetValue("", "URL:TVOD");
            k.SetValue("URL Protocol", "");
            using var cmd = k.CreateSubKey(@"shell\open\command");
            cmd.SetValue("", $"\"{exe}\" \"%1\"");
        }
        catch { }
    }
}
