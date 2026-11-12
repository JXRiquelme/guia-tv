' AerioTV Lite para Roku: películas y series con los datos del servicio (servidor, usuario, contraseña).
' Todo se pide de a poco (categorías, luego los títulos de una categoría, luego los capítulos de una
' serie), así nunca se descarga el catálogo completo.

sub init()
    m.reg = CreateObject("roRegistrySection", "aeriolite")
    m.login = m.top.findNode("login")
    m.loginList = m.top.findNode("loginList")
    m.loginStatus = m.top.findNode("loginStatus")
    m.home = m.top.findNode("home")
    m.header = m.top.findNode("header")
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
    m.toast = m.top.findNode("toast")
    m.toastBg = m.top.findNode("toastBg")
    m.catTimer = m.top.findNode("catTimer")
    m.toastTimer = m.top.findNode("toastTimer")

    m.loginList.observeField("itemSelected", "onLoginSelected")
    m.cats.observeField("itemFocused", "onCatFocused")
    m.cats.observeField("itemSelected", "onCatSelected")
    m.grid.observeField("itemSelected", "onGridSelected")
    m.seasons.observeField("itemFocused", "onSeasonFocused")
    m.seasons.observeField("itemSelected", "onSeasonSelected")
    m.episodes.observeField("itemSelected", "onEpisodeSelected")
    m.video.observeField("state", "onVideoState")
    m.video.observeField("position", "onVideoPosition")
    m.catTimer.observeField("fire", "onCatTimer")
    m.toastTimer.observeField("fire", "hideToast")

    m.server = readReg("server")
    m.user = readReg("user")
    m.pass = readReg("pass")
    m.mode = readReg("mode")
    if m.mode <> "series" then m.mode = "movies"
    m.cache = {}
    m.cacheOrder = []
    m.catIds = []
    m.tasks = {}
    m.returnTo = "home"
    m.lastSaved = 0

    if m.server <> "" and m.user <> "" and m.pass <> "" then
        showHome()
    else
        showLogin("")
    end if
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
    if m.pass <> "" then pwd = String(Len(m.pass), "•")
    for each t in ["Servidor:  " + srv, "Usuario:  " + usr, "Contraseña:  " + pwd, "Conectar"]
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
    end if
    dlg.close = true
    m.kb = invalid
    refreshLoginList()
    m.loginList.setFocus(true)
end sub

sub connect()
    m.server = normalizeServer(m.server)
    if m.server = "" or m.user = "" or m.pass = "" then
        m.loginStatus.text = "Completa servidor, usuario y contraseña."
        return
    end if
    m.loginStatus.text = "Conectando…"
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
    if m.mode = "series" then m.header.text = "Series" else m.header.text = "Películas"
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    m.gridStatus.text = "Cargando categorías…"
    loading = CreateObject("roSGNode", "ContentNode")
    n = loading.createChild("ContentNode")
    n.title = "Cargando…"
    m.cats.content = loading
    m.catIds = [""]
    m.cats.setFocus(true)
    if m.mode = "series" then runTask("seriescats", "", "onCats") else runTask("vodcats", "", "onCats")
end sub

sub onCats(ev as object)
    t = ev.getRoSGNode()
    if ev.getField() = "error" then
        m.gridStatus.text = t.error + Chr(10) + "Pulsa ✱ para revisar los datos del servicio."
        return
    end if
    r = t.result
    if r = invalid then return
    root = CreateObject("roSGNode", "ContentNode")
    m.catIds = []
    if historyFor(m.mode).Count() > 0 then
        n = root.createChild("ContentNode")
        n.title = "▶  Seguir viendo"
        m.catIds.push("__continue")
    end if
    for i = 0 to r.getChildCount() - 1
        c = r.getChild(i)
        n = root.createChild("ContentNode")
        n.title = c.title
        m.catIds.push(c.id)
    end for
    m.cats.content = root
    m.gridStatus.text = ""
    m.cats.jumpToItem = 0
    m.cats.setFocus(true)
    loadCategory(0)
end sub

sub onCatFocused()
    m.catTimer.control = "stop"
    m.catTimer.control = "start"
end sub

sub onCatTimer()
    loadCategory(m.cats.itemFocused)
end sub

sub onCatSelected()
    if m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then m.grid.setFocus(true)
end sub

