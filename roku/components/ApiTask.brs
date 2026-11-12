sub init()
    m.top.functionName = "fetchData"
end sub

sub fetchData()
    xfer = CreateObject("roUrlTransfer")
    base = m.top.server + "/player_api.php?username=" + xfer.Escape(m.top.user) + "&password=" + xfer.Escape(m.top.pass)
    kind = m.top.kind
    if kind = "m3u" then
        loadM3u()
        return
    else if kind = "guide" then
        loadGuide()
        return
    else if kind = "search" then
        search()
        return
    end if
    url = base
    if kind = "vodcats" then url = base + "&action=get_vod_categories"
    if kind = "seriescats" then url = base + "&action=get_series_categories"
    if kind = "vod" then url = base + "&action=get_vod_streams&category_id=" + xfer.Escape(m.top.param)
    if kind = "series" then url = base + "&action=get_series&category_id=" + xfer.Escape(m.top.param)
    if kind = "info" then url = base + "&action=get_series_info&series_id=" + xfer.Escape(m.top.param)

    body = fetch(url)
    if body = invalid then
        m.top.error = "No se pudo conectar con el servidor. Revisa la dirección y tu internet."
        return
    end if
    json = ParseJson(body)
    if json = invalid then
        m.top.error = "El servidor respondió algo que no se pudo leer."
        return
    end if

    root = CreateObject("roSGNode", "ContentNode")
    if kind = "auth" then
        ok = false
        msg = "Usuario o contraseña incorrectos."
        if type(json) = "roAssociativeArray" and json.user_info <> invalid then
            info = json.user_info
            if toStr(info.auth) = "1" then
                ok = true
                msg = ""
                if info.status <> invalid and LCase(toStr(info.status)) <> "active" then
                    ok = false
                    msg = "La cuenta no está activa (" + toStr(info.status) + ")."
                end if
            end if
        end if
        root.addFields({ ok: ok, msg: msg })
    else if kind = "vodcats" or kind = "seriescats" then
        if type(json) = "roArray" then
            for each c in json
                n = root.createChild("ContentNode")
                n.title = toStr(c.category_name)
                n.id = toStr(c.category_id)
            end for
        end if
    else if kind = "vod" then
        if type(json) = "roArray" then
            for each v in json
                n = root.createChild("ContentNode")
                n.addFields({ kind: "movie", streamId: toStr(v.stream_id), ext: toStr(v.container_extension), seriesId: "" })
                n.title = toStr(v.name)
                n.shortDescriptionLine1 = toStr(v.name)
                n.hdPosterUrl = toStr(v.stream_icon)
                n.sdPosterUrl = toStr(v.stream_icon)
            end for
        end if
    else if kind = "series" then
        if type(json) = "roArray" then
            for each s in json
                n = root.createChild("ContentNode")
                n.addFields({ kind: "series", streamId: "", ext: "", seriesId: toStr(s.series_id) })
                n.title = toStr(s.name)
                n.shortDescriptionLine1 = toStr(s.name)
                n.hdPosterUrl = toStr(s.cover)
                n.sdPosterUrl = toStr(s.cover)
            end for
        end if
    else if kind = "info" then
        buildSeries(root, json)
    end if
    m.top.result = root
end sub

' GET with a 40 s limit; invalid if it failed
function fetch(url as string) as dynamic
    for attempt = 1 to 2
        xfer = CreateObject("roUrlTransfer")
        port = CreateObject("roMessagePort")
        xfer.SetMessagePort(port)
        xfer.SetUrl(url)
        xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
        xfer.InitClientCertificates()
        xfer.EnableEncodings(true)
        xfer.AddHeader("User-Agent", "AerioTV Lite Roku")
        if xfer.AsyncGetToString() then
            msg = Wait(40000, port)
            if type(msg) = "roUrlEvent" then
                if msg.GetResponseCode() = 200 then return msg.GetString()
            else
                xfer.AsyncCancel()
            end if
        end if
    end for
    return invalid
end function

