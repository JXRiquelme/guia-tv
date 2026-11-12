' AerioTV VOD para Roku: películas y series con los datos del servicio (servidor, usuario, contraseña).
' Todo se pide de a poco (categorías, luego los títulos de una categoría, luego los capítulos de una
' serie), así nunca se descarga el catálogo completo.

sub init()
    ' fixed name: reinstalling the app keeps the account, "Continuar viendo" and "Ver más tarde"
    m.reg = CreateObject("roRegistrySection", "aeriovod")
    m.login = m.top.findNode("login")
    m.loginList = m.top.findNode("loginList")
    m.loginStatus = m.top.findNode("loginStatus")
    m.home = m.top.findNode("home")
    m.tabMark = m.top.findNode("tabMark")
    m.tabLive = m.top.findNode("tabLive")
    m.tabMovies = m.top.findNode("tabMovies")
    m.channels = m.top.findNode("channels")
    m.osdLogo = m.top.findNode("osdLogo")
    m.osdHelp = m.top.findNode("osdHelp")
    m.guideTimer = m.top.findNode("guideTimer")
    m.tabSeries = m.top.findNode("tabSeries")
    m.cats = m.top.findNode("cats")
    m.grid = m.top.findNode("grid")
    m.gridStatus = m.top.findNode("gridStatus")
    m.seriesView = m.top.findNode("seriesView")
    m.seriesPoster = m.top.findNode("seriesPoster")
    m.seriesTitle = m.top.findNode("seriesTitle")
    m.seriesPlot = m.top.findNode("seriesPlot")
    m.seasons = m.top.findNode("seasons")
    m.episodes = m.top.findNode("episodes")
    m.video = m.top.findNode("video")
    m.osd = m.top.findNode("osd")
    m.osdTitle = m.top.findNode("osdTitle")
    m.osdState = m.top.findNode("osdState")
    m.osdBar = m.top.findNode("osdBar")
    m.osdTime = m.top.findNode("osdTime")
    m.osdLeft = m.top.findNode("osdLeft")
    m.toast = m.top.findNode("toast")
    m.toastBg = m.top.findNode("toastBg")
    m.catTimer = m.top.findNode("catTimer")
    m.toastTimer = m.top.findNode("toastTimer")
    m.osdTimer = m.top.findNode("osdTimer")

    m.loginList.observeField("itemSelected", "onLoginSelected")
    m.cats.observeField("itemFocused", "onCatFocused")
    m.cats.observeField("itemSelected", "onCatSelected")
    m.grid.observeField("itemSelected", "onGridSelected")
    m.channels.observeField("itemSelected", "onChannelSelected")
    m.guideTimer.observeField("fire", "refreshNow")
    m.seasons.observeField("itemFocused", "onSeasonFocused")
    m.seasons.observeField("itemSelected", "onSeasonSelected")
    m.episodes.observeField("itemSelected", "onEpisodeSelected")
    m.video.observeField("state", "onVideoState")
    m.video.observeField("position", "onVideoPosition")
    m.catTimer.observeField("fire", "onCatTimer")
    m.toastTimer.observeField("fire", "hideToast")
    m.osdTimer.observeField("fire", "onOsdTick")

    m.server = readReg("server")
    m.user = readReg("user")
    m.pass = readReg("pass")
    m.list = readReg("list")
    if m.server = "" or m.user = "" or m.pass = "" or m.list = "" then loadDefaults()
    m.mode = readReg("mode")
    if m.mode <> "series" and m.mode <> "movies" then m.mode = "live"
    m.tabCursor = m.mode
    m.liveMode = false
    m.channelsNode = invalid
    m.guide = invalid
    m.searchResults = invalid
    m.searchText = ""
    m.cache = {}
    m.cacheOrder = []
    m.catIds = []
    m.tasks = {}
    m.returnTo = "home"
    m.lastSaved = 0
    m.tabsFocused = false
    m.osdHideAt = 0
    m.clock = CreateObject("roTimespan")

    if m.server <> "" and m.user <> "" and m.pass <> "" then
        showHome()
    else
        showLogin("")
    end if
end sub

' The installed package may carry the account (pkg:/source/account.json, never published), so a
' fresh install starts signed in.
sub loadDefaults()
    raw = ReadAsciiFile("pkg:/source/account.json")
    if raw = "" then return
    a = ParseJson(raw)
    if type(a) <> "roAssociativeArray" then return
    if a.server <> invalid and m.server = "" then m.server = a.server
    if a.user <> invalid and m.user = "" then m.user = a.user
    if a.pass <> invalid and m.pass = "" then m.pass = a.pass
    if a.list <> invalid and m.list = "" then m.list = a.list
end sub

' ------------------------------------------------------------------ sign in

sub showLogin(status as string)
    m.home.visible = false
    m.seriesView.visible = false
    m.login.visible = true
    m.loginStatus.text = status
    refreshLoginList()
    m.loginList.setFocus(true)
end sub

sub refreshLoginList()
    root = CreateObject("roSGNode", "ContentNode")
    srv = m.server
    if srv = "" then srv = "(toca para escribir)"
    usr = m.user
    if usr = "" then usr = "(toca para escribir)"
    pwd = "(toca para escribir)"
    if m.pass <> "" then pwd = String(Len(m.pass), "*")
    lst = m.list
    if lst = "" then lst = "(toca para escribir)"
    for each t in ["Servidor:  " + srv, "Usuario:  " + usr, "Contraseña:  " + pwd, "Lista de canales:  " + lst, "Conectar"]
        n = root.createChild("ContentNode")
        n.title = t
    end for
    focused = m.loginList.itemFocused
    m.loginList.content = root
    if focused > 0 then m.loginList.jumpToItem = focused