sub loadCategory(i as integer)
    if i < 0 or i >= m.catIds.Count() then return
    id = m.catIds[i]
    if id = "" then return
    m.currentCat = id
    if id = "__continue" then
        setGrid(continueContent(), "Aún no has visto nada.")
        return
    end if
    key = m.mode + ":" + id
    if m.cache.DoesExist(key) then
        setGrid(m.cache[key], "Esta categoría está vacía.")
        return
    end if
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    m.gridStatus.text = "Cargando…"
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

' ------------------------------------------------------------------ one series

sub openSeries(seriesId as string, title as string, poster as string)
    m.home.visible = false
    m.seriesView.visible = true
    m.seriesId = seriesId
    m.seriesName = title
    m.seriesCover = poster
    m.seriesTitle.text = title
    m.seriesPlot.text = "Cargando capítulos…"
    m.seriesPoster.uri = poster
    m.seriesNode = invalid
    empty = CreateObject("roSGNode", "ContentNode")
    m.seasons.content = empty
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
    if r.cover <> "" and m.seriesCover = "" then m.seriesPoster.uri = r.cover
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
    m.playing = it
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
    content.title = m.playing.title
    if m.resumeAt > 0 then content.playStart = m.resumeAt
    m.home.visible = false
    m.seriesView.visible = false
    m.video.content = content
    m.video.visible = true
    m.video.setFocus(true)
    m.video.control = "play"
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
    for each alt in [{ e: "m3u8", f: "hls" }, { e: "mp4", f: "mp4" }, { e: "mkv", f: "mkv" }]
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
        else
            msg = m.video.errorMsg
            closeVideo(false)
            showToast("No se pudo reproducir este título. " + msg)
        end if
    else if state = "finished" then
        removeHistory(histKey(m.playing))
        closeVideo(false)
    end if
end sub

sub onVideoPosition()
    at = m.video.position
    if at - m.lastSaved >= 10 or m.lastSaved - at >= 10 then
        m.lastSaved = at
        saveHistory(at, m.video.duration)
    end if
end sub

sub closeVideo(save as boolean)
    if save and m.playing <> invalid and m.video.position > 5 then saveHistory(m.video.position, m.video.duration)
    m.video.control = "stop"
    m.video.visible = false
    m.playing = invalid
    if m.returnTo = "series" then
        m.seriesView.visible = true
        m.episodes.setFocus(true)
    else
        m.home.visible = true
        if m.currentCat = "__continue" then setGrid(continueContent(), "Aún no has visto nada.")
        m.grid.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ "Seguir viendo" (saved on the Roku)

function histKey(it as object) as string
    if it.kind = "episode" then return "e" + it.streamId
    return "m" + it.streamId
end function

function loadHistory() as object
    raw = readReg("history")
    if raw = "" then return []
    h = ParseJson(raw)
    if type(h) <> "roArray" then return []
    return h
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
        n.addFields({ kind: h.kind, streamId: h.id, ext: h.ext, seriesId: h.s })
        n.title = h.t
        label = h.t
        if h.pos > 0 then label = clock(h.pos) + " · " + h.t
        n.shortDescriptionLine1 = label
        n.hdPosterUrl = h.p
        n.sdPosterUrl = h.p
    end for
    return root
end function

' ------------------------------------------------------------------ options (✱ key)

sub showOptions()
    other = "Ver Series"
    if m.mode = "series" then other = "Ver Películas"
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "Dialog")
    dlg.title = "AerioTV Lite"
    dlg.buttons = [other, "Cambiar servidor o usuario", "Cancelar"]
    dlg.observeField("buttonSelected", "onOptionsButton")
    m.optionsDlg = dlg
    m.top.dialog = dlg
end sub

sub onOptionsButton()
    dlg = m.optionsDlg
    if dlg = invalid then return
    pick = dlg.buttonSelected
    dlg.close = true
    m.optionsDlg = invalid
    if pick = 0 then
        if m.mode = "series" then m.mode = "movies" else m.mode = "series"
        writeReg("mode", m.mode)
        showHome()
    else if pick = 1 then
        showLogin("")
    else
        restoreFocus()
    end if
end sub

sub restoreFocus()
    if m.seriesView.visible then
        m.episodes.setFocus(true)
    else if m.home.visible then
        m.cats.setFocus(true)
    else if m.login.visible then
        m.loginList.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ keys

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.video.visible then
        if key = "back" then
            closeVideo(true)
            return true
        end if
        return false
    end if
    if key = "options" then
        showOptions()
        return true
    end if
    if m.home.visible then
        if key = "right" and m.cats.hasFocus() then
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
