"""Thin, safe wrappers around subprocess and the environment.

Goals: never hang (always a timeout), never leak file descriptors, and turn
every failure into a typed CommandError with the captured stderr so callers
get actionable messages instead of opaque tracebacks.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from typing import Sequence

from .errors import CommandError, DependencyError, PrivilegeError, TimeoutError_
from .log import get_logger

log = get_logger()


def which(binary: str) -> str | None:
    return shutil.which(binary)


def require_binaries(*binaries: str) -> None:
    missing = [b for b in binaries if which(b) is None]
    if missing:
        raise DependencyError(
            f"missing required executables: {', '.join(missing)}",
            hint="Run 'torchain doctor' or './setup.sh' to install dependencies.",
        )


def is_root() -> bool:
    return os.geteuid() == 0


def require_root() -> None:
    if not is_root():
        raise PrivilegeError(
            "this operation requires root privileges",
            hint="Re-run with sudo, e.g. 'sudo torchain start'.",
        )


def run(
    cmd: Sequence[str],
    *,
    timeout: float = 30.0,
    check: bool = True,
    capture: bool = True,
    input_text: str | None = None,
    env: dict | None = None,
) -> subprocess.CompletedProcess:
    """Run a command with a hard timeout and typed error handling."""
    log.debug("exec: %s", " ".join(cmd))
    try:
        proc = subprocess.run(
            list(cmd),
            timeout=timeout,
            input=input_text,
            text=True,
            capture_output=capture,
            env=env,
        )
    except FileNotFoundError as exc:
        raise DependencyError(f"executable not found: {cmd[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise TimeoutError_(
            f"command timed out after {timeout:g}s: {' '.join(cmd)}"
        ) from exc
    if check and proc.returncode != 0:
        raise CommandError(cmd, proc.returncode, proc.stderr or "")
    return proc


def run_ok(cmd: Sequence[str], *, timeout: float = 15.0) -> bool:
    """Return True if the command exits zero; never raises on non-zero."""
    try:
        return run(cmd, timeout=timeout, check=False).returncode == 0
    except TorChainErrorTuple:  # pragma: no cover - defensive
        return False


# Tuple of swallowable errors for run_ok.
from .errors import TorChainError as _TCE  # noqa: E402

TorChainErrorTuple = (_TCE,)


def read_first_line(path: str, default: str = "") -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.readline().strip()
    except OSError:
        return default
