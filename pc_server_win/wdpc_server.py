#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WDPC server для Windows — пульт в стиле Watch Dogs.

Никаких зависимостей и установок: только стандартная библиотека Python 3.
Принимает команды от Android-приложения WDPC Remote по Wi-Fi (TCP, порт 8721)
и эмулирует клавиатуру через Win32 SendInput.

Запуск:  py wdpc_server.py            (или python wdpc_server.py)
Останов: Ctrl+C

Команды протокола (текст, по одной на строку):
  KEY <vk>            — нажать клавишу (Virtual-Key code)
  KEY <vk> DOWN       — зажать
  KEY <vk> UP         — отпустить
  CHORD <vk1> <vk2>.. — комбинация (зажать все, отпустить в обратном порядке)
  PING                — проверка связи -> PONG
"""
import ctypes
import socket
import sys
import threading
from ctypes import wintypes

PORT = 8721

user32 = ctypes.windll.user32

INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class INPUTUNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", INPUTUNION)]


SendInput = user32.SendInput
SendInput.argtypes = (ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int)
SendInput.restype = ctypes.c_uint


def key_event(vk: int, up: bool = False):
    inp = INPUT()
    inp.type = INPUT_KEYBOARD
    inp.u.ki.wVk = vk & 0xFFFF
    inp.u.ki.dwFlags = KEYEVENTF_KEYUP if up else 0
    SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def press(vk: int):
    key_event(vk, up=False)
    key_event(vk, up=True)


def chord(vks):
    for vk in vks:
        key_event(vk, up=False)
    for vk in reversed(vks):
        key_event(vk, up=True)


def handle_line(line: str):
    parts = line.split()
    if not parts:
        return None
    cmd = parts[0].upper()
    try:
        if cmd == "PING":
            return "PONG"
        if cmd == "KEY" and len(parts) >= 2:
            vk = int(parts[1])
            state = parts[2].upper() if len(parts) > 2 else ""
            if state == "DOWN":
                key_event(vk, up=False)
            elif state == "UP":
                key_event(vk, up=True)
            else:
                press(vk)
            return "OK"
        if cmd == "CHORD" and len(parts) >= 2:
            # формат клиента: CHORD vk1 vk2 ... UP/DOWN — трактуем как аккорд
            vks = [int(p) for p in parts[1:] if p.isdigit()]
            if vks:
                chord(vks)
            return "OK"
    except Exception as e:  # noqa: BLE001
        return f"ERR {e}"
    return "ERR unknown"


def serve_client(conn: socket.socket, addr):
    print(f"[wdpc] подключение: {addr}")
    conn.settimeout(None)
    buf = b""
    try:
        with conn.makefile("rwb") as f:
            while True:
                data = f.readline()
                if not data:
                    break
                line = data.decode("utf-8", "replace").strip()
                if not line:
                    continue
                reply = handle_line(line)
                print(f"[wdpc] {addr} <- {line}")
                if reply:
                    f.write((reply + "\n").encode("utf-8"))
                    f.flush()
    except OSError:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass
        print(f"[wdpc] отключён: {addr}")


def local_ips():
    ips = set()
    try:
        host = socket.gethostname()
        for ai in socket.getaddrinfo(host, None):
            ip = ai[4][0]
            if "." in ip and not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips)


def main():
    if not hasattr(ctypes, "windll"):
        sys.exit("Этот сервер предназначен для Windows.")
    print("=" * 52)
    print(" WDPC server (Windows) — порт", PORT)
    for ip in local_ips():
        print("   В приложении на телефоне введите IP:", ip)
    print(" Первый запуск Windows спросит разрешение для сети — «Разрешить»")
    print("=" * 52)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(4)
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\n[wdpc] остановлен")
    finally:
        srv.close()


if __name__ == "__main__":
    main()
