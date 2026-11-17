#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WDPC Server — сервер управления ПК со смартфона (в стиле Watch Dogs).

Каналы связи:
  1. Bluetooth RFCOMM (SPP) — основной. Телефон подключается как к обычной
     гарнитуре/мыши, никакого подтверждения на ПК не требуется (режим "без пары").
  2. Wi-Fi: веб-интерфейс http://IP:8000 и WebSocket ws://IP:8000/ws — fallback.

Команды (текст, по одной в строке):
  PING                      -> PONG
  KEY <linux-keycode> [DOWN|UP]   одиночная клавиша / зажатие
  CHORD <kc> <kc> ... [UP]        комбинация (напр. CHORD 125 116 = Win+D)
  MOVE <dx> <dy>            перемещение мыши (относительно)
  WHEEL <dy>                колесо мыши
  CLICK [LEFT|RIGHT|MIDDLE] клик мыши
  MDOWN/MUP <btn>           зажать/отпустить кнопку мыши

Примеры:
  KEY 104          # Alt+F4 (закрыть окно)
  KEY 133          # Пуск (Win)
  KEY 376          # Win+Tab (обзор окон / Task View)
  CHORD 125 116    # Alt+Tab (удерживая Alt, нажать Tab)
  CHORD 125 116 UP # Alt+Shift+Tab (отпустить Tab)
  CHORD 125 9      # Win+M (свернуть всё)
  MOVE 100 -50     # двигать курсор
