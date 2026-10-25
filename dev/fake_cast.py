#!/usr/bin/env python3
"""Chromecast finto ("ok. Google TV"): Cast v2 su TLS (127.0.0.1:18009) con ricevitore CC1AD845 simulato
che legge i video come una pagina web (Origin + controllo CORS, preflight per l'HLS, niente MPEG-TS unico),
risponditore mDNS unicast su 127.0.0.1:15353 e stato su http://127.0.0.1:18099/."""
import hashlib
import http.client
import json
import re
import socket
import ssl
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CAST_PORT, MDNS_PORT, CTRL_PORT = 18009, 15353, 18099
CERT, KEY = sys.argv[1], sys.argv[2]
NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
NS_HB = "urn:x-cast:com.google.cast.tp.heartbeat"
NS_RCV = "urn:x-cast:com.google.cast.receiver"
NS_MEDIA = "urn:x-cast:com.google.cast.media"
APP = "CC1AD845"
LOCK = threading.Lock()


def fresh():
    return {"app": False, "volume": 0.3, "muted": False, "media": None, "player": "IDLE", "idle_reason": None,
            "pos": 0.0, "started": 0.0, "msid": 0, "loads": [], "cmds": [], "connects": [], "stopped_app": 0}


S = fresh()


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def fstr(num, s):
    b = s.encode()
    return bytes([(num << 3) | 2]) + varint(len(b)) + b


def encode(src, dst, ns, payload):
    return bytes([0x08]) + varint(0) + fstr(2, src) + fstr(3, dst) + fstr(4, ns) + bytes([0x28]) + varint(0) + fstr(6, payload)


def read_varint(b, i):
    shift = res = 0
    while True:
        v = b[i]
        i += 1
        res |= (v & 0x7F) << shift
        if not v & 0x80:
            return res, i
        shift += 7