end sub

sub onLoginSelected()
    i = m.loginList.itemSelected
    if i = 0 then
        value = m.server
        if value = "" then value = "http://"
        askText("Dirección del servidor", value, "server")
    else if i = 1 then
        askText("Usuario", m.user, "user")
    else if i = 2 then
        askText("Contraseña", m.pass, "pass")
    else if i = 3 then
        value = m.list
        if value = "" then value = "https://tinyurl.com/"
        askText("Enlace de la lista de canales", value, "list")
    else
        connect()
    end if
end sub

sub askText(title as string, value as string, field as string)
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "KeyboardDialog")
    dlg.title = title
    dlg.text = value
    dlg.buttons = ["Aceptar", "Cancelar"]
    dlg.observeField("buttonSelected", "onKeyboardButton")
    m.kbField = field
    m.kb = dlg
    m.top.dialog = dlg
end sub

sub onKeyboardButton()
    dlg = m.kb
    if dlg = invalid then return
    if dlg.buttonSelected = 0 then
        value = dlg.text.Trim()
        if m.kbField = "server" then m.server = value
        if m.kbField = "user" then m.user = value
        if m.kbField = "pass" then m.pass = value
        if m.kbField = "list" then m.list = value
        if m.kbField = "search" then m.searchText = value
    end if
    dlg.close = true
    m.kb = invalid
    if m.kbField = "search" then
        if dlg.buttonSelected = 0 then runSearch() else m.cats.setFocus(true)
        return
    end if
    refreshLoginList()
    m.loginList.setFocus(true)
end sub

sub connect()
    m.server = normalizeServer(m.server)
    if m.server = "" or m.user = "" or m.pass = "" then
        m.loginStatus.text = "Completa servidor, usuario y contraseña."
        return
    end if
    m.loginStatus.text = "Conectando..."
    refreshLoginList()
    runTask("auth", "", "onAuth")
end sub

sub onAuth(ev as object)
    t = ev.getRoSGNode()
    if ev.getField() = "error" then
        m.loginStatus.text = t.error
        return
    end if
    r = t.result
    if r = invalid then return
    if r.ok then
        writeReg("server", m.server)
        writeReg("user", m.user)
        writeReg("pass", m.pass)
        writeReg("list", m.list)
        m.channelsNode = invalid
        m.login.visible = false
        m.cache = {}
        m.cacheOrder = []
        showHome()
    else
        m.loginStatus.text = r.msg
    end if
end sub

function normalizeServer(s as string) as string
    s = s.Trim()
    if s = "" or s = "http://" or s = "https://" then return ""
    if LCase(Left(s, 4)) <> "http" then s = "http://" + s
    p = Instr(1, s, "://")
    if p > 0 then
        slash = Instr(p + 3, s, "/")
        if slash > 0 then s = Left(s, slash - 1)
    end if
    return s
end function

' ------------------------------------------------------------------ catalog

sub showHome()
    m.login.visible = false
    m.seriesView.visible = false
    m.video.visible = false
    m.home.visible = true
    paintTabs()
    if m.mode = "live" then
        showLive()
        return
    end if
    m.channels.visible = false
    m.cats.visible = true
    m.grid.visible = true
    m.guideTimer.control = "stop"
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    m.gridStatus.text = "Cargando categorías..."
    loading = CreateObject("roSGNode", "ContentNode")
    n = loading.createChild("ContentNode")
    n.title = "Cargando..."
    m.cats.content = loading
    m.catIds = [""]
    if m.tabsFocused then m.top.setFocus(true) else m.cats.setFocus(true)
    if m.mode = "series" then runTask("seriescats", "", "onCats") else runTask("vodcats", "", "onCats")
end sub

function tabNode(mode as string) as object
    if mode = "series" then return m.tabSeries
    if mode = "movies" then return m.tabMovies
    return m.tabLive
end function

' the mark sits on the active section; while the tabs have the focus it follows the cursor (bright)
sub paintTabs()
    for each mode in ["live", "movies", "series"]
        t = tabNode(mode)
        if mode = m.mode then t.color = "0xFFFFFFFF" else t.color = "0x8A93AFFF"
    end for
    if m.tabsFocused then
        m.tabMark.translation = tabNode(m.tabCursor).translation
        m.tabMark.color = "0x2563EBFF"
    else
        m.tabMark.translation = tabNode(m.mode).translation
        m.tabMark.color = "0x2563EB55"
    end if
end sub

sub focusTabs(on as boolean)
    m.tabsFocused = on
    m.tabCursor = m.mode
    paintTabs()
    if on then
        m.top.setFocus(true)
    else if m.mode = "live" then
        m.channels.setFocus(true)
    else
        m.cats.setFocus(true)
    end if
end sub

sub moveTabCursor(delta as integer)
    order = ["live", "movies", "series"]
    i = 0
    for j = 0 to 2
        if order[j] = m.tabCursor then i = j
    end for
    i = i + delta
    if i < 0 then i = 0
    if i > 2 then i = 2
    m.tabCursor = order[i]
    paintTabs()
end sub

sub switchMode(mode as string)
    m.tabsFocused = false
    if mode = m.mode then
        focusTabs(false)
        return
    end if
    m.mode = mode
    writeReg("mode", m.mode)
    showHome()
end sub