"""

import argparse
import ctypes
import ctypes.util
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid

# ---------------------------------------------------------------- уinput ---

libc_name = ctypes.util.find_library("c")
libc = ctypes.CDLL(libc_name or "libc.so.6", use_errno=True)

EV_SYN, EV_KEY, EV_REL, EV_MSC = 0x00, 0x01, 0x02, 0x04
MSC_SCAN = 0x04
REL_X, REL_WHEEL = 0x00, 0x08
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502
UINPUT_MAX_NAME_SIZE = 80

BUS_UINPUT = 0x0003


class input_event(ctypes.Structure):
    _fields_ = [("tv_sec", ctypes.c_long), ("tv_usec", ctypes.c_long),
                ("type", ctypes.c_uint16), ("code", ctypes.c_uint16),
                ("value", ctypes.c_int32)]


class uinput_user_dev(ctypes.Structure):
    _fields_ = [("name", ctypes.c_char * UINPUT_MAX_NAME_SIZE),
                ("id_bustype", ctypes.c_uint16),
                ("id_vendor", ctypes.c_uint16),
                ("id_product", ctypes.c_uint16),
                ("id_version", ctypes.c_uint16),
                ("ff_effects_max", ctypes.c_uint32),
                ("abs", ctypes.c_int32 * (0x3F * 4))]


def _ioctl(fd, req, arg):
    return libc.ioctl(fd, ctypes.c_ulong(req), ctypes.byref(arg))


class InputEmulator:
    """Эмуляция клавиатуры + мыши через /dev/uinput (Linux)."""

    KEYS = list(range(0, 0x2FF))
    BTNS = [0x110, 0x111, 0x112, 0x113, 0x114, 0x115, 0x116]

    def __init__(self):
        self.lock = threading.Lock()
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        self._set_bits()
        dev = uinput_user_dev()
        dev.name = b"WDPC Virtual Keyboard+Mouse"
        dev.id_bustype, dev.id_vendor, dev.id_product, dev.id_version = BUS_UINPUT, 0x1234, 0x0001, 1
        os.write(self.fd, ctypes.string_at(ctypes.byref(dev), ctypes.sizeof(dev)))
        if _ioctl(self.fd, UI_DEV_CREATE, None) != 0:
            raise OSError("UI_DEV_CREATE failed. Запустите с правами на запись в /dev/uinput "
                          "(sudo или группа input): " + os.strerror(ctypes.get_errno()))
        time.sleep(0.4)  # подождать появления устройства

    def _set_bits(self):
        UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT, UI_SET_MSCBIT = 0x40045564, 0x40045565, 0x40045566, 0x40045569
        for ev in (EV_KEY, EV_REL, EV_MSC):
            _ioctl(self.fd, UI_SET_EVBIT, ctypes.c_int(ev))
        for k in self.KEYS + self.BTNS:
            _ioctl(self.fd, UI_SET_KEYBIT, ctypes.c_int(k))
        for r in (REL_X, REL_Y, REL_WHEEL):
            _ioctl(self.fd, UI_SET_RELBIT, ctypes.c_int(r))
        _ioctl(self.fd, UI_SET_MSCBIT, ctypes.c_int(MSC_SCAN))

    def _ev(self, t, code, value):
        e = input_event(0, 0, t, code, value)
        os.write(self.fd, ctypes.string_at(ctypes.byref(e), ctypes.sizeof(e)))

    def _syn(self):
        self._ev(EV_SYN, 0, 0)

    def key(self, kc, down):
        with self.lock:
            self._ev(EV_MSC, MSC_SCAN, kc)
            self._ev(EV_KEY, kc, 1 if down else 0)
            self._syn()

    def tap(self, kc):
        self.key(kc, True)
        time.sleep(0.03)
        self.key(kc, False)

    def chord_down(self, keys):
        with self.lock:
            for kc in keys[:-1]:
                self._ev(EV_KEY, kc, 1)
            self._syn()
            self._ev(EV_MSC, MSC_SCAN, keys[-1])
            self._ev(EV_KEY, keys[-1], 1)
            self._syn()

    def chord_up_last(self, keys):
        with self.lock:
            self._ev(EV_KEY, keys[-1], 0)
            for kc in reversed(keys[:-1]):
                self._ev(EV_KEY, kc, 0)
            self._syn()

    def move(self, dx, dy):
        MAX = 30000
        with self.lock:
            while abs(dx) > MAX or abs(dy) > MAX:
                cx = max(-MAX, min(MAX, dx))
                cy = max(-MAX, min(MAX, dy))
                self._ev(EV_REL, REL_X, cx)
                self._ev(EV_REL, 0x01, cy)
                self._syn()
                dx -= cx
                dy -= cy
            if dx:
                self._ev(EV_REL, REL_X, dx)
            if dy:
                self._ev(EV_REL, 0x01, dy)
            if dx or dy:
                self._syn()

    def wheel(self, dy):
        with self.lock:
            self._ev(EV_REL, REL_WHEEL, dy)
            self._syn()

    def click(self, btn=0x110):
        with self.lock:
            self._ev(EV_KEY, btn, 1)
            self._syn()
            time.sleep(0.03)
            self._ev(EV_KEY, btn, 0)
            self._syn()

    def mouse_btn(self, btn, down):
        with self.lock:
            self._ev(EV_KEY, btn, 1 if down else 0)
            self._syn()


# ------------------------------------------------------------- команды -----

BTN_MAP = {"LEFT": 0x110, "RIGHT": 0x111, "MIDDLE": 0x112}


def handle_command(cmd: str, emu: InputEmulator) -> str:
    parts = cmd.strip().upper().split()
    if not parts:
        return "ERR empty"
    op = parts[0]
    try:
        if op == "PING":
            return "PONG"
        if op == "KEY" and len(parts) >= 2:
            kc = int(parts[1])
            state = parts[2] if len(parts) > 2 else "TAP"
            if state == "DOWN":
                emu.key(kc, True)
            elif state == "UP":
                emu.key(kc, False)
            else:
                emu.tap(kc)
            return "OK"
        if op == "CHORD" and len(parts) >= 2:
            up = parts[-1] == "UP"
            nums = parts[1:-1] if up else parts[1:]
            keys = [int(x) for x in nums]
            if up:
                emu.chord_up_last(keys)
            elif len(keys) >= 2:
                emu.chord_down(keys)
            elif len(keys) == 1:
                emu.tap(keys[0])
            return "OK"
        if op == "MOVE" and len(parts) >= 3:
            emu.move(int(parts[1]), int(parts[2]))
            return "OK"
        if op == "WHEEL" and len(parts) >= 2:
            emu.wheel(int(parts[1]))
            return "OK"
        if op == "CLICK":
            emu.click(BTN_MAP.get(parts[1] if len(parts) > 1 else "LEFT", 0x110))
            return "OK"
        if op in ("MDOWN", "MUP") and len(parts) >= 2:
            emu.mouse_btn(BTN_MAP.get(parts[1], 0x110), op == "MDOWN")
            return "OK"
        return "ERR unknown: " + cmd[:40]
    except Exception as exc:  # noqa
        return "ERR " + str(exc)[:60]


# ------------------------------------------------------------ bluetooth ----

def rfcomm_listen(port, emu: InputEmulator, stop=threading.Event()):
    """RFCOMM-сокет напрямую через AF_BLUETOOTH (без pybluez)."""
    BTPROTO_RFCOMM = 3
    srv = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, BTPROTO_RFCOMM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((socket.BDADDR_ANY, port))
    srv.listen(2)
    print(f"[BT] RFCOMM-канал запущен на канале {port}. Ищите устройство в телефоне.")
    clients = []
    while not stop.is_set():
        try:
            srv.settimeout(1.0)
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            print(f"[BT] Подключено: {addr}")
            clients.append(conn)
            threading.Thread(target=_bt_client, args=(conn, emu), daemon=True).start()
        except OSError as e:
            if stop.is_set():
                break
            print("[BT] ошибка accept:", e)
            time.sleep(1)


def _bt_client(conn, emu):
    buf = b""
    try:
        while True:
            data = conn.recv(1024)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                cmd = line.decode(errors="ignore").strip()
                if cmd:
                    resp = handle_command(cmd, emu)
                    print(f"[BT] {cmd!r} -> {resp}")
                    try:
                        conn.sendall((resp + "\n").encode())
                    except OSError:
                        return
    except OSError:
        pass
    finally:
        print("[BT] Клиент отключился")
        try:
            conn.close()
        except OSError:
            pass


def register_sdappt(channel=22):
    """Объявить приложение SPP в BlueZ (sdptool), чтобы ПК был виден с именем WDPC."""
    try:
        subprocess.run(["sdptool", "add", "--channel", str(channel), "1101"],
                       check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except FileNotFoundError:
        pass


# ---------------------------------------------------------------- web ------

HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>WDPC Remote</title>
<style>
body{background:#0d1117;color:#e6edf3;font-family:sans-serif;text-align:center;margin:0;padding:16px}
h2{color:#3fb950}button{font-size:22px;margin:8px;padding:18px 22px;border-radius:14px;border:none;background:#1f6feb;color:#fff}
button:active{background:#3fb950}.row{display:flex;flex-wrap:wrap;justify-content:center}
#log{font-size:12px;color:#8b949e;margin-top:16px;word-break:break-all}
</style></head><body>
<h2>WDPC Remote (Wi-Fi)</h2>
<div class="row">
<button onclick="s('KEY 133')">Пуск</button>
<button onclick="s('KEY 376')">Win+Tab</button>
<button onclick="altTab()">Alt+Tab</button>
<button onclick="s('KEY 104')">Alt+F4</button>
<button onclick="s('CHORD 125 9')">Win+M</button>
</div>
<div class="row">
<button onclick="s('MOVE 60 0')">→</button>
<button onclick="s('MOVE -60 0')">←</button>
<button onclick="s('MOVE 0 -60')">↑</button>
<button onclick="s('MOVE 0 60')">↓</button>
<button onclick="s('CLICK LEFT')">ЛКМ</button>
<button onclick="s('WHEEL -2')">▲колесо</button>
<button onclick="s('WHEEL 2')">▼колесо</button>
</div>
<div id="log">подключение...</div>
<script>
let ws; function connect(){ws=new WebSocket((location.protocol=='https:'?'wss://':'ws://')+location.host+'/ws');
ws.onopen=()=>log('онлайн');ws.onclose=()=>{log('офлайн, переподключение');setTimeout(connect,1500)};
ws.onmessage=e=>log('ПК: '+e.data)}
function s(c){if(ws&&ws.readyState==1){ws.send(c);log('> '+c)}}
function altTab(){s('CHORD 125 116');setTimeout(()=>s('CHORD 125 116 UP'),120)}
function log(t){document.getElementById('log').textContent=t}connect();
</script></body></html>"""