def decode(b):
    i, d = 0, {}
    while i < len(b):
        key, i = read_varint(b, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            _, i = read_varint(b, i)
        elif wt == 2:
            n, i = read_varint(b, i)
            d[f] = b[i:i + n]
            i += n
        else:
            raise ValueError("wire type")
    return {"src": d.get(2, b"").decode(), "dst": d.get(3, b"").decode(), "ns": d.get(4, b"").decode(),
            "payload": d.get(6, b"").decode()}


def cur_pos():
    return time.time() - S["started"] if S["player"] == "PLAYING" else S["pos"]


def media_status():
    if S["media"] is None:
        return []
    st = {"mediaSessionId": S["msid"], "playbackRate": 1, "playerState": S["player"], "currentTime": cur_pos(),
          "supportedMediaCommands": 15, "media": {"contentId": S["media"]["contentId"],
                                                   "contentType": S["media"]["contentType"], "duration": 30.0,
                                                   "streamType": "BUFFERED"}}
    if S["player"] == "IDLE" and S["idle_reason"]:
        st["idleReason"] = S["idle_reason"]
    return [st]


def receiver_status():
    st = {"volume": {"level": S["volume"], "muted": S["muted"], "controlType": "attenuation"}}
    if S["app"]:
        st["applications"] = [{"appId": APP, "displayName": "Default Media Receiver", "sessionId": "sess-1",
                               "transportId": "web-7", "namespaces": [{"name": NS_MEDIA}]}]
    return st


def browser_get(url, headers, rec):
    u = urllib.parse.urlparse(url)
    c = http.client.HTTPConnection(u.hostname, u.port, timeout=20)
    h = {"User-Agent": "Mozilla/5.0 (Linux; Android 12) CrKey/1.56 Chrome", "Origin": "https://www.gstatic.com"}
    h.update(headers)
    c.request("GET", u.path + ("?" + u.query if u.query else ""), headers=h)
    r = c.getresponse()
    body = r.read()
    hd = {k.lower(): v for k, v in r.getheaders()}
    c.close()
    if hd.get("access-control-allow-origin") not in ("*", "https://www.gstatic.com"):
        rec["cors_ok"] = False
    if r.status not in (200, 206):
        raise IOError(f"HTTP {r.status} su {u.path}")
    return body, hd


def preflight(url, rec):
    u = urllib.parse.urlparse(url)
    c = http.client.HTTPConnection(u.hostname, u.port, timeout=10)
    c.request("OPTIONS", u.path, headers={"Origin": "https://www.gstatic.com", "Access-Control-Request-Method": "GET",
                                          "Access-Control-Request-Headers": "range"})
    r = c.getresponse()
    r.read()
    hd = {k.lower(): v for k, v in r.getheaders()}
    c.close()
    rec["preflight_ok"] = (r.status in (200, 204) and "range" in hd.get("access-control-allow-headers", "").lower()
                           and hd.get("access-control-allow-origin") == "*")


def fetch_media(media, rec):
    url, ctype = media["contentId"], media["contentType"].lower()
    if "mpegurl" in ctype:
        preflight(url, rec)
        text = browser_get(url, {}, rec)[0].decode()
        if "#EXT-X-STREAM-INF" in text:
            lines = [l.strip() for l in text.splitlines() if l.strip()]
            rec["variant_count"] = sum(1 for l in lines if l.startswith("#EXT-X-STREAM-INF"))
            var = next(lines[i + 1] for i, l in enumerate(lines) if l.startswith("#EXT-X-STREAM-INF"))
            url = urllib.parse.urljoin(url, var)
            text = browser_get(url, {}, rec)[0].decode()
        data = b""
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("#EXT-X-KEY"):
                m = re.search(r'URI="([^"]+)"', line)
                rec["key"] = browser_get(urllib.parse.urljoin(url, m.group(1)), {}, rec)[0].hex()
            elif line and not line.startswith("#"):
                data += browser_get(urllib.parse.urljoin(url, line), {}, rec)[0]
                rec["segments"] += 1
        return data
    body, hd = browser_get(url, {"Range": "bytes=0-"}, rec)
    rec["ctype"] = hd.get("content-type")
    return body


def do_load(media, current_time, rec):
    try:
        if media["contentType"].lower() in ("video/mp2t", "video/mpeg"):
            raise IOError("il ricevitore non decodifica un MPEG-TS unico")
        data = fetch_media(media, rec)
        rec["sha"] = hashlib.sha256(data).hexdigest()
        rec["bytes"] = len(data)
        if not rec.get("cors_ok", True):
            raise IOError("CORS mancante")
        with LOCK:
            S.update(media=media, player="PLAYING", idle_reason=None, pos=current_time,
                     started=time.time() - current_time)
            S["msid"] += 1
        return True
    except Exception as e:  # noqa
        rec["error"] = repr(e)
        return False


class Conn(threading.Thread):
    def __init__(self, sock):
        super().__init__(daemon=True)
        self.s = sock
        self.wlock = threading.Lock()

    def send(self, src, dst, ns, obj):
        b = encode(src, dst, ns, json.dumps(obj))
        with self.wlock:
            self.s.sendall(struct.pack(">I", len(b)) + b)

    def recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            c = self.s.recv(n - len(buf))
            if not c:
                raise EOFError
            buf += c
        return buf

    def run(self):
        try:
            while True:
                n = struct.unpack(">I", self.recv_exact(4))[0]
                m = decode(self.recv_exact(n))
                ns, src, dst = m["ns"], m["src"], m["dst"]
                d = json.loads(m["payload"])
                t, rid = d.get("type"), d.get("requestId", 0)
                if ns == NS_HB:
                    if t == "PING":
                        self.send(dst, src, NS_HB, {"type": "PONG"})
                elif ns == NS_CONN:
                    with LOCK:
                        S["connects"].append(dst)
                elif ns == NS_RCV:
                    with LOCK:
                        if t == "LAUNCH" and d.get("appId") == APP:
                            S["app"] = True
                        elif t == "STOP":
                            S.update(app=False, media=None, player="IDLE")
                            S["stopped_app"] += 1
                        elif t == "SET_VOLUME":
                            v = d.get("volume", {})
                            if "level" in v:
                                S["volume"] = round(float(v["level"]), 3)
                            if "muted" in v:
                                S["muted"] = v["muted"]
                        st = receiver_status()
                    self.send("receiver-0", src, NS_RCV, {"type": "RECEIVER_STATUS", "requestId": rid, "status": st})
                elif ns == NS_MEDIA:
                    if dst != "web-7" or not S["app"]:
                        self.send(dst, src, NS_MEDIA, {"type": "INVALID_REQUEST", "requestId": rid, "reason": "INVALID_PLAYER_STATE"})
                    elif t == "LOAD":
                        md = d["media"]
                        rec = {"contentId": md["contentId"], "contentType": md["contentType"],
                               "currentTime": d.get("currentTime", 0), "hlsSegmentFormat": md.get("hlsSegmentFormat"),
                               "segments": 0, "cors_ok": True, "title": md.get("metadata", {}).get("title")}
                        with LOCK:
                            S["loads"].append(rec)
                        if do_load(md, float(d.get("currentTime", 0)), rec):
                            with LOCK:
                                st = media_status()
                            self.send("web-7", src, NS_MEDIA, {"type": "MEDIA_STATUS", "requestId": rid, "status": st})
                        else:
                            self.send("web-7", src, NS_MEDIA, {"type": "LOAD_FAILED", "requestId": rid, "reason": "LOAD_FAILED"})
                    else:
                        with LOCK:
                            S["cmds"].append({"type": t, "currentTime": d.get("currentTime")})
                            if t == "PAUSE":
                                S.update(pos=cur_pos(), player="PAUSED")
                            elif t == "PLAY":
                                S.update(started=time.time() - S["pos"], player="PLAYING")
                            elif t == "SEEK":
                                S["pos"] = float(d["currentTime"])
                                S["started"] = time.time() - S["pos"]
                            elif t == "STOP":
                                S.update(pos=cur_pos(), player="IDLE", idle_reason="CANCELLED")
                            st = media_status()
                        self.send("web-7", src, NS_MEDIA, {"type": "MEDIA_STATUS", "requestId": rid, "status": st})
        except Exception:
            pass
        finally:
            try:
                self.s.close()
            except Exception:
                pass


def cast_server():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", CAST_PORT))
    srv.listen(16)
    while True:
        c, _ = srv.accept()
        try:
            Conn(ctx.wrap_socket(c, server_side=True)).start()
        except Exception:
            c.close()


def dns_name(name):
    out = b""
    for label in name.rstrip(".").split("."):
        b = label.encode()
        out += bytes([len(b)]) + b
    return out + b"\x00"


def rr(name, rtype, rdata, cls=0x8001, ttl=120):
    return dns_name(name) + struct.pack(">HHIH", rtype, cls, ttl, len(rdata)) + rdata


def mdns_response():
    svc, inst, target = "_googlecast._tcp.local", "Google-TV-ok-abc123._googlecast._tcp.local", "abc123.local"
    txt = b""
    for kv in ("id=abc123def456", "md=ok. 43 Google TV", "fn=ok. Google TV (finta)", "rs=", "ca=465413"):
        b = kv.encode()
        txt += bytes([len(b)]) + b
    ans = rr(svc, 12, dns_name(inst), cls=1)
    add = (rr(inst, 33, struct.pack(">HHH", 0, 0, CAST_PORT) + dns_name(target)) + rr(inst, 16, txt)
           + rr(target, 1, socket.inet_aton("127.0.0.1")))
    return struct.pack(">HHHHHH", 0, 0x8400, 0, 1, 0, 3) + ans + add


def mdns():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", MDNS_PORT))
    while True:
        data, addr = s.recvfrom(4096)
        if b"_googlecast" in data:
            s.sendto(mdns_response(), addr)


class Ctrl(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        with LOCK:
            if self.path == "/_last":
                body = json.dumps(S["loads"][-1] if S["loads"] else {})
            else:
                body = json.dumps({k: v for k, v in S.items() if k not in ("loads",)}, default=str)
        b = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        global S
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with LOCK:
            S = fresh()
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()


if __name__ == "__main__":
    for target in (cast_server, mdns, lambda: ThreadingHTTPServer(("127.0.0.1", CTRL_PORT), Ctrl).serve_forever()):
        threading.Thread(target=target, daemon=True).start()
    print("chromecast finto pronto", flush=True)
    while True:
        time.sleep(3600)