sub onCats(ev as object)
    t = ev.getRoSGNode()
    if ev.getField() = "error" then
        m.gridStatus.text = t.error + Chr(10) + "Pulsa * para revisar los datos del servicio."
        return
    end if
    r = t.result
    if r = invalid then return
    root = CreateObject("roSGNode", "ContentNode")
    n = root.createChild("ContentNode")
    n.title = "Buscar"
    n = root.createChild("ContentNode")
    n.title = "Continuar viendo"
    n = root.createChild("ContentNode")
    n.title = "★  Ver más tarde"
    m.catIds = ["__search", "__continue", "__later"]
    m.searchResults = invalid
    for i = 0 to r.getChildCount() - 1
        c = r.getChild(i)
        n = root.createChild("ContentNode")
        n.title = c.title
        m.catIds.push(c.id)
    end for
    m.cats.content = root
    m.gridStatus.text = ""
    ' open on "Continuar viendo" when there is something to continue, else on the first category
    start = 1
    if historyFor(m.mode).Count() = 0 then
        start = 3
        if laterFor(m.mode).Count() > 0 then start = 2
        if start >= m.catIds.Count() then start = 1
    end if
    m.cats.jumpToItem = start
    if not m.tabsFocused then m.cats.setFocus(true)
    loadCategory(start)
end sub

sub onCatFocused()
    m.catTimer.control = "stop"
    m.catTimer.control = "start"
end sub

sub onCatTimer()
    loadCategory(m.cats.itemFocused)
end sub

sub onCatSelected()
    if m.catIds[m.cats.itemSelected] = "__search" then
        what = "películas"
        if m.mode = "series" then what = "series"
        askText("Buscar " + what, m.searchText, "search")
        return
    end if
    if m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then m.grid.setFocus(true)
end sub

sub runSearch()
    if m.searchText = "" then
        m.cats.setFocus(true)
        return
    end if
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    m.gridStatus.text = "Buscando «" + m.searchText + "»..." + Chr(10) + "La primera búsqueda descarga el catálogo completo y puede tardar un poco."
    m.searchMode = m.mode
    t = CreateObject("roSGNode", "ApiTask")
    t.server = m.server
    t.user = m.user
    t.pass = m.pass
    t.kind = "search"
    t.mode = m.mode
    t.param = m.searchText
    t.observeField("result", "onSearch")
    t.observeField("error", "onSearch")
    m.tasks["search"] = t
    t.control = "RUN"
    m.cats.setFocus(true)
end sub

sub onSearch(ev as object)
    t = ev.getRoSGNode()
    if t.mode <> m.mode or m.currentCat <> "__search" then return
    if ev.getField() = "error" then
        m.gridStatus.text = t.error
        return
    end if
    if t.result = invalid then return
    m.searchResults = t.result
    setGrid(t.result, "Nada encontrado para «" + m.searchText + "».")
    if t.result.getChildCount() > 0 then m.grid.setFocus(true)
end sub

sub loadCategory(i as integer)
    if i < 0 or i >= m.catIds.Count() then return
    id = m.catIds[i]
    if id = "" then return
    m.currentCat = id
    if id = "__search" then
        if m.searchResults <> invalid then
            setGrid(m.searchResults, "Nada encontrado.")
        else
            m.grid.content = CreateObject("roSGNode", "ContentNode")
            m.gridStatus.text = "Pulsa OK para buscar por nombre."
        end if
        return
    end if
    if id = "__continue" then
        setGrid(continueContent(), "Aún no has visto nada.")
        return
    end if
    if id = "__later" then
        setGrid(laterContent(), "Vacío. En cualquier título pulsa * y elige «Agregar a Ver más tarde».")
        return
    end if
    key = m.mode + ":" + id
    if m.cache.DoesExist(key) then
        setGrid(m.cache[key], "Esta categoría está vacía.")
        return
    end if
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    m.gridStatus.text = "Cargando..."
    m.loadingKey = key
    if m.mode = "series" then runTask("series", id, "onCategory") else runTask("vod", id, "onCategory")
end sub

sub onCategory(ev as object)
    t = ev.getRoSGNode()
    key = "movies:" + t.param
    if t.kind = "series" then key = "series:" + t.param
    if ev.getField() = "error" then
        if key = m.loadingKey then m.gridStatus.text = t.error
        return
    end if
    r = t.result
    if r = invalid then return
    ' keep the last categories in memory (going back to one is instant)
    m.cache[key] = r
    m.cacheOrder.push(key)
    if m.cacheOrder.Count() > 8 then
        old = m.cacheOrder.Shift()
        if old <> key then m.cache.Delete(old)
    end if
    if key = m.loadingKey then setGrid(r, "Esta categoría está vacía.")
end sub

sub setGrid(content as object, emptyText as string)
    m.grid.content = content
    if content.getChildCount() = 0 then m.gridStatus.text = emptyText else m.gridStatus.text = ""
end sub

sub refreshSpecialCategory()
    if m.currentCat = "__continue" then setGrid(continueContent(), "Aún no has visto nada.")
    if m.currentCat = "__later" then setGrid(laterContent(), "Vacío. En cualquier título pulsa * y elige «Agregar a Ver más tarde».")
end sub

function focusedGridItem() as dynamic
    c = m.grid.content
    if c = invalid then return invalid
    return c.getChild(m.grid.itemFocused)
end function

sub onGridSelected()
    c = m.grid.content
    if c = invalid then return
    item = c.getChild(m.grid.itemSelected)
    if item = invalid then return
    if item.kind = "series" then
        openSeries(item.seriesId, item.title, item.hdPosterUrl)
    else
        m.returnTo = "home"
        playItem({ kind: item.kind, streamId: item.streamId, ext: item.ext, title: item.title, poster: item.hdPosterUrl, seriesId: item.seriesId })
    end if
end sub

' ------------------------------------------------------------------ live channels

