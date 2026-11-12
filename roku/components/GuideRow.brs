' programmes of one channel, between winStart and winStart + 3 h, across 1460 px
sub init()
    m.num = m.top.findNode("num")
    m.logo = m.top.findNode("logo")
    m.name = m.top.findNode("name")
    m.head = m.top.findNode("head")
    m.blocks = m.top.findNode("blocks")
    m.x0 = 336
    m.width = 1460
    m.span = 10800
end sub

sub showContent()
    c = m.top.itemContent
    if c = invalid then return
    m.num.text = c.shortDescriptionLine2
    m.logo.uri = c.hdPosterUrl
    m.name.text = c.title
    m.blocks.removeChildrenIndex(m.blocks.getChildCount(), 0)
    win = c.winStart
    progs = c.progs
    now = CreateObject("roDateTime").AsSeconds()
    if progs = invalid or progs.Count() = 0 then
        addBlock(m.x0, m.width, "Sin información de programación", false)
        return
    end if
    for each p in progs
        s = p[0]
        e = p[1]
        if e > win and s < win + m.span then
            if s < win then s = win
            if e > win + m.span then e = win + m.span
            x = m.x0 + Int((s - win) * m.width / m.span)
            w = Int((e - s) * m.width / m.span)
            if w > 6 then addBlock(x, w - 4, p[2], p[0] <= now and now < p[1])
        end if
    end for
end sub

sub addBlock(x as integer, w as integer, title as string, current as boolean)
    r = m.blocks.createChild("Rectangle")
    r.translation = [x, 0]
    r.width = w
    r.height = 76
    if current then r.color = "0x2A3B6EFF" else r.color = "0x1A2240FF"
    if w > 40 then
        l = m.blocks.createChild("Label")
        l.translation = [x + 12, 0]
        l.width = w - 20
        l.height = 76
        l.vertAlign = "center"
        l.font = "font:SmallestSystemFont"
        l.color = "0xE6E9F2FF"
        l.text = title
    end if
end sub

sub showFocus()
    if m.top.focusPercent > 0.5 then
        m.head.color = "0x2563EBFF"
        m.num.color = "0xFFFFFFFF"
    else
        m.head.color = "0x161D36FF"
        m.num.color = "0x8A93AFFF"
    end if
end sub