def web_serve(host, port, emu: InputEmulator):
    try:
        from flask import Flask, Response, request
        from flask_sock import Sock  # pip install flask-sock
    except ImportError:
        print("[WEB] flask/flask_sock не установлены, веб-режим пропущен "
              "(pip install flask flask-sock)")
        return
    app = Flask(__name__)
    sock = Sock(app)

    @app.route("/")
    def index():
        return Response(HTML, mimetype="text/html")

    @sock.route("/ws")
    def ws_handler(client):
        print("[WEB] клиент подключился")
        for msg in client:
            resp = handle_command(msg, emu)
            print(f"[WEB] {msg!r} -> {resp}")
            client.send(resp)

    app.run(host=host, port=port, threaded=True)


# ---------------------------------------------------------------- main -----

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-bt", action="store_true", help="не поднимать Bluetooth")
    ap.add_argument("--bt-channel", type=int, default=22, help="RFCOMM канал (по умолч. 22)")
    ap.add_argument("--web", action="store_true", help="поднять Wi-Fi веб-сервер")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()

    if sys.platform != "linux":
        print("Сервер рассчитан на Linux (/dev/uinput).")
        sys.exit(1)

    emu = InputEmulator()
    print("Виртуальные устройства созданы: WDPC Virtual Keyboard+Mouse")

    threads = []
    if args.web:
        t = threading.Thread(target=web_serve, args=(args.host, args.port, emu), daemon=True)
        t.start()
        threads.append(t)
        ip = ""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        except OSError:
            pass
        print(f"[WEB] http://{ip or '<IP ПК>'}:{args.port}")

    if not args.no_bt:
        register_sdappt()
        try:
            rfcomm_listen(args.bt_channel, emu)
        except (OSError, PermissionError) as e:
            print("[BT] Не удалось поднять Bluetooth RFCOMM:", e)
            print("[BT] Попробуйте: sudo apt install bluez; либо используйте --web")
    else:
        while True:
            time.sleep(1)


if __name__ == "__main__":
    main()