' Seasons as children of root, each with its episodes as children.
sub buildSeries(root as object, json as object)
    if type(json) <> "roAssociativeArray" then return
    info = json.info
    plot = ""
    cover = ""
    if info <> invalid then
        plot = toStr(info.plot)
        cover = toStr(info.cover)
    end if
    root.addFields({ plot: plot, cover: cover })
    eps = json.episodes
    if eps = invalid then return
    seasons = []
    if type(eps) = "roAssociativeArray" then
        keys = []
        for each k in eps
            keys.push(Int(Val(k)))
        end for
        keys.Sort()
        for each k in keys
            list = eps[k.ToStr()]
            if list <> invalid then seasons.push({ num: k, list: list })
        end for
    else if type(eps) = "roArray" then
        i = 0
        for each list in eps
            i = i + 1
            if type(list) = "roArray" and list.Count() > 0 then
                num = i
                if list[0].season <> invalid then num = Int(Val(toStr(list[0].season)))
                seasons.push({ num: num, list: list })
            end if
        end for
    end if
    for each s in seasons
        sn = root.createChild("ContentNode")
        sn.title = "Temporada " + s.num.ToStr()
        for each e in s.list
            en = sn.createChild("ContentNode")
            epTitle = toStr(e.title)
            num = toStr(e.episode_num)
            poster = cover
            dur = 0
            if e.info <> invalid then
                if e.info.movie_image <> invalid and toStr(e.info.movie_image) <> "" then poster = toStr(e.info.movie_image)
                if e.info.duration_secs <> invalid then dur = Val(toStr(e.info.duration_secs))
            end if
            label = epTitle
            if num <> "" then label = num + ". " + epTitle
            en.title = label
            en.hdPosterUrl = poster
            en.addFields({ kind: "episode", streamId: toStr(e.id), ext: toStr(e.container_extension), seriesId: "", duration: dur, season: s.num })
        end for
    end for
end sub

function toStr(v as dynamic) as string
    if v = invalid then return ""
    t = type(v)
    if t = "roString" or t = "String" then return v
    if t = "roInt" or t = "Integer" or t = "roLongInteger" or t = "LongInteger" then return v.ToStr()
    if t = "roFloat" or t = "Float" or t = "roDouble" or t = "Double" then
        if v = Int(v) then return Int(v).ToStr()
        return Str(v).Trim()
    end if
    if t = "roBoolean" or t = "Boolean" then
        if v then return "1"
        return "0"
    end if
    return ""
end function

' ------------------------------------------------------------------ live channels (the curated list)

sub loadM3u()
    body = fetch(m.top.param)
    if body = invalid then
        m.top.error = "No se pudo descargar la lista de canales."
        return
    end if
    root = CreateObject("roSGNode", "ContentNode")
    lines = body.Split(Chr(10))
    cur = invalid
    rxAttr = CreateObject("roRegex", "([a-zA-Z-]+)=" + Chr(34) + "([^" + Chr(34) + "]*)" + Chr(34), "")
    number = 0
    for each raw in lines
        line = raw.Trim()
        if Left(line, 8) = "#EXTINF:" then
            cur = { name: "", logo: "", tvg: "", alts: [] }
            comma = lastComma(line)
            if comma > 0 then cur.name = Mid(line, comma + 1).Trim()
            for each mt in rxAttr.MatchAll(line)
                k = LCase(mt[1])
                if k = "tvg-logo" then cur.logo = mt[2]
                if k = "tvg-id" then cur.tvg = mt[2]
            end for
        else if Left(line, 8) = "#EXTALT:" and cur <> invalid then
            rest = Mid(line, 9)
            bar = Instr(1, rest, "|")
            if bar > 0 then cur.alts.push({ label: Left(rest, bar - 1).Trim(), url: Mid(rest, bar + 1).Trim() })
        else if line <> "" and Left(line, 1) <> "#" and cur <> invalid then
            number = number + 1
            n = root.createChild("ContentNode")
            n.title = cur.name
            n.hdPosterUrl = cur.logo
            n.shortDescriptionLine2 = number.ToStr()
            opts = [{ label: "Opción 1", url: line }]
            for each a in cur.alts
                opts.push(a)
            end for
            n.addFields({ tvg: cur.tvg, options: opts, num: number })
            cur = invalid
        end if
    end for
    m.top.result = root
end sub

function lastComma(line as string) as integer
    ' the name follows the last comma outside the quoted attributes
    inQuote = false
    at = 0
    for i = 1 to Len(line)
        ch = Mid(line, i, 1)
        if ch = Chr(34) then inQuote = not inQuote
        if ch = "," and not inQuote then at = i
    end for
    return at
