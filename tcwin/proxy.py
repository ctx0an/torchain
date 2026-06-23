"""System (WinINET) proxy control.

Windows has no transparent proxy, so to actually route applications through
tor we point the per-user WinINET proxy at tor's SOCKS port. WinINET-based
apps (Edge, Chrome by default, most desktop apps that honor system proxy)
then tunnel through tor. Combined with the firewall kill-switch, apps that
ignore the proxy simply cannot reach the network.

We save the previous proxy settings and restore them exactly on teardown.
"""
from __future__ import annotations

import ctypes
import json
import os

from . import DATA_DIR, SOCKS_PORT
from .log import get_logger

log = get_logger()

try:
    import winreg  # type: ignore
except ImportError:  # pragma: no cover - allows import on non-Windows for tests
    winreg = None  # type: ignore

_KEY = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
_STATE = os.path.join(DATA_DIR, "proxy_state.json")

_INTERNET_OPTION_SETTINGS_CHANGED = 39
_INTERNET_OPTION_REFRESH = 37


def _refresh() -> None:
    try:
        wininet = ctypes.windll.wininet
        wininet.InternetSetOptionW(0, _INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
        wininet.InternetSetOptionW(0, _INTERNET_OPTION_REFRESH, 0, 0)
    except Exception as exc:  # noqa: BLE001
        log.debug("proxy refresh notify failed: %s", exc)


def _read(name: str):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _KEY, 0, winreg.KEY_READ) as k:
        try:
            value, _typ = winreg.QueryValueEx(k, name)
            return value
        except FileNotFoundError:
            return None


def _save_current() -> None:
    try:
        state = {
            "ProxyEnable": _read("ProxyEnable"),
            "ProxyServer": _read("ProxyServer"),
            "AutoConfigURL": _read("AutoConfigURL"),
        }
        os.makedirs(DATA_DIR, exist_ok=True)
        with open(_STATE, "w", encoding="utf-8") as fh:
            json.dump(state, fh)
    except OSError as exc:
        log.debug("could not save proxy state: %s", exc)


def enable(socks_port: int = SOCKS_PORT) -> None:
    """Point the WinINET proxy at the local tor SOCKS port."""
    if winreg is None:
        return
    if not os.path.exists(_STATE):
        _save_current()
    server = f"socks=127.0.0.1:{socks_port}"
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _KEY, 0, winreg.KEY_SET_VALUE) as k:
        winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(k, "ProxyServer", 0, winreg.REG_SZ, server)
        # A PAC/AutoConfig URL would override the manual proxy; clear it.
        try:
            winreg.DeleteValue(k, "AutoConfigURL")
        except FileNotFoundError:
            pass
    _refresh()
    log.info("system proxy set to %s", server)


def disable() -> None:
    """Restore the user's previous proxy settings (or simply turn it off)."""
    if winreg is None:
        return
    prev = {}
    try:
        with open(_STATE, "r", encoding="utf-8") as fh:
            prev = json.load(fh)
    except (OSError, json.JSONDecodeError):
        prev = {}
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _KEY, 0, winreg.KEY_SET_VALUE) as k:
        winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD,
                          int(prev.get("ProxyEnable") or 0))
        server = prev.get("ProxyServer")
        if server:
            winreg.SetValueEx(k, "ProxyServer", 0, winreg.REG_SZ, server)
        else:
            try:
                winreg.DeleteValue(k, "ProxyServer")
            except FileNotFoundError:
                pass
        pac = prev.get("AutoConfigURL")
        if pac:
            winreg.SetValueEx(k, "AutoConfigURL", 0, winreg.REG_SZ, pac)
    _refresh()
    try:
        os.remove(_STATE)
    except OSError:
        pass
    log.info("system proxy restored")


def is_set(socks_port: int = SOCKS_PORT) -> bool:
    if winreg is None:
        return False
    try:
        return bool(_read("ProxyEnable")) and \
            str(socks_port) in str(_read("ProxyServer") or "")
    except OSError:
        return False