sub showLive()
    m.cats.visible = false
    m.grid.visible = false
    m.channels.visible = true
    m.gridStatus.text = ""
    m.guideTimer.control = "start"
    if m.guide = invalid then runTask("guide", "", "onGuide")
    if m.channelsNode <> invalid then
        m.channels.content = m.channelsNode
        refreshNow()
        m.channels.jumpToItem = lastChannel()
        if not m.tabsFocused then m.channels.setFocus(true)
        return
    end if
    if m.list = "" then
        m.gridStatus.text = "Falta el enlace de tu lista de canales." + Chr(10) + "Pulsa * y elige «Cambiar servidor o usuario»."
        if not m.tabsFocused then m.top.setFocus(true)
        return
    end if
    m.gridStatus.text = "Cargando canales..."
    m.channels.content = CreateObject("roSGNode", "ContentNode")
    if not m.tabsFocused then m.channels.setFocus(true)
    runTask("m3u", m.list, "onM3u")
end sub

sub onM3u(ev as object)
    t = ev.getRoSGNode()
    if ev.getField() = "error" then
        if m.mode = "live" then m.gridStatus.text = t.error
        return
    end if
    if t.result = invalid then return
    m.channelsNode = t.result
    if m.mode <> "live" then return
    m.gridStatus.text = ""
    if m.channelsNode.getChildCount() = 0 then m.gridStatus.text = "La lista no tiene canales."
    refreshNow()
    m.channels.content = m.channelsNode
    m.channels.jumpToItem = lastChannel()
    if not m.tabsFocused then m.channels.setFocus(true)
end sub

sub onGuide(ev as object)
    t = ev.getRoSGNode()
    if ev.getField() = "error" or t.result = invalid then return
    m.guide = t.result.guide
    refreshNow()
end sub

function lastChannel() as integer
    i = Val(readReg("lastChannel"))
    if m.channelsNode = invalid then return 0
    if i < 0 or i >= m.channelsNode.getChildCount() then return 0
    return Int(i)
end function

' "Ahora: … · Luego: …" under every channel
sub refreshNow()
    if m.channelsNode = invalid then return
    for i = 0 to m.channelsNode.getChildCount() - 1
        ch = m.channelsNode.getChild(i)
        pr = nowNext(ch.tvg)
        text = ""
        if pr.now <> invalid then
            text = hhmm(pr.now[0]) + "  " + pr.now[2]
            if pr.next <> invalid then text = text + "     ·     " + hhmm(pr.next[0]) + "  " + pr.next[2]
        end if
        ch.description = text
    end for
end sub

function nowNext(tvg as string) as object
    out = { now: invalid, next: invalid }
    if m.guide = invalid or tvg = "" then return out
    list = m.guide[tvg]
    if list = invalid then return out
    now = CreateObject("roDateTime").AsSeconds()
    for i = 0 to list.Count() - 1
        p = list[i]
        if p[0] <= now and now < p[1] then
            out.now = p
            if i + 1 < list.Count() then out.next = list[i + 1]
            return out
        end if
        if p[0] > now then
            out.next = p
            return out
        end if
    end for
    return out
end function

function hhmm(secs as dynamic) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(Int(secs))
    d.ToLocalTime()
    h = d.GetHours()
    mi = d.GetMinutes()
    hs = h.ToStr()
    if h < 10 then hs = "0" + hs
    ms = mi.ToStr()
    if mi < 10 then ms = "0" + ms
    return hs + ":" + ms
end function

sub onChannelSelected()
    playChannel(m.channels.itemSelected)
end sub

function altIndex(name as string) as integer
    a = ParseJson(readReg("alt"))
    if type(a) <> "roAssociativeArray" then return 0
    v = a[name]
    if v = invalid then return 0
    return v
end function

sub setAlt(name as string, i as integer)
    a = ParseJson(readReg("alt"))
    if type(a) <> "roAssociativeArray" then a = {}
    a[name] = i
    writeReg("alt", FormatJson(a))
end sub

sub playChannel(index as integer)
    if m.channelsNode = invalid then return
    n = m.channelsNode.getChildCount()
    if n = 0 then return
    if index < 0 then index = n - 1
    if index >= n then index = 0
    ch = m.channelsNode.getChild(index)
    opts = ch.options
    k = altIndex(ch.title)
    if k >= opts.Count() then k = 0
    url = opts[k].url
    m.liveIndex = index
    writeReg("lastChannel", index.ToStr())
    m.liveMode = true
    m.playing = invalid
    m.resumeAt = 0
    m.playTitle = ch.shortDescriptionLine2 + "   " + ch.title
    m.osdLogo.uri = ch.hdPosterUrl
    m.osdHelp.text = "arriba/abajo cambia de canal  ·  * opción de señal y subtítulos  ·  Atrás lista de canales"
    fmt = "ts"
    if Instr(1, LCase(url), ".m3u8") > 0 then fmt = "hls"
    other = "hls"
    if fmt = "hls" then other = "ts"
    m.cands = [{ url: url, fmt: fmt }, { url: url, fmt: other }]
    m.candIdx = 0
    m.video.visible = true
    startCandidate()
    if opts.Count() > 1 then showToast(ch.title + "  ·  " + opts[k].label)
end sub

sub zap(delta as integer)
    playChannel(m.liveIndex + delta)
end sub

sub showSignalMenu()
    ch = m.channelsNode.getChild(m.liveIndex)
    labels = []
    actions = []
    cur = altIndex(ch.title)
    i = 0
    for each o in ch.options
        label = o.label
        if i = cur then label = "> " + label
        labels.push(label)
        actions.push("o:" + i.ToStr())
        i = i + 1
    end for
    labels.push("Subtítulos")
    actions.push("subsLive")
    labels.push("Cerrar")
    actions.push("none")
    showMenu(ch.title, labels, actions)
end sub

