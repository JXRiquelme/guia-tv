sub init()
    m.top.functionName = "fetchData"
end sub

sub fetchData()
    xfer = CreateObject("roUrlTransfer")
    base = m.top.server + "/player_api.php?username=" + xfer.Escape(m.top.user) + "&password=" + xfer.Escape(m.top.pass)
    kind = m.top.kind
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