end function

sub loadGuide()
    body = fetch("https://raw.githubusercontent.com/JXRiquelme/guia-tv/main/guia-roku.json")
    if body = invalid then
        m.top.error = "Sin guía"
        return
    end if
    data = ParseJson(body)
    if type(data) <> "roAssociativeArray" then
        m.top.error = "Sin guía"
        return
    end if
    root = CreateObject("roSGNode", "ContentNode")
    root.addFields({ guide: data })
    m.top.result = root
end sub

' ------------------------------------------------------------------ search (whole catalog, kept as a file)

sub search()
    xfer = CreateObject("roUrlTransfer")
    action = "get_vod_streams"
    if m.top.mode = "series" then action = "get_series"
    file = "tmp:/all_" + m.top.mode + ".json"
    fs = CreateObject("roFileSystem")
    if not fs.Exists(file) then
        url = m.top.server + "/player_api.php?username=" + xfer.Escape(m.top.user) + "&password=" + xfer.Escape(m.top.pass) + "&action=" + action
        if not download(url, file) then
            m.top.error = "No se pudo descargar el catálogo para buscar."
            return
        end if
    end if
    q = plain(LCase(m.top.param.Trim()))
    root = CreateObject("roSGNode", "ContentNode")
    if q = "" then
        m.top.result = root
        return
    end if
    text = ReadAsciiFile(file)
    rxName = CreateObject("roRegex", Chr(34) + "name" + Chr(34) + ":" + Chr(34) + "((?:[^" + Chr(34) + "\\]|\\.)*)" + Chr(34), "")
    found = 0
    for each chunk in text.Split("},{")
        mt = rxName.Match(chunk)
        if mt.Count() > 1 then
            if Instr(1, plain(LCase(mt[1])), q) > 0 then
                c = chunk
                if Left(c, 2) = "[{" then c = Mid(c, 3)
                if Left(c, 1) = "{" then c = Mid(c, 2)
                if Right(c, 2) = "}]" then c = Left(c, Len(c) - 2)
                o = ParseJson("{" + c + "}")
                if o <> invalid then
                    n = root.createChild("ContentNode")
                    if m.top.mode = "series" then
                        n.addFields({ kind: "series", streamId: "", ext: "", seriesId: toStr(o.series_id) })
                        n.hdPosterUrl = toStr(o.cover)
                    else
                        n.addFields({ kind: "movie", streamId: toStr(o.stream_id), ext: toStr(o.container_extension), seriesId: "" })
                        n.hdPosterUrl = toStr(o.stream_icon)
                    end if
                    n.title = toStr(o.name)
                    n.shortDescriptionLine1 = n.title
                    n.sdPosterUrl = n.hdPosterUrl
                    found = found + 1
                    if found >= 150 then exit for
                end if
            end if
        end if
    end for
    m.top.result = root
end sub

function download(url as string, file as string) as boolean
    xfer = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    xfer.SetUrl(url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.EnableEncodings(true)
    if not xfer.AsyncGetToFile(file) then return false
    msg = Wait(120000, port)
    if type(msg) = "roUrlEvent" and msg.GetResponseCode() = 200 then return true
    xfer.AsyncCancel()
    CreateObject("roFileSystem").Delete(file)
    return false
end function

' lowercase text without accents, so "accion" finds "Acción" (JSON may also write them as \u00f3)
function plain(s as string) as string
    pairs = [["\u00e1", "a"], ["\u00e9", "e"], ["\u00ed", "i"], ["\u00f3", "o"], ["\u00fa", "u"], ["\u00f1", "n"], ["\u00fc", "u"], ["\u00c1", "a"], ["\u00c9", "e"], ["\u00cd", "i"], ["\u00d3", "o"], ["\u00da", "u"], ["\u00d1", "n"], ["á", "a"], ["é", "e"], ["í", "i"], ["ó", "o"], ["ú", "u"], ["ñ", "n"], ["ü", "u"], ["Á", "a"], ["É", "e"], ["Í", "i"], ["Ó", "o"], ["Ú", "u"], ["Ñ", "n"]]
    for each p in pairs
        s = s.Replace(p[0], p[1])
    end for
    return s
end function
