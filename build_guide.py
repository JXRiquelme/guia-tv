# Arma guia.xml para AerioTV juntando epgshare01 y la guía del servidor.
# Cada canal tiene una lista de fuentes en orden de preferencia: se usa la
# primera que tenga programación vigente. El tercer valor es un ajuste en
# minutos (positivo = la guía se corre hacia más tarde).
import gzip, io, os, sys, time, urllib.request, datetime
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

EPGSHARE = "https://epgshare01.online/epgshare01/epg_ripper_%s.xml.gz"
SOURCES = {
    "cl": EPGSHARE % "CL1",
    "ar": EPGSHARE % "AR1",
    "es": EPGSHARE % "ES1",
    "dtv": EPGSHARE % "DIRECTVSPORTS1",
    "srv": os.environ.get("SERVER_XMLTV_URL", ""),
}

CHANNELS = [
    ("canal13", "Canal 13", [("cl", "Canal.13.de.Chile.cl", 0), ("srv", "C13AAH", 0)]),
    ("t13", "T13", [("srv", "C13T13", 0)]),
    ("13c", "13C", [("srv", "C13CABH", 0)]),
    ("chv", "Chilevisión", [("cl", "Canal.Chilevisión.(CHV).cl", 0), ("srv", "CHIVHD", 0)]),
    ("mega", "Mega", [("cl", "Canal.Mega.(Chile).cl", 0), ("srv", "MEGAHD", 0)]),
    ("tvn", "TVN", [("cl", "Canal.TVN.(Chile).cl", 0), ("srv", "TVNHA", 0)]),
    ("lared", "La Red", [("cl", "Canal.La.Red.(Chile).cl", 0), ("srv", "REDCD", 0)]),
    ("tvmas", "TV+", [("srv", "TVMAS", 0)]),
    ("24horas", "24 Horas", [("cl", "Canal.24.Horas.(Chile).cl", 0), ("srv", "C24HCHD", 0)]),
    ("cnnchile", "CNN Chile", [("cl", "Canal.CNN.Chile.cl", 0), ("srv", "CNNCH", 0)]),
    ("etc", "ETC TV", [("srv", "ETCHD", 0)]),
    ("telemundo", "Telemundo", [("cl", "Canal.Telemundo.(Chile).cl", 0), ("srv", "CABTELE", 0)]),
    ("ae", "A&E", [("cl", "Canal.A&E.(Chile).cl", 0), ("srv", "AEMX", 0)]),
    ("axn", "AXN", [("cl", "Canal.AXN.(Chile).cl", 0), ("srv", "AXNARG", 0)]),
    ("fx", "FX", [("cl", "Canal.FX.(Chile).cl", 0), ("srv", "FXL", 0)]),
    ("amcseries", "AMC Series", [("srv", "CHICUS", 0)]),
    ("mtv", "MTV", [("cl", "Canal.MTV.(Chile).cl", 0), ("srv", "MTVLA", 0)]),
    ("sony", "Sony Channel", [("cl", "Canal.Sony.(Chile).cl", 0), ("srv", "SENTTVH", 0)]),
    ("star", "Star Channel", [("cl", "Canal.Star.Channel.(Chile).cl", 0), ("ar", "Canal.Star.Channel.(Argentina).ar", 0)]),
    ("usa", "USA Network", [("ar", "Canal.USA.Network.(Argentina).ar", 0), ("srv", "SYFYLA", 0)]),
    ("tntseries", "TNT Series", [("srv", "TNTSPAH", 0)]),
    ("adultswim", "Adult Swim", [("srv", "ADSMH", 0)]),
    ("universaltv", "Universal TV", [("srv", "UNILAHD", 0)]),
    ("warner", "Warner", [("srv", "WARCOL", -120), ("cl", "Canal.Warner.TV.(Chile).cl", 0)]),
    ("cinemax", "Cinemax", [("cl", "Canal.Cinemax.(Chile).cl", 0), ("srv", "CINECHL", 0)]),
    ("sonymovies", "Sony Movies", [("srv", "SONY", 0)]),
    ("studiouniversal", "Studio Universal", [("srv", "STULA", 0)]),
    ("hbo", "HBO", [("cl", "Canal.HBO.(Chile).cl", 0), ("srv", "HBOCHIL", 0)]),
    ("hbo2", "HBO 2", [("cl", "Canal.HBO.2.Latinoamérica.cl", 0), ("srv", "HBO2LA", 0)]),
    ("hboplus", "HBO+", [("srv", "HBOPPA", 0), ("cl", "Canal.HBO.Plus.cl", 0)]),
    ("hbofamily", "HBO Family", [("cl", "Canal.HBO.Family.Latinoamérica.cl", 0), ("srv", "HBOFAM", 0)]),
    ("hbosignature", "HBO Signature", [("cl", "Canal.HBO.Signature.Latinoamérica.cl", 0), ("srv", "HBOSLA", 0)]),
    ("hbopop", "HBO Pop", [("cl", "Canal.HBO.Pop.cl", 0), ("srv", "POPLA", 0)]),
    ("hbomundi", "HBO Mundi", [("cl", "Canal.HBO.Mundi.cl", 0), ("srv", "MUNLA", 0)]),
    ("hboxtreme", "HBO Xtreme", [("cl", "Canal.HBO.Xtreme.cl", 0), ("srv", "HBOXLA", 0)]),
    ("discovery", "Discovery Channel", [("cl", "Canal.Discovery.Channel.(Chile).cl", 0), ("srv", "DISCCHD", 0)]),
    ("dhh", "Discovery Home & Health", [("srv", "DHEAL", 0)]),
    ("animalplanet", "Animal Planet", [("cl", "Canal.Animal.Planet.(Chile).cl", 0), ("srv", "ANIPAA2", 0)]),
    ("natgeo", "National Geographic", [("cl", "Canal.National.Geographic.(Chile).cl", 0), ("srv", "NGLATHD", 0)]),
    ("history", "History", [("cl", "Canal.History.(Chile).cl", 0), ("srv", "HISTEHD", 0)]),
    ("history2", "History 2", [("cl", "Canal.History.2.(Chile).cl", 0), ("ar", "Canal.History.2.(Argentina).ar", 0)]),
    ("cartoon", "Cartoon Network", [("cl", "Canal.Cartoon.Network.(Chile).cl", 0), ("ar", "Canal.Cartoon.Network.(Argentina).ar", 0)]),
    ("disney", "Disney Channel", [("cl", "Canal.Disney.Channel.(Chile).cl", 0), ("ar", "Canal.Disney.Channel.(Argentina).ar", 0)]),
    ("nick", "Nickelodeon", [("cl", "Canal.Nickelodeon.(Chile).cl", 0), ("srv", "NIKL", 0)]),
    ("espn", "ESPN", [("cl", "Canal.ESPN.(Chile).cl", 0), ("srv", "ESPN2AH", 0)]),
    ("espn2", "ESPN 2", [("cl", "Canal.ESPN.2.(Chile).cl", 0), ("srv", "ESPNSCH", 0)]),
    ("espn3", "ESPN 3", [("cl", "Canal.ESPN.3.(Chile).cl", 0), ("srv", "ESPN3HD", 0)]),
    ("dsports", "DirecTV Sports", [("srv", "SPOARHD", 0), ("dtv", "DSPORTS.(ARG).dtvsp", 0)]),
    ("dsports2", "DirecTV Sports 2", [("dtv", "DSPORTS.2.HD.dtvsp", 0), ("srv", "SPO2HD", 0)]),
    ("dsportsplus", "DirecTV Sports+", [("dtv", "DSPORTS.+.HD.dtvsp", 0), ("srv", "SPOPLH", 0)]),
    ("dazn1", "DAZN 1", [("es", "DAZN.1.es", 0), ("srv", "DAZN1HD", 0)]),
    ("dazn2", "DAZN 2", [("es", "DAZN.2.es", 0), ("srv", "DAZN2HD", 0)]),
    ("dazn3", "DAZN 3", [("es", "DAZN.3.es", 0)]),
    ("dazn4", "DAZN 4", [("es", "DAZN.4.es", 0)]),
    ("daznlaliga1", "DAZN LaLiga 1", [("es", "DAZN.LaLiga.es", 0), ("es", "DAZN.LALIGA.es", 0)]),
    ("daznlaliga2", "DAZN LaLiga 2", [("es", "DAZN.LaLiga.2.es", 0), ("es", "DAZN.LALIGA.2.es", 0)]),
    ("ligacampeones", "Liga de Campeones", [("es", "M+.Liga.de.Campeones.es", 0), ("es", "Movistar.Liga.de.Campeones.es", 0)]),
    ("cuatro", "Cuatro", [("es", "Cuatro.es", 0)]),
]


