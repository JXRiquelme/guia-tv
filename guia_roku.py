"""guia.xml -> guia-roku.json: lo que el Roku necesita (programas de las próximas 30 h, sin descripciones)."""
import json, time, xml.etree.ElementTree as ET
from datetime import datetime

def epoch(s):
    return int(datetime.strptime(s.strip(), "%Y%m%d%H%M%S %z").timestamp())

now = time.time()
out = {}
for p in ET.parse("guia.xml").getroot().iter("programme"):
    try:
        start, stop = epoch(p.get("start")), epoch(p.get("stop"))
    except Exception:
        continue
    if stop < now - 3600 or start > now + 30 * 3600:
        continue
    title = (p.findtext("title") or "").strip()
    out.setdefault(p.get("channel"), []).append([start, stop, title])
for v in out.values():
    v.sort()
with open("guia-roku.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
print(f"guia-roku.json: {len(out)} canales, {sum(len(v) for v in out.values())} programas")