sub updateLiveOsd()
    ch = m.channelsNode.getChild(m.liveIndex)
    pr = nowNext(ch.tvg)
    st = m.video.state
    if pr.now <> invalid then
        m.osdState.text = pr.now[2]
        m.osdTime.text = hhmm(pr.now[0]) + " - " + hhmm(pr.now[1])
        now = CreateObject("roDateTime").AsSeconds()
        span = pr.now[1] - pr.now[0]
        if span > 0 then m.osdBar.width = Int(1760 * (now - pr.now[0]) / span) else m.osdBar.width = 0
    else
        m.osdState.text = ""
        m.osdTime.text = ""
        m.osdBar.width = 0
    end if
    if st = "buffering" then m.osdState.text = "Cargando..."
    if pr.next <> invalid then m.osdLeft.text = "Luego: " + hhmm(pr.next[0]) + "  " + pr.next[2] else m.osdLeft.text = ""
end sub

' ------------------------------------------------------------------ one series

sub openSeries(seriesId as string, title as string, poster as string)
    m.home.visible = false
    m.seriesView.visible = true
    m.seriesId = seriesId
    m.seriesName = title
    m.seriesCover = poster
    m.seriesTitle.text = title
    m.seriesPlot.text = "Cargando capítulos..."
    m.seriesPoster.uri = poster
    m.seriesNode = invalid
    m.seasons.content = CreateObject("roSGNode", "ContentNode")
    m.episodes.content = CreateObject("roSGNode", "ContentNode")
    m.seasons.setFocus(true)
    runTask("info", seriesId, "onSeriesInfo")
end sub

sub onSeriesInfo(ev as object)
    t = ev.getRoSGNode()
    if t.param <> m.seriesId then return
    if ev.getField() = "error" then
        m.seriesPlot.text = t.error
        return
    end if
    r = t.result
    if r = invalid then return
    m.seriesNode = r
    m.seriesPlot.text = r.plot
    if r.cover <> "" and m.seriesCover = "" then
        m.seriesCover = r.cover
        m.seriesPoster.uri = r.cover
    end if
    if r.getChildCount() = 0 then
        m.seriesPlot.text = "Esta serie no tiene capítulos disponibles."
        return
    end if
    m.seasons.content = r
    m.episodes.content = r.getChild(0)
    if r.getChildCount() > 1 then m.seasons.setFocus(true) else m.episodes.setFocus(true)
end sub

sub onSeasonFocused()
    if m.seriesNode = invalid then return
    s = m.seriesNode.getChild(m.seasons.itemFocused)
    if s <> invalid then m.episodes.content = s
end sub

sub onSeasonSelected()
    m.episodes.setFocus(true)
end sub

sub onEpisodeSelected()
    if m.seriesNode = invalid then return
    s = m.seriesNode.getChild(m.seasons.itemFocused)
    if s = invalid then return
    e = s.getChild(m.episodes.itemSelected)
    if e = invalid then return
    m.returnTo = "series"
    title = m.seriesName + " · " + s.title + " · " + e.title
    playItem({ kind: "episode", streamId: e.streamId, ext: e.ext, title: title, poster: m.seriesCover, seriesId: m.seriesId })
end sub

' ------------------------------------------------------------------ player

sub playItem(it as object)
    m.liveMode = false
    m.playing = it
    m.playTitle = it.title
    m.osdLogo.uri = ""
    m.osdHelp.text = "OK pausa  ·  izquierda/derecha 10 s  ·  rebobinar/adelantar 1 min  ·  * audio y subtítulos  ·  Atrás salir"
    m.resumeAt = 0
    h = findHistory(histKey(it))
    if h <> invalid then
        if h.pos > 30 and (h.dur = 0 or h.pos < h.dur - 120) then m.resumeAt = h.pos
    end if
    m.cands = candidates(it)
    m.candIdx = 0
    m.lastSaved = 0
    startCandidate()
    if m.resumeAt > 0 then showToast("Continuando desde " + clock(m.resumeAt))
end sub

sub startCandidate()
    c = m.cands[m.candIdx]
    content = CreateObject("roSGNode", "ContentNode")
    content.url = c.url
    content.streamFormat = c.fmt
    content.title = m.playTitle
    if m.liveMode then content.live = true
    if m.resumeAt > 0 then content.playStart = m.resumeAt
    m.home.visible = false
    m.seriesView.visible = false
    m.video.content = content
    m.video.visible = true
    ' the keys come to the scene: our own controls (the Video node's own UI is off)
    m.top.setFocus(true)
    m.video.control = "play"
    m.osdTitle.text = m.playTitle
    showOsd()
    m.osdTimer.control = "start"
end sub

' The service may give movies as .ts, which Roku can't play directly: try the same title as HLS,
' then MP4 / MKV, before giving up.
function candidates(it as object) as object
    path = "movie"
    if it.kind = "episode" then path = "series"
    base = m.server + "/" + path + "/" + m.user + "/" + m.pass + "/" + it.streamId
    ext = LCase(it.ext)
    if ext = "" then ext = "mp4"
    list = []
    if ext = "mp4" or ext = "m4v" or ext = "mov" then
        list.push({ url: base + "." + ext, fmt: "mp4" })
    else if ext = "mkv" then
        list.push({ url: base + ".mkv", fmt: "mkv" })
    else if ext = "m3u8" then
        list.push({ url: base + ".m3u8", fmt: "hls" })
    end if
    for each alt in [{ e: "m3u8", f: "hls" }, { e: "ts", f: "ts" }, { e: "mp4", f: "mp4" }, { e: "mkv", f: "mkv" }]
        u = base + "." + alt.e
        seen = false
        for each c in list
            if c.url = u then seen = true
        end for
        if not seen then list.push({ url: u, fmt: alt.f })
    end for
    return list
