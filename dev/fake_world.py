#!/usr/bin/env python3
"""Mondo finto per testare TeleCast: un sito di origine e una TV "LG" DLNA simulata."""
import hashlib
import http.client
import json
import random
import re
import socket
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ORIGIN_PORT = 18080
TV_PORT = 18090
REFERER_PREFIX = "https://site.example/"

rng = random.Random(42)
MOVIE = rng.randbytes(3 * 1024 * 1024 + 123)


def ts_segment(q, n):
    out = bytearray()
    for i in range(60):
        payload = hashlib.sha256(f"{q}-{n}-{i}".encode()).digest() * 6
        out += b"\x47" + payload[:187]
    return bytes(out)


PNG_HEAD = b"\x89PNG\r\n\x1a\n" + b"\x00" * 50 + b"IEND\xaeB`\x82"
SEGS = {q: [ts_segment(q, n) for n in range(5)] for q in ("360", "720", "1080", "aes")}
KEY = bytes(range(16))
ORIGIN_LOG = []

MASTER = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2"
360/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
http://127.0.0.1:18080/hls/1080/index.m3u8
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="1080/iframes.m3u8"
"""

AES_PL = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000001
""" + "".join(f"#EXTINF:6.0,\ns{n}.ts\n" for n in range(5)) + "#EXT-X-ENDLIST\n"


def media_playlist(q):
    body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n"
    body += "".join(f"#EXTINF:6.000,\nseg{n}.ts\n" for n in range(5))
    return body + "#EXT-X-ENDLIST\n"


def expected():
    return {
        "movie": hashlib.sha256(MOVIE).hexdigest(),
        "hls1080": hashlib.sha256(b"".join(SEGS["1080"])).hexdigest(),
        "hls1080from2": hashlib.sha256(b"".join(SEGS["1080"][2:])).hexdigest(),
        "aes": hashlib.sha256(b"".join(SEGS["aes"])).hexdigest(),
        "key": KEY.hex(),
    }


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass


class Origin(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype, extra=None, head=False):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head=False):
        p = urllib.parse.urlparse(self.path)
        ORIGIN_LOG.append({"path": p.path, "referer": self.headers.get("Referer"),
                           "range": self.headers.get("Range"), "ua": self.headers.get("User-Agent")})
        if p.path == "/_expected":
            return self._send(200, json.dumps(expected()).encode(), "application/json", head=head)
        if p.path == "/_log":
            return self._send(200, json.dumps(ORIGIN_LOG[-300:]).encode(), "application/json", head=head)
        if not (self.headers.get("Referer") or "").startswith(REFERER_PREFIX):
            return self._send(403, b"forbidden", "text/plain", head=head)
        if p.path == "/movie.mp4":
            return self._range(MOVIE, "application/octet-stream", head)
        if p.path == "/hls/master.m3u8":
            return self._send(200, MASTER.encode(), "application/vnd.apple.mpegurl", head=head)
        m = re.fullmatch(r"/hls/(360|720|1080)/index\.m3u8", p.path)
        if m:
            return self._send(200, media_playlist(m.group(1)).encode(), "application/vnd.apple.mpegurl", head=head)
        m = re.fullmatch(r"/hls/(360|720|1080)/seg(\d)\.ts", p.path)
        if m:
            q, n = m.group(1), int(m.group(2))
            if q == "1080" and n == 2:  # segmento "travestito" da immagine
                return self._send(200, PNG_HEAD + SEGS[q][n], "image/png", head=head)
            return self._send(200, SEGS[q][n], "video/mp2t", head=head)
        if p.path == "/aes/index.m3u8":
            return self._send(200, AES_PL.encode(), "application/x-mpegURL", head=head)
        if p.path == "/aes/key.bin":
            return self._send(200, KEY, "application/octet-stream", head=head)
        m = re.fullmatch(r"/aes/s(\d)\.ts", p.path)
        if m:
            return self._send(200, SEGS["aes"][int(m.group(1))], "video/mp2t", head=head)
        return self._send(404, b"nope", "text/plain", head=head)

    def _range(self, data, ctype, head):
        rh = self.headers.get("Range")
        if rh:
            m = re.match(r"bytes=(\d+)-(\d*)", rh)
            start = int(m.group(1))
            end = min(int(m.group(2)) if m.group(2) else len(data) - 1, len(data) - 1)
            body = data[start:end + 1]
            self.send_response(206)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            if not head:
                self.wfile.write(body)
        else:
            self._send(200, data, ctype, {"Accept-Ranges": "bytes"}, head)


# ------------------------------------------------------------------ TV finta

