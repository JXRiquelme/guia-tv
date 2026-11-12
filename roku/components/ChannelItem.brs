sub init()
    m.num = m.top.findNode("num")
    m.logo = m.top.findNode("logo")
    m.name = m.top.findNode("name")
    m.now = m.top.findNode("now")
end sub

sub showContent()
    c = m.top.itemContent
    if c = invalid then return
    m.num.text = c.shortDescriptionLine2
    m.logo.uri = c.hdPosterUrl
    m.name.text = c.title
    m.now.text = c.description
end sub

sub showFocus()
    if m.top.focusPercent > 0.5 then
        m.name.color = "0x0B1020FF"
        m.now.color = "0x2A3350FF"
        m.num.color = "0x0B1020FF"
    else
        m.name.color = "0xFFFFFFFF"
        m.now.color = "0xB0B8D0FF"
        m.num.color = "0x8A93AFFF"
    end if
end sub