end function

sub onVideoState()
    state = m.video.state
    if state = "error" then
        if m.video.position > 5 then m.resumeAt = m.video.position
        m.candIdx = m.candIdx + 1
        if m.candIdx < m.cands.Count() then
            startCandidate()
        else if m.liveMode then
            m.osdState.text = "Sin señal en esta opción. Pulsa * para elegir otra."
            showOsd()
            showToast("Sin señal. Pulsa * para probar otra opción.")
        else
            msg = m.video.errorMsg
            closeVideo(false)
            showToast("No se pudo reproducir este título. " + msg)
        end if
        return
    else if state = "finished" and m.liveMode then
        m.candIdx = 0
        startCandidate()
        return
    else if state = "finished" then
        removeHistory(histKey(m.playing))
        closeVideo(false)
        return
    else if state = "buffering" or state = "paused" then
        showOsd()
    end if
    updateOsd()
end sub

sub onVideoPosition()
    if m.liveMode then return
    at = m.video.position
    if at - m.lastSaved >= 10 or m.lastSaved - at >= 10 then
        m.lastSaved = at
        saveHistory(at, m.video.duration)
    end if
end sub

sub seekBy(delta as integer)
    if m.video.duration <= 0 then return
    target = m.video.position + delta
    if target < 0 then target = 0
    if target > m.video.duration - 5 then target = m.video.duration - 5
    m.video.seek = target
    sign = "+"
    if delta < 0 then sign = "-"
    showToast(sign + Abs(delta).ToStr() + " s  ·  " + clock(target))
    showOsd()
end sub

sub togglePause()
    if m.video.state = "paused" then
        m.video.control = "resume"
    else if m.video.state = "playing" or m.video.state = "buffering" then
        m.video.control = "pause"
    end if
    showOsd()
end sub

sub showOsd()
    m.osd.visible = true
    m.osdHideAt = m.clock.TotalSeconds() + 5
    updateOsd()
end sub

sub onOsdTick()
    if not m.video.visible then
        m.osdTimer.control = "stop"
        return
    end if
    updateOsd()
    if m.osd.visible and m.video.state = "playing" and m.clock.TotalSeconds() >= m.osdHideAt then m.osd.visible = false
end sub

sub updateOsd()
    if not m.osd.visible then return
    if m.liveMode then
        updateLiveOsd()
        return
    end if
    dur = m.video.duration
    st = m.video.state
    stateText = ""
    if st = "buffering" then stateText = "Cargando..."
    if st = "paused" then stateText = "En pausa"
    m.osdState.text = stateText
    at = m.video.position
    m.osdTime.text = clock(at)
    if dur > 0 then
        m.osdLeft.text = "-" + clock(dur - at) + "  ·  " + clock(dur)
        m.osdBar.width = Int(1760 * at / dur)
    else
        m.osdLeft.text = ""
        m.osdBar.width = 0
    end if
end sub

sub closeVideo(save as boolean)
    if save and m.playing <> invalid and m.video.position > 5 then saveHistory(m.video.position, m.video.duration)
    m.video.control = "stop"
    m.video.visible = false
    m.osd.visible = false
    m.osdTimer.control = "stop"
    m.playing = invalid
    if m.liveMode then
        m.liveMode = false
        m.home.visible = true
        refreshNow()
        m.channels.jumpToItem = m.liveIndex
        m.channels.setFocus(true)
        return
    end if
    if m.returnTo = "series" then
        m.seriesView.visible = true
        m.episodes.setFocus(true)
    else
        m.home.visible = true
        refreshSpecialCategory()
        m.grid.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ audio and subtitles

sub showPlayerOptions()
    if m.liveMode then
        showSignalMenu()
        return
    end if
    showMenu("Reproducción", ["Idioma del audio", "Subtítulos", "Cerrar"], ["audio", "subs", "none"])
end sub

sub showAudioMenu()
    tracks = m.video.availableAudioTracks
    if tracks = invalid or tracks.Count() = 0 then
        showToast("Este título tiene un solo audio.")
        restoreFocus()
        return
    end if
    labels = []
    actions = []
    for each t in tracks
        name = langName(t.Language)
        if t.Name <> invalid and t.Name <> "" and t.Name <> t.Language then name = name + " (" + t.Name + ")"
        if t.Track = m.video.currentAudioTrack then name = "> " + name
        labels.push(name)
        actions.push("a:" + t.Track)
    end for
    showMenu("Idioma del audio", labels, actions)
end sub

sub showSubtitleMenu()
    tracks = m.video.availableSubtitleTracks
    labels = ["Sin subtítulos"]
    actions = ["s:off"]
    if tracks <> invalid then
        for each t in tracks
            name = langName(t.Language)
            if t.Description <> invalid and t.Description <> "" then name = name + " (" + t.Description + ")"
            if m.video.globalCaptionMode = "On" and t.TrackName = m.video.subtitleTrack then name = "> " + name
            labels.push(name)
            actions.push("s:" + t.TrackName)
        end for
    end if
    if labels.Count() = 1 then
        showToast("Este título no trae subtítulos.")
        restoreFocus()
        return
    end if
    showMenu("Subtítulos", labels, actions)
end sub

function langName(code as dynamic) as string
    if code = invalid or code = "" then return "Pista"
    c = LCase(code)
    names = { spa: "Español", es: "Español", eng: "Inglés", en: "Inglés", por: "Portugués", pt: "Portugués", fra: "Francés", fre: "Francés", fr: "Francés", ita: "Italiano", it: "Italiano", deu: "Alemán", ger: "Alemán", de: "Alemán", jpn: "Japonés", ja: "Japonés", kor: "Coreano", ko: "Coreano", und: "Sin especificar" }
    if names.DoesExist(c) then return names[c]
    return code