DESC = f"""
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>[LG] webOS TV OLED55C1 (finta)</friendlyName>
    <manufacturer>LG Electronics</manufacturer>
    <modelName>OLED55C1PSA</modelName>
    <UDN>uuid:fake-lg-1234</UDN>
    <serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/rc.xml</SCPDURL><controlURL>/RenderingControl/control</controlURL><eventSubURL>/rc/ev</eventSubURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cm.xml</SCPDURL><controlURL>/ConnectionManager/control</controlURL><eventSubURL>/cm/ev</eventSubURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/avt.xml</SCPDURL><controlURL>/AVTransport/control</controlURL><eventSubURL>/avt/ev</eventSubURL></service>
    </serviceList>
  </device>
</root>
"""

LOCK = threading.Lock()


def fresh(config=None):
    return {"state": "NO_MEDIA_PRESENT", "uri": None, "mime": None, "started": 0.0, "pos": 0, "volume": 20,
            "mute": False, "token": 0, "actions": [], "plays": [], "config": config or {}}


TV = fresh()


class UpnpError(Exception):
    def __init__(self, code, desc):
        self.code, self.desc = code, desc


def tget(url, headers=None, method="GET"):
    u = urllib.parse.urlparse(url)
    c = http.client.HTTPConnection(u.hostname, u.port, timeout=20)
    path = u.path + (("?" + u.query) if u.query else "")
    c.request(method, path, headers=headers or {"User-Agent": "LG-webOS-fake/1.0"})
    r = c.getresponse()
    body = r.read() if method != "HEAD" else b""
    hdrs = {k.lower(): v for k, v in r.getheaders()}
    c.close()
    return r.status, hdrs, body


def hls_client(uri, rec):
    st, h, body = tget(uri)
    if st != 200:
        raise IOError(f"playlist HTTP {st}")
    text = body.decode()
    if "#EXT-X-STREAM-INF" in text:
        lines = [l.strip() for l in text.splitlines() if l.strip()]
        rec["variant_count"] = sum(1 for l in lines if l.startswith("#EXT-X-STREAM-INF"))
        var = next(lines[i + 1] for i, l in enumerate(lines) if l.startswith("#EXT-X-STREAM-INF"))
        uri = urllib.parse.urljoin(uri, var)
        st, h, body = tget(uri)
        if st != 200:
            raise IOError(f"variante HTTP {st}")
        text = body.decode()
    data = b""
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXT-X-KEY"):
            m = re.search(r'URI="([^"]+)"', line)
            st, h, k = tget(urllib.parse.urljoin(uri, m.group(1)))
            rec["key"] = k.hex()
        elif line and not line.startswith("#"):
            st, h, seg = tget(urllib.parse.urljoin(uri, line))
            if st != 200:
                raise IOError(f"segmento HTTP {st}")
            rec["segments"] += 1
            data += seg
    return data


def player(token, uri, mime):
    rec = {"uri": uri, "mime": mime, "bytes": 0, "sha": None, "ctype": None, "features": None,
           "error": None, "segments": 0, "key": None, "head": None}
    with LOCK:
        TV["plays"].append(rec)
    try:
        if "mpegurl" in mime.lower():
            data = hls_client(uri, rec)
        else:
            st, h, _ = tget(uri, method="HEAD")
            rec["head"] = st
            hdr = {"User-Agent": "LG-webOS-fake/1.0", "getcontentFeatures.dlna.org": "1"}
            if "/v/" in uri:
                hdr["Range"] = "bytes=0-"
            st, h, data = tget(uri, headers=hdr)
            rec["status"] = st
            rec["ctype"] = h.get("content-type")
            rec["features"] = h.get("contentfeatures.dlna.org")
            rec["chunked"] = h.get("transfer-encoding")
        rec["sha"] = hashlib.sha256(data).hexdigest()
        rec["bytes"] = len(data)
        with LOCK:
            if TV["token"] != token:
                return
            if mime in TV["config"].get("fail_mimes", []):
                rec["result"] = "decoder: formato non supportato"
                TV["state"] = "STOPPED"
                return
            TV["state"] = "PLAYING"
            TV["started"] = time.time() - TV["pos"]
    except Exception as e:  # noqa
        rec["error"] = repr(e)
        with LOCK:
            if TV["token"] == token:
                TV["state"] = "STOPPED"


def fmt(sec):
    sec = int(sec)
    return f"{sec // 3600}:{sec // 60 % 60:02d}:{sec % 60:02d}"


def parse_time(t):
    acc = 0
    for p in t.split(".")[0].split(":"):
        acc = acc * 60 + int(p)
    return acc


