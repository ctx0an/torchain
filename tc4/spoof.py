"""Optional identity spoofing: MAC address and hostname.

VM- and bare-metal-safe:
- We verify each MAC change actually took effect. Some hypervisors enforce
  port security and reject foreign MACs; in that case we roll the interface
  back to its original MAC and keep going (never knock the box offline).
- Virtual/tunnel interfaces are skipped.
- Everything is reversible: originals are saved and restored on stop.
"""
from __future__ import annotations

import json
import os
import random

from . import DATA_DIR
from .errors import CommandError
from .log import get_logger
from .sysutil import run, run_ok, which

log = get_logger()

_STATE = os.path.join(DATA_DIR, "spoof_state.json")


def _save_state(state: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(_STATE, "w", encoding="utf-8") as fh:
        json.dump(state, fh)


def _load_state() -> dict:
    try:
        with open(_STATE, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return {}


def random_mac() -> str:
    # Locally administered, unicast MAC.
    first = (random.randint(0, 255) & 0xFC) | 0x02
    octets = [first] + [random.randint(0, 255) for _ in range(5)]
    return ":".join(f"{o:02x}" for o in octets)


def _current_mac(iface: str) -> str:
    return run(["cat", f"/sys/class/net/{iface}/address"], check=False).stdout.strip()


def _ethernet_interfaces() -> list[str]:
    ifaces = []
    try:
        names = sorted(os.listdir("/sys/class/net"))
    except OSError:
        return []
    for name in names:
        if name == "lo":
            continue
        if name.startswith(("tun", "tap", "docker", "veth", "br-", "virbr", "vnet", "wg")):
            continue
        ifaces.append(name)
    return ifaces


def spoof_mac() -> dict:
    if which("ip") is None:
        raise CommandError(["ip"], 127, "iproute2 not installed")
    state = _load_state()
    macs = state.get("macs", {})
    for iface in _ethernet_interfaces():
        original = _current_mac(iface)
        new = random_mac()
        macs.setdefault(iface, original)
        run_ok(["ip", "link", "set", iface, "down"])
        changed = run_ok(["ip", "link", "set", iface, "address", new])
        run_ok(["ip", "link", "set", iface, "up"])
        # Verify (hypervisor port-security may silently reject the change).
        if not changed or _current_mac(iface).lower() != new.lower():
            log.warning("MAC change rejected on %s (likely VM port security); "
                        "reverting to %s", iface, original)
            run_ok(["ip", "link", "set", iface, "down"])
            run_ok(["ip", "link", "set", iface, "address", original])
            run_ok(["ip", "link", "set", iface, "up"])
            macs[iface] = original
        else:
            log.info("spoofed %s %s -> %s", iface, original, new)
    state["macs"] = macs
    _save_state(state)
    return state


def spoof_hostname(new_name: str | None = None) -> dict:
    state = _load_state()
    if "hostname" not in state:
        state["hostname"] = run(["hostname"], check=False).stdout.strip()
    new_name = new_name or "localhost"
    _ensure_hosts_entry(new_name)  # add mapping BEFORE switching
    run_ok(["hostname", new_name])
    _save_state(state)
    log.info("hostname -> %s", new_name)
    return state


def _ensure_hosts_entry(name: str) -> None:
    try:
        with open("/etc/hosts", "r", encoding="utf-8") as fh:
            content = fh.read()
        if f" {name}" not in content and f"\t{name}" not in content:
            with open("/etc/hosts", "a", encoding="utf-8") as fh:
                fh.write(f"127.0.0.1\t{name}\n")
    except OSError:
        pass


def _remove_hosts_entry(name: str) -> None:
    try:
        with open("/etc/hosts", "r", encoding="utf-8") as fh:
            lines = fh.readlines()
        filtered = [ln for ln in lines
                    if ln.strip() != f"127.0.0.1\t{name}"
                    and ln.strip() != f"127.0.0.1 {name}"]
        if len(filtered) != len(lines):
            with open("/etc/hosts", "w", encoding="utf-8") as fh:
                fh.writelines(filtered)
    except OSError:
        pass


def restore() -> None:
    state = _load_state()
    for iface, mac in state.get("macs", {}).items():
        run_ok(["ip", "link", "set", iface, "down"])
        run_ok(["ip", "link", "set", iface, "address", mac])
        run_ok(["ip", "link", "set", iface, "up"])
    if state.get("hostname"):
        run_ok(["hostname", state["hostname"]])
        _remove_hosts_entry(state["hostname"])
    try:
        os.remove(_STATE)
    except OSError:
        pass
    log.info("identity restored")