end function

' ------------------------------------------------------------------ "Continuar viendo" and "Ver más tarde" (kept on the Roku)

function histKey(it as object) as string
    if it.kind = "episode" then return "e" + it.streamId
    return "m" + it.streamId
end function

function loadList(name as string) as object
    raw = readReg(name)
    if raw = "" then return []
    h = ParseJson(raw)
    if type(h) <> "roArray" then return []
    return h
end function

function loadHistory() as object
    return loadList("history")
end function

function findHistory(key as string) as dynamic
    for each h in loadHistory()
        if h.k = key then return h
    end for
    return invalid
end function

function historyFor(mode as string) as object
    out = []
    for each h in loadHistory()
        if (mode = "series" and h.kind = "episode") or (mode <> "series" and h.kind = "movie") then out.push(h)
    end for
    return out
end function

sub saveHistory(at as float, dur as float)
    it = m.playing
    if it = invalid or at < 5 then return
    key = histKey(it)
    list = [{ k: key, kind: it.kind, id: it.streamId, ext: it.ext, t: it.title, p: it.poster, s: it.seriesId, pos: Int(at), dur: Int(dur) }]
    for each h in loadHistory()
        if h.k <> key and list.Count() < 25 then list.push(h)
    end for
    writeReg("history", FormatJson(list))
end sub

sub removeHistory(key as string)
    list = []
    for each h in loadHistory()
        if h.k <> key then list.push(h)
    end for
    writeReg("history", FormatJson(list))
end sub

function continueContent() as object
    root = CreateObject("roSGNode", "ContentNode")
    for each h in historyFor(m.mode)
        n = root.createChild("ContentNode")
        n.addFields({ kind: h.kind, streamId: h.id, ext: h.ext, seriesId: h.s, histKey: h.k })
        n.title = h.t
        label = h.t
        if h.pos > 0 then label = clock(h.pos) + " · " + h.t
        n.shortDescriptionLine1 = label
        n.hdPosterUrl = h.p
        n.sdPosterUrl = h.p
    end for
    return root
end function

' later: movies and whole series
function laterKey(kind as string, id as string) as string
    return kind + ":" + id
end function

function inLater(kind as string, id as string) as boolean
    key = laterKey(kind, id)
    for each l in loadList("later")
        if laterKey(l.kind, l.id) = key then return true
    end for
    return false
end function

function laterFor(mode as string) as object
    out = []
    for each l in loadList("later")
        if (mode = "series" and l.kind = "series") or (mode <> "series" and l.kind = "movie") then out.push(l)
    end for
    return out
end function

sub toggleLater(kind as string, id as string, ext as string, title as string, poster as string)
    key = laterKey(kind, id)
    list = []
    found = false
    for each l in loadList("later")
        if laterKey(l.kind, l.id) = key then found = true else list.push(l)
    end for
    if found then
        showToast("Quitado de Ver más tarde")
    else
        list.Unshift({ kind: kind, id: id, ext: ext, t: title, p: poster })
        while list.Count() > 40
            list.Pop()
        end while
        showToast("Agregado a Ver más tarde")
    end if
    writeReg("later", FormatJson(list))
    refreshSpecialCategory()
end sub

function laterContent() as object
    root = CreateObject("roSGNode", "ContentNode")
    for each l in laterFor(m.mode)
        n = root.createChild("ContentNode")
        if l.kind = "series" then
            n.addFields({ kind: "series", streamId: "", ext: "", seriesId: l.id })
        else
            n.addFields({ kind: "movie", streamId: l.id, ext: l.ext, seriesId: "" })
        end if
        n.title = l.t
        n.shortDescriptionLine1 = l.t
        n.hdPosterUrl = l.p
        n.sdPosterUrl = l.p
    end for
    return root
end function

' ------------------------------------------------------------------ options (* key)

sub showOptions()
    labels = []
    actions = []
    if m.home.visible and m.grid.hasFocus() then
        item = focusedGridItem()
        if item <> invalid then
            id = item.streamId
            kind = "movie"
            if item.kind = "series" then
                id = item.seriesId
                kind = "series"
            end if
            if item.kind <> "episode" then
                if inLater(kind, id) then labels.push("Quitar de Ver más tarde") else labels.push("Agregar a Ver más tarde")
                actions.push("later")
            end if
            if m.currentCat = "__continue" then
                labels.push("Quitar de Continuar viendo")
                actions.push("forget")
            end if
        end if
    else if m.seriesView.visible then
        if inLater("series", m.seriesId) then labels.push("Quitar serie de Ver más tarde") else labels.push("Agregar serie a Ver más tarde")
        actions.push("laterSeries")
    end if
    if m.mode = "series" then labels.push("Ver Películas") else labels.push("Ver Series")
    actions.push("switch")
    labels.push("Cambiar servidor o usuario")
    actions.push("account")
    labels.push("Cancelar")
    actions.push("none")
    showMenu("TV/VOD", labels, actions)
end sub

sub showMenu(title as string, labels as object, actions as object)
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "Dialog")
    dlg.title = title
    dlg.buttons = labels
    dlg.observeField("buttonSelected", "onMenuButton")
    m.menuDlg = dlg
    m.menuActions = actions
    m.top.dialog = dlg
end sub