def handle(action, args):
    with LOCK:
        if TV["state"] == "PLAYING":
            TV["pos"] = int(time.time() - TV["started"])
        if action == "SetAVTransportURI":
            meta = args.get("CurrentURIMetaData", "")
            m = re.search(r'protocolInfo="http-get:\*:([^:"]+):', meta)
            mime = m.group(1) if m else "?"
            if mime in TV["config"].get("reject_mimes", []):
                raise UpnpError(714, "Illegal MIME-type")
            TV["token"] += 1
            TV.update(uri=args["CurrentURI"], mime=mime, state="STOPPED", pos=0)
            return {}
        if action == "Play":
            if not TV["uri"]:
                raise UpnpError(701, "Transition not available")
            if TV["state"] == "PAUSED_PLAYBACK":
                TV["state"] = "PLAYING"
                TV["started"] = time.time() - TV["pos"]
                return {}
            TV["token"] += 1
            TV["state"] = "TRANSITIONING"
            threading.Thread(target=player, args=(TV["token"], TV["uri"], TV["mime"]), daemon=True).start()
            return {}
        if action == "Pause":
            TV["state"] = "PAUSED_PLAYBACK"
            return {}
        if action == "Stop":
            TV["token"] += 1
            TV["state"] = "STOPPED" if TV["uri"] else "NO_MEDIA_PRESENT"
            return {}
        if action == "Seek":
            TV["pos"] = parse_time(args["Target"])
            TV["started"] = time.time() - TV["pos"]
            return {}
        if action == "GetTransportInfo":
            return {"CurrentTransportState": TV["state"], "CurrentTransportStatus": "OK", "CurrentSpeed": "1"}
        if action == "GetPositionInfo":
            return {"Track": "1", "TrackDuration": "0:00:30", "TrackMetaData": "", "TrackURI": TV["uri"] or "",
                    "RelTime": fmt(TV["pos"]), "AbsTime": "NOT_IMPLEMENTED", "RelCount": "2147483647", "AbsCount": "2147483647"}
        if action == "GetVolume":
            return {"CurrentVolume": TV["volume"]}
        if action == "SetVolume":
            TV["volume"] = int(args["DesiredVolume"])
            return {}
        if action == "GetMute":
            return {"CurrentMute": "1" if TV["mute"] else "0"}
        if action == "SetMute":
            TV["mute"] = args["DesiredMute"] in ("1", "true")
            return {}
    raise UpnpError(401, "Invalid Action")


def local(tag):
    return tag.split("}")[-1]


class Tv(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _reply(self, code, body, ctype='text/xml; charset="utf-8"'):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/desc.xml":
            return self._reply(200, DESC)
        with LOCK:
            if self.path == "/_last":
                return self._reply(200, json.dumps(TV["plays"][-1] if TV["plays"] else {}), "application/json")
            if self.path == "/_actions":
                return self._reply(200, json.dumps(TV["actions"]), "application/json")
            if self.path == "/_status":
                s = {k: v for k, v in TV.items() if k not in ("actions", "plays")}
                return self._reply(200, json.dumps(s), "application/json")
        self._reply(404, "nope", "text/plain")

    def do_POST(self):
        global TV
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path == "/_reset":
            with LOCK:
                TV = fresh(json.loads(body or b"{}"))
            return self._reply(200, "{}", "application/json")
        action = self.headers.get("SOAPAction", "").strip('"').split("#")[-1]
        svc = self.headers.get("SOAPAction", "").strip('"').split("#")[0]
        root = ET.fromstring(body)
        bodyel = next(e for e in root if local(e.tag) == "Body")
        actel = list(bodyel)[0]
        args = {local(c.tag): (c.text or "") for c in actel}
        with LOCK:
            TV["actions"].append({"action": action, "args": {k: v for k, v in args.items() if k != "CurrentURIMetaData"}})
        try:
            out = handle(action, args)
        except UpnpError as e:
            fault = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                     's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode>'
                     '<faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">'
                     f'<errorCode>{e.code}</errorCode><errorDescription>{e.desc}</errorDescription></UPnPError></detail>'
                     '</s:Fault></s:Body></s:Envelope>')
            return self._reply(500, fault)
        inner = "".join(f"<{k}>{escape(str(v))}</{k}>" for k, v in out.items())
        resp = ('<?xml version="1.0"?>\n<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                f'<u:{action}Response xmlns:u="{svc}">{inner}</u:{action}Response></s:Body></s:Envelope>')
        self._reply(200, resp)


def ssdp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", 1900))
    while True:
        data, addr = s.recvfrom(4096)
        msg = data.decode("latin-1")
        if not msg.startswith("M-SEARCH"):
            continue
        st = re.search(r"(?im)^ST:\s*(.+)$", msg).group(1).strip()
        resp = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                f"LOCATION: http://127.0.0.1:{TV_PORT}/desc.xml\r\n"
                "SERVER: Linux/4.4 UPnP/1.0 LGE_DLNA_SDK/1.6.0\r\n"
                f"ST: {st}\r\nUSN: uuid:fake-lg-1234::{st}\r\n\r\n")
        s.sendto(resp.encode("latin-1"), addr)


if __name__ == "__main__":
    for target in (lambda: QuietServer(("127.0.0.1", ORIGIN_PORT), Origin).serve_forever(),
                   lambda: QuietServer(("127.0.0.1", TV_PORT), Tv).serve_forever(),
                   ssdp):
        threading.Thread(target=target, daemon=True).start()
    print("mondo finto pronto", flush=True)
    while True:
        time.sleep(3600)
