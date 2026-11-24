using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace TVOD;

public partial class Shell
{
    Grid dialogLayer;
    Border dialogCard;
    readonly Stack<(string kind, FrameworkElement view)> dialogStack = new();
    Action RefreshAccountRow;
    bool dialogShowsSetup => dialogStack.Count > 0 && dialogStack.Peek().kind == "setup";

    void BuildDialogLayer()
    {
        dialogCard = new Border
        {
            Background = Ui.Card, CornerRadius = new CornerRadius(18), Padding = new Thickness(26, 22, 26, 22),
            MaxWidth = 660, MinWidth = 380, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(24), BorderBrush = Ui.Line, BorderThickness = new Thickness(1)
        };
        dialogCard.MouseLeftButtonDown += (_, e) => e.Handled = true;
        dialogLayer = new Grid { Background = new SolidColorBrush(Color.FromArgb(0xB0, 0, 0, 0)), Visibility = Visibility.Collapsed };
        dialogLayer.Children.Add(dialogCard);
        dialogLayer.MouseLeftButtonDown += (_, e) => { e.Handled = true; if (!dialogShowsSetup) CloseDialog(); };
        root.Children.Add(dialogLayer);
        SizeChanged += (_, _) => dialogCard.MaxHeight = Math.Max(200, ActualHeight - 60);
    }