sub onMenuButton()
    dlg = m.menuDlg
    if dlg = invalid then return
    action = m.menuActions[dlg.buttonSelected]
    dlg.close = true
    m.menuDlg = invalid
    if action = invalid then action = "none"
    if action = "later" then
        item = focusedGridItem()
        if item <> invalid then
            if item.kind = "series" then
                toggleLater("series", item.seriesId, "", item.title, item.hdPosterUrl)
            else
                toggleLater("movie", item.streamId, item.ext, item.title, item.hdPosterUrl)
            end if
        end if
    else if action = "laterSeries" then
        toggleLater("series", m.seriesId, "", m.seriesName, m.seriesCover)
    else if action = "forget" then
        item = focusedGridItem()
        if item <> invalid then
            removeHistory(item.histKey)
            refreshSpecialCategory()
        end if
    else if action = "switch" then
        if m.mode = "series" then switchMode("movies") else switchMode("series")
        return
    else if action = "account" then
        showLogin("")
        return
    else if action = "audio" then
        showAudioMenu()
        return
    else if action = "subs" then
        showSubtitleMenu()
        return
    else if Left(action, 2) = "o:" then
        ch = m.channelsNode.getChild(m.liveIndex)
        setAlt(ch.title, Val(Mid(action, 3)))
        playChannel(m.liveIndex)
        return
    else if action = "subsLive" then
        showSubtitleMenu()
        return
    else if Left(action, 2) = "a:" then
        m.video.audioTrack = Mid(action, 3)
    else if action = "s:off" then
        m.video.globalCaptionMode = "Off"
    else if Left(action, 2) = "s:" then
        m.video.subtitleTrack = Mid(action, 3)
        m.video.globalCaptionMode = "On"
    end if
    restoreFocus()
end sub

sub restoreFocus()
    if m.video.visible then
        m.top.setFocus(true)
    else if m.home.visible and m.mode = "live" and not m.tabsFocused then
        m.channels.setFocus(true)
    else if m.seriesView.visible then
        m.episodes.setFocus(true)
    else if m.home.visible then
        if m.tabsFocused then
            m.top.setFocus(true)
        else if m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then
            m.grid.setFocus(true)
        else
            m.cats.setFocus(true)
        end if
    else if m.login.visible then
        m.loginList.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ keys

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.video.visible and m.liveMode then
        if key = "back" then
            closeVideo(false)
        else if key = "up" or key = "channelup" then
            zap(1)
        else if key = "down" or key = "channeldown" then
            zap(-1)
        else if key = "options" then
            showPlayerOptions()
        else
            showOsd()
        end if
        return true
    end if
    if m.video.visible then
        if key = "back" then
            closeVideo(true)
        else if key = "OK" or key = "play" then
            togglePause()
        else if key = "left" or key = "replay" then
            seekBy(-10)
        else if key = "right" then
            seekBy(10)
        else if key = "rewind" then
            seekBy(-60)
        else if key = "fastforward" then
            seekBy(60)
        else if key = "options" then
            showPlayerOptions()
        else
            showOsd()
        end if
        return true
    end if
    if key = "options" then
        showOptions()
        return true
    end if
    if m.home.visible then
        if m.tabsFocused then
            ' left/right only move the cursor; OK opens that section
            if key = "left" then
                moveTabCursor(-1)
            else if key = "right" then
                moveTabCursor(1)
            else if key = "OK" then
                switchMode(m.tabCursor)
            else if key = "down" then
                focusTabs(false)
            else if key = "back" then
                return false
            end if
            return true
        end if
        if key = "up" and (m.grid.hasFocus() or (m.cats.hasFocus() and m.cats.itemFocused <= 0) or (m.channels.hasFocus() and m.channels.itemFocused <= 0)) then
            focusTabs(true)
            return true
        else if key = "right" and m.cats.hasFocus() then
            if m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then m.grid.setFocus(true)
            return true
        else if (key = "left" or key = "back") and m.grid.hasFocus() then
            m.cats.setFocus(true)
            return true
        end if
    else if m.seriesView.visible then
        if key = "right" and m.seasons.hasFocus() then
            m.episodes.setFocus(true)
            return true
        else if key = "left" and m.episodes.hasFocus() then
            m.seasons.setFocus(true)
            return true
        else if key = "back" then
            m.seriesView.visible = false
            m.home.visible = true
            refreshSpecialCategory()
            m.grid.setFocus(true)
            return true
        end if
    else if m.login.visible then
        if key = "back" and m.server <> "" and readReg("pass") <> "" then
            showHome()
            return true
        end if
    end if
    return false
end function

' ------------------------------------------------------------------ helpers

sub runTask(kind as string, param as string, callback as string)
    t = CreateObject("roSGNode", "ApiTask")
    t.server = m.server
    t.user = m.user
    t.pass = m.pass
    t.kind = kind
    t.param = param
    t.observeField("result", callback)
    t.observeField("error", callback)
    m.tasks[kind] = t
    t.control = "RUN"
end sub

sub showToast(text as string)
    m.toast.text = text
    m.toast.visible = true
    m.toastBg.visible = true
    m.toastTimer.control = "stop"
    m.toastTimer.control = "start"
end sub

sub hideToast()
    m.toast.visible = false
    m.toastBg.visible = false
end sub

function clock(secs as dynamic) as string
    s = Int(secs)
    if s < 0 then s = 0
    h = Int(s / 3600)
    mi = Int((s Mod 3600) / 60)
    se = s Mod 60
    mm = mi.ToStr()
    if mi < 10 then mm = "0" + mm
    ss = se.ToStr()
    if se < 10 then ss = "0" + ss
    if h > 0 then return h.ToStr() + ":" + mm + ":" + ss
    return mm + ":" + ss
end function

function readReg(key as string) as string
    if m.reg.Exists(key) then return m.reg.Read(key)
    return ""
end function

sub writeReg(key as string, value as string)
    m.reg.Write(key, value)
    m.reg.Flush()
end sub