def epoch(value):
    value = (value or "").strip()
    if len(value) < 14:
        return None
    dt = datetime.datetime.strptime(value[:14], "%Y%m%d%H%M%S")
    offset = 0
    rest = value[14:].strip()
    if len(rest) == 5 and rest[0] in "+-":
        offset = int(rest[1:3]) * 3600 + int(rest[3:5]) * 60
        if rest[0] == "+":
            offset = -offset
    return int(dt.replace(tzinfo=datetime.timezone.utc).timestamp()) + offset


def download(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (guia-tv)"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = resp.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


def main():
    now = int(time.time())
    wanted = {}
    for _, _, cands in CHANNELS:
        for src, cid, _ in cands:
            wanted.setdefault(src, set()).add(cid)
    progs = {}
    report = []
    for src, ids in wanted.items():
        url = SOURCES.get(src, "")
        if not url:
            report.append("fuente %s: sin configurar" % src)
            continue
        try:
            data = download(url)
        except Exception as exc:
            report.append("fuente %s: error al descargar (%s)" % (src, exc))
            continue
        count = 0
        for _, el in ET.iterparse(io.BytesIO(data), events=("end",)):
            if el.tag == "programme":
                cid = el.get("channel", "")
                if cid in ids:
                    start, stop = epoch(el.get("start")), epoch(el.get("stop"))
                    if start and stop and stop > now - 86400 and start < now + 8 * 86400:
                        progs.setdefault((src, cid), []).append((
                            start, stop,
                            el.findtext("title") or "",
                            el.findtext("desc") or "",
                            [c.text for c in el.findall("category") if c.text]))
                        count += 1
                el.clear()
        report.append("fuente %s: %d programas usados" % (src, count))
        del data

    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="guia-tv">']
    body = []
    for key, name, cands in CHANNELS:
        out.append('  <channel id="%s"><display-name>%s</display-name></channel>' % (key, escape(name)))
        chosen = None
        for src, cid, shift in cands:
            items = progs.get((src, cid), [])
            if any(stop > now and start < now + 12 * 3600 for start, stop, *_ in items):
                chosen = (src, cid, shift, items)
                break
        if not chosen:
            report.append("%-16s sin guía" % key)
            continue
        src, cid, shift, items = chosen
        report.append("%-16s %s / %s (ajuste %+d min)" % (key, src, cid, shift))
        seen = set()
        for start, stop, title, desc, cats in sorted(items):
            start += shift * 60
            stop += shift * 60
            if start in seen:
                continue
            seen.add(start)
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            item = '  <programme start="%s" stop="%s" channel="%s"><title>%s</title>' % (fmt(start), fmt(stop), key, escape(title))
            if desc:
                item += "<desc>%s</desc>" % escape(desc)
            for cat in cats[:3]:
                item += "<category>%s</category>" % escape(cat)
            body.append(item + "</programme>")
    out += body
    out.append("</tv>")
    with open("guia.xml", "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")
    stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(now))
    with open("informe.txt", "w", encoding="utf-8") as fh:
        fh.write("Actualizado: %s\n\n%s\n" % (stamp, "\n".join(report)))
    print("\n".join(report))
    if not body:
        sys.exit("No se obtuvo ningún programa")


if __name__ == "__main__":
    main()