    /// <summary>Shows a card with a title, a body and buttons (each closes the card unless it returns false).</summary>
    void ShowDialog(string kind, string title, UIElement body, params (string text, Func<bool> action)[] buttons)
    {
        var stack = new StackPanel();
        if (!string.IsNullOrEmpty(title))
        {
            var t = Ui.Txt(title, 22, Ui.White, true, 2);
            t.Margin = new Thickness(0, 0, 0, 14);
            stack.Children.Add(t);
        }
        if (body != null) stack.Children.Add(body);
        if (buttons.Length > 0)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 20, 0, 0) };
            for (int i = 0; i < buttons.Length; i++)
            {
                var (text, action) = buttons[i];
                var b = Ui.Btn(text, () => { if (action == null || action()) CloseDialog(); }, i == 0 ? Ui.Accent : null);
                b.Margin = new Thickness(10, 0, 0, 0);
                row.Children.Add(b);
            }
            stack.Children.Add(row);
        }
        var view = new ScrollViewer { Content = stack, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        dialogStack.Push((kind, view));
        dialogCard.Child = view;
        Show(dialogLayer, true);
        Cursor = null;
        Activate();
    }

    void CloseDialog()
    {
        if (dialogStack.Count == 0) return;
        dialogStack.Pop();
        if (dialogStack.Count > 0) dialogCard.Child = dialogStack.Peek().view;
        else
        {
            dialogCard.Child = null;
            Show(dialogLayer, false);
            RefreshAccountRow = null;
            Keyboard.Focus(this);
        }
    }

    void CloseAllDialogs() { while (dialogStack.Count > 0) CloseDialog(); }

    static Button MenuButton(string text, bool selected, Action a)
    {
        var b = Ui.Btn(Ui.Txt((selected ? "●  " : "    ") + text, 16, selected ? Ui.AccentLight : Ui.White, selected), a);
        b.HorizontalContentAlignment = HorizontalAlignment.Left;
        b.Padding = new Thickness(16, 10, 16, 10);
        b.Margin = new Thickness(0, 0, 0, 6);
        return b;
    }

    static TextBlock Label(string text)
    {
        var t = Ui.Txt(text, 13, Ui.Faint, true);
        t.Margin = new Thickness(0, 18, 0, 8);
        return t;
    }

    // ------------------------------------------------------------ channel options, tracks, format

    void ShowOptionsMenu()
    {
        var ch = CurrentChannel;
        if (ch == null || vod != null) return;
        HideInfo();
        var list = new StackPanel();
        var sel = AltIndex(ch);
        var opts = ch.Options;
        for (int i = 0; i < opts.Count; i++)
        {
            var k = i;
            list.Children.Add(MenuButton(opts[i].Label, i == sel, () => { CloseAllDialogs(); ChooseOption(k); }));
        }
        var note = Ui.Txt("La opción elegida queda guardada para este canal (y viaja con tu cuenta de Google).", 13, Ui.Faint, false, 3);
        note.Margin = new Thickness(0, 8, 0, 0);
        list.Children.Add(note);
        ShowDialog("menu", ch.Name, list, ("Cerrar", null));
    }

    void ShowTracksMenu()
    {
        if (mpv == null) return;
        var list = new StackPanel();
        list.Children.Add(Label("AUDIO"));
        var audio = mpv.Tracks("audio");
        if (audio.Count == 0) list.Children.Add(Ui.Txt("Sin pistas de audio", 15, Ui.Faint));
        foreach (var t in audio) { var id = t.Id; list.Children.Add(MenuButton(t.Title, t.Selected, () => { mpv.SelectAudio(id); CloseDialog(); })); }
        list.Children.Add(Label("SUBTÍTULOS"));
        var subs = mpv.Tracks("sub");
        list.Children.Add(MenuButton("Sin subtítulos", subs.All(s => !s.Selected), () => { mpv.SelectSubtitle(null); CloseDialog(); }));
        foreach (var t in subs) { var id = t.Id; list.Children.Add(MenuButton(t.Title, t.Selected, () => { mpv.SelectSubtitle(id); CloseDialog(); })); }
        ShowDialog("menu", "Audio y subtítulos", list, ("Cerrar", null));
    }

    void ShowAspectMenu()
    {
        var list = new StackPanel();
        foreach (var (label, value) in new[] { ("Original", "no"), ("16:9", "16:9"), ("4:3", "4:3"), ("21:9 (cine)", "2.35:1") })
        {
            var v = value;
            list.Children.Add(MenuButton(label, aspect == v, () => { aspect = v; mpv?.SetAspect(v); CloseDialog(); }));
        }
        ShowDialog("menu", "Formato de imagen", list, ("Cerrar", null));
    }

    // ------------------------------------------------------------ setup: list address and Xtream account

    void OpenSetup(string message)
    {
        mpv?.Stop();
        var box = new TextBox { Text = ListUrl.Length > 0 ? ListUrl : "https://tinyurl.com/" };
        var msg = Ui.Txt(message ?? "Escribe la dirección de tu lista de canales (la misma del Roku).", 15, message != null ? Ui.Gold : Ui.Dim, false, 4);
        msg.Margin = new Thickness(0, 0, 0, 12);
        var body = Ui.V(0, msg, box);
        ShowDialog("setup", "Lista de canales", body, ("Guardar", () =>
        {
            var url = box.Text.Trim();
            if (!url.StartsWith("http") || url.Length < 12) { msg.Text = "La dirección debe empezar con https://"; msg.Foreground = Ui.Gold; return false; }
            Store.Set("list", url);
            try { File.Delete(ListFile); } catch { }
            channels = new();
            dialogStack.Clear();
            Show(dialogLayer, false);
            StartLive();
            return false;
        }));
        box.Focus();
        box.SelectAll();
    }

    void OpenAccount(bool thenSeries)
    {
        var acc = Xtream() ?? new XtreamAccount { Base = "https://" };
        var server = new TextBox { Text = acc.Base };
        var user = new TextBox { Text = acc.User };
        var pass = new TextBox { Text = acc.Pass };
        var msg = Ui.Txt("Para ver películas y series escribe tu cuenta (se guarda solo en este PC).", 15, Ui.Dim, false, 3);
        var body = Ui.V(8, msg, Label("SERVIDOR"), server, Label("USUARIO"), user, Label("CLAVE"), pass);
        ShowDialog("account", "Cuenta del servicio", body, ("Guardar", () =>
        {
            var a = new XtreamAccount { Base = server.Text.Trim(), User = user.Text.Trim(), Pass = pass.Text.Trim() };
            if (!a.Valid) { msg.Text = "Completa servidor (https://…), usuario y clave."; msg.Foreground = Ui.Gold; return false; }
            Store.Set("xtream", JsonSerializer.Serialize(a));
            categories.Clear();
            itemCache.Clear();
            catalogCache.Clear();
            CloseAllDialogs();
            OpenBrowser(thenSeries);
            return false;
        }), ("Cancelar", null));
    }

    // ------------------------------------------------------------ settings

    void OpenSettings()
    {
        HideInfo();
        var body = new StackPanel();

        // Google account
        body.Children.Add(Label("CUENTA DE GOOGLE"));
        var accountHost = new StackPanel();
        body.Children.Add(accountHost);
        void RenderAccount()
        {
            accountHost.Children.Clear();
            var c = Cloud.Shared;
            if (c.SignedIn)
            {
                accountHost.Children.Add(Ui.Txt(c.Email ?? "Sesión iniciada", 17, Ui.White, true));
                var state = c.Busy ? "Sincronizando…" : c.LastError != null ? $"Error al sincronizar: {c.LastError}"
                    : c.LastOK is DateTime t ? $"Sincronizado a las {t:HH:mm:ss}" : "Seguir viendo, Ver más tarde y las opciones de canales se sincronizan con tus otros equipos.";
                var st = Ui.Txt(state, 13, c.LastError != null ? Ui.Gold : Ui.Faint, false, 3);
                st.Margin = new Thickness(0, 4, 0, 10);
                accountHost.Children.Add(st);
                accountHost.Children.Add(Ui.H(8,
                    Ui.Btn("Sincronizar ahora", async () => { var e = await Cloud.Shared.SyncNow(); if (e != null) Toast("No se pudo sincronizar: " + e, 5); RenderAccount(); }),
                    Ui.Btn("Cerrar sesión", () => { Cloud.Shared.SignOut(); RenderAccount(); })));
            }
            else
            {
                var t = Ui.Txt("Inicia sesión para tener Seguir viendo, Ver más tarde y las opciones de canales iguales en la TV, el teléfono, el Mac y este PC.", 14, Ui.Dim, false, 4);
                t.Margin = new Thickness(0, 0, 0, 10);
                accountHost.Children.Add(t);
                var b = Ui.Btn("Iniciar sesión con Google", () =>
                {
                    Cloud.Shared.SignIn(err =>
                    {
                        if (err != null) Toast(err, 6);
                        else { Toast("Sesión iniciada: " + Cloud.Shared.Email, 4); history = Store.Load<List<Watch>>("history") ?? new(); later = Store.Load<List<VodItem>>("later") ?? new(); }
                        RenderAccount();
                    });
                    Toast("Se abrió el navegador: elige tu cuenta y acepta abrir TVOD.", 6);
                }, Ui.Accent);
                b.HorizontalAlignment = HorizontalAlignment.Left;
                accountHost.Children.Add(b);
            }
        }
        RenderAccount();
        RefreshAccountRow = RenderAccount;

        // languages
        body.Children.Add(Label("IDIOMA DEL AUDIO (PELÍCULAS Y SERIES)"));
        var audioRow = new WrapPanel();
        void RenderAudio()
        {
            audioRow.Children.Clear();
            foreach (var (label, v) in new[] { ("Español", "es"), ("Inglés", "en"), ("Original", "orig") })
            {
                var val = v;
                var b = Ui.Btn(label, () => { audioLang = val; Store.Set("audio_lang", val); ApplyLanguages(); RenderAudio(); }, audioLang == v ? Ui.Accent : null);
                b.Margin = new Thickness(0, 0, 8, 8);
                audioRow.Children.Add(b);
            }
        }
        RenderAudio();
        body.Children.Add(audioRow);
        body.Children.Add(Label("SUBTÍTULOS"));
        var subRow = new WrapPanel();
        void RenderSubs()
        {
            subRow.Children.Clear();
            foreach (var (label, v) in new[] { ("No", "off"), ("Español", "es"), ("Inglés", "en") })
            {
                var val = v;
                var b = Ui.Btn(label, () => { subLang = val; Store.Set("sub_lang", val); ApplyLanguages(); RenderSubs(); }, subLang == v ? Ui.Accent : null);
                b.Margin = new Thickness(0, 0, 8, 8);
                subRow.Children.Add(b);
            }
        }
        RenderSubs();
        body.Children.Add(subRow);

        // view
        body.Children.Add(Label("PANTALLA"));
        var stats = new CheckBox { Content = "Mostrar datos técnicos (resolución, velocidad, buffer)", IsChecked = showStats };
        stats.Click += (_, _) => { showStats = stats.IsChecked == true; Store.SetBool("show_stats", showStats); UpdateStats(); };
        body.Children.Add(stats);

        // hidden things
        body.Children.Add(Label("OCULTAR"));
        var hideRow = new WrapPanel();
        foreach (var (label, act) in new (string, Action)[]
                 {
                     ("Canales…", OpenChannelFilter),
                     ("Categorías de películas…", () => OpenCategoryFilter(false)),
                     ("Categorías de series…", () => OpenCategoryFilter(true)),
                 })
        {
            var b = Ui.Btn(label, act);
            b.Margin = new Thickness(0, 0, 8, 8);
            hideRow.Children.Add(b);
        }
        body.Children.Add(hideRow);

        // service
        body.Children.Add(Label("SERVICIO"));
        var svcRow = new WrapPanel();
        var l1 = Ui.Btn("Lista de canales…", () => OpenSetup(null));
        l1.Margin = new Thickness(0, 0, 8, 8);
        var l2 = Ui.Btn("Cuenta de películas y series…", () => OpenAccount(browserSeries));
        l2.Margin = new Thickness(0, 0, 8, 8);
        var l3 = Ui.Btn("Actualizar guía", () => { RefreshGuide(true); Toast("Actualizando la guía…"); });
        l3.Margin = new Thickness(0, 0, 8, 8);
        svcRow.Children.Add(l1);
        svcRow.Children.Add(l2);
        svcRow.Children.Add(l3);
        body.Children.Add(svcRow);

        var keys = Ui.Txt("Teclado: ↑↓ cambia de canal · ← lista · → guía · O opción de señal · F pantalla completa · Esc atrás · rueda del mouse volumen. " +
                          "En películas: espacio pausa · ←→ 10 s.", 13, Ui.Faint, false, 4);
        keys.Margin = new Thickness(0, 18, 0, 0);
        body.Children.Add(keys);
        var ver = Ui.Txt("TVOD para Windows " + (typeof(Shell).Assembly.GetName().Version?.ToString(3) ?? ""), 12, Ui.Faint);
        ver.Margin = new Thickness(0, 8, 0, 0);
        body.Children.Add(ver);

        ShowDialog("settings", "Configuración", body, ("Listo", null));
    }

    void OpenChannelFilter()
    {
        var list = new StackPanel();
        var note = Ui.Txt("Desmarca los canales que no quieras ver en la lista, la guía ni al cambiar de canal.", 14, Ui.Dim, false, 3);
        note.Margin = new Thickness(0, 0, 0, 10);
        list.Children.Add(note);
        foreach (var ch in channels)
        {
            var name = ch.Name;
            var cb = new CheckBox { Content = $"{ch.Number}  {ch.Name}", IsChecked = !hiddenChannels.Contains(name) };
            cb.Click += (_, _) => { ToggleHiddenChannel(name); };
            list.Children.Add(cb);
        }
        ShowDialog("filter", "Canales visibles", list, ("Listo", () => { if (Visible(panel)) RenderPanel(); return true; }));
    }

    void OpenCategoryFilter(bool series)
    {
        if (!categories.ContainsKey(series))
        {
            Toast(Xtream() == null ? "Primero configura la cuenta de películas y series." : "Abre " + (series ? "Series" : "Películas") + " una vez para cargar sus categorías.");
            return;
        }
        var set = HiddenCats(series);
        var list = new StackPanel();
        var note = Ui.Txt("Desmarca las categorías que no quieras ver (tampoco aparecen en la búsqueda).", 14, Ui.Dim, false, 3);
        note.Margin = new Thickness(0, 0, 0, 10);
        list.Children.Add(note);
        foreach (var c in categories[series])
        {
            var id = c.Id;
            var cb = new CheckBox { Content = c.Name, IsChecked = !set.Contains(id) };
            cb.Click += (_, _) =>
            {
                if (cb.IsChecked == true) set.Remove(id); else set.Add(id);
                Store.SetSet(series ? "hide_series" : "hide_vod", set);
            };
            list.Children.Add(cb);
        }
        ShowDialog("filter", series ? "Categorías de series" : "Categorías de películas", list, ("Listo", () =>
        {
            if (browserOpen && browserSeries == series)
            {
                BuildChips();
                var sel = selectedCat.GetValueOrDefault(series);
                if (sel == null || set.Contains(sel)) SelectCategory(DefaultCategory()?.Id); else MarkChips();
            }
            return true;
        }));
    }
}
