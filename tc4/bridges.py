"""Bridge / pluggable-transport management.

Supports obfs4, snowflake, meek_lite and webtunnel built-in transports plus
fully custom user-supplied bridge lines. All state lives in the main config
(`use_bridges`, `bridge_type`, `custom_bridges`) so it persists atomically.

We deliberately do NOT ship hardcoded bridge addresses (they rot fast and can
harm users). Instead we validate user input and, where available, help fetch
fresh bridges from Tor's BridgeDB.
"""
from __future__ import annotations

import re

from . import config as config_mod
from .config import Config, _VALID_BRIDGE_TYPES
from .errors import ConfigError
from .log import get_logger

log = get_logger()

BRIDGE_TYPES = _VALID_BRIDGE_TYPES

# A loose validator: an obfs4 line looks like
#   obfs4 1.2.3.4:443 <FINGERPRINT> cert=... iat-mode=0
# while plain bridges are "IP:PORT [FINGERPRINT]".
_OBFS4_RE = re.compile(r"^obfs4\s+\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}\s+[0-9A-Fa-f]{40}\s+cert=\S+")
_PLAIN_RE = re.compile(r"^(?:Bridge\s+)?\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}(?:\s+[0-9A-Fa-f]{40})?$")
_GENERIC_PT_RE = re.compile(r"^(snowflake|meek_lite|webtunnel|obfs4)\s+\S+")


def validate_bridge_line(line: str) -> bool:
    line = line.strip()
    if not line or line.startswith("#"):
        return False
    return bool(_OBFS4_RE.match(line) or _PLAIN_RE.match(line)
                or _GENERIC_PT_RE.match(line))


def set_type(bridge_type: str, cfg: Config | None = None) -> Config:
    cfg = cfg or config_mod.load()
    if bridge_type not in BRIDGE_TYPES:
        raise ConfigError(f"unknown bridge type '{bridge_type}'",
                          hint=f"choose one of: {', '.join(BRIDGE_TYPES)}")
    cfg.bridge_type = bridge_type
    config_mod.save(cfg)
    log.info("bridge type set to %s", bridge_type)
    return cfg


def enable(on: bool, cfg: Config | None = None) -> Config:
    cfg = cfg or config_mod.load()
    cfg.use_bridges = bool(on)
    config_mod.save(cfg)
    log.info("bridges %s", "enabled" if on else "disabled")
    return cfg


def add(lines, cfg: Config | None = None) -> Config:
    """Add one or more custom bridge lines (string or list)."""
    cfg = cfg or config_mod.load()
    if isinstance(lines, str):
        lines = [lines]
    added = 0
    for raw in lines:
        line = raw.strip()
        if not validate_bridge_line(line):
            raise ConfigError(
                f"invalid bridge line: {line[:60]}",
                hint="Expected e.g. 'obfs4 1.2.3.4:443 <FP> cert=... iat-mode=0'.",
            )
        if line not in cfg.custom_bridges:
            cfg.custom_bridges.append(line)
            added += 1
    # If the user is adding custom obfs4 lines, switch to custom mode only
    # if the current type is not already a supported transport type.
    if added and cfg.bridge_type not in ("custom", "obfs4", "snowflake", "meek_lite", "webtunnel"):
        cfg.bridge_type = "custom"
    config_mod.save(cfg)
    log.info("added %d custom bridge(s)", added)
    return cfg


def remove(index_or_line, cfg: Config | None = None) -> Config:
    cfg = cfg or config_mod.load()
    if isinstance(index_or_line, int):
        if 0 <= index_or_line < len(cfg.custom_bridges):
            cfg.custom_bridges.pop(index_or_line)
        else:
            raise ConfigError(f"no bridge at index {index_or_line}")
    else:
        try:
            cfg.custom_bridges.remove(index_or_line.strip())
        except ValueError as exc:
            raise ConfigError("that bridge line is not in the list") from exc
    config_mod.save(cfg)
    return cfg


def clear(cfg: Config | None = None) -> Config:
    cfg = cfg or config_mod.load()
    cfg.custom_bridges = []
    config_mod.save(cfg)
    log.info("cleared all custom bridges")
    return cfg


def listing(cfg: Config | None = None) -> list[str]:
    cfg = cfg or config_mod.load()
    return list(cfg.custom_bridges)
