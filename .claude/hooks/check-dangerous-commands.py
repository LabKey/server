#!/usr/bin/env python3
"""
Claude Code PreToolUse hook - cross-platform dangerous command checker
Works on macOS, Linux, and Windows (native or WSL)
"""

import json
import sys
import re
import os
import shlex

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from secrets_patterns import contains_secrets_reference, is_secrets_path


SHELL_OPERATORS = {"|", "||", "&", "&&", ";", ">", ">>", "<", "<<"}


def tokenize_command(command: str) -> list[str]:
    """Split a command conservatively for Unix and Windows-like shells."""
    for posix in (True, False):
        try:
            tokens = shlex.split(command, posix=posix)
        except ValueError:
            continue
        if tokens:
            return tokens
    return re.findall(r'"[^"]*"|\'[^\']*\'|\S+', command)


def looks_like_path_fragment(text: str) -> bool:
    """Heuristic: treat plain path-like args differently from code expressions."""
    return not any(ch in text for ch in "()=:,")


def command_touches_secret(command: str) -> bool:
    """Block commands that reference secret-looking paths anywhere in their args."""
    for token in tokenize_command(command):
        cleaned = token.strip("\"'`()[]{} ,")
        cleaned = cleaned.rstrip(";|&<>")
        if not cleaned or cleaned.startswith("-") or cleaned in SHELL_OPERATORS:
            continue
        if (
            (looks_like_path_fragment(cleaned) and is_secrets_path(cleaned))
            or contains_secrets_reference(token)
            or contains_secrets_reference(cleaned)
        ):
            return True
    return False


def check_command(command: str) -> tuple[bool, str]:
    """Returns (blocked, reason)"""

    checks = [
        # Recursive force deletes on root/home (handles both -rf and -fr)
        (
            r'rm\s+-(?=[a-z]*r)(?=[a-z]*f)[a-z]+\s+(/\s*$|/\s*\*|~/?\s*$|~/?\s*\*)',
            "Blocked: recursive force delete on root or home directory"
        ),
        # Recursive force deletes on system directories
        (
            r'rm\s+-(?=[a-z]*r)(?=[a-z]*f)[a-z]+\s+/(home|var|opt|usr|etc|boot|lib|sbin|root)\b',
            "Blocked: recursive force delete on system directory"
        ),
        # Windows recursive force delete
        (
            r'(Remove-Item|ri)\s+.*-Recurse.*-Force\s+(C:\\\\?|~)',
            "Blocked: recursive force delete on root or home (Windows)"
        ),
        # Pipe-to-shell (supply chain risk)
        (
            r'(curl|wget|iwr|Invoke-WebRequest).+\|\s*(bash|sh|zsh|python3?|node|iex)',
            "Blocked: pipe-to-shell pattern detected (supply chain risk)"
        ),
        # chmod 777
        (
            r'chmod\s+(-R\s+)?777',
            "Blocked: chmod 777 is a security risk"
        ),
        # Writing to unix system dirs
        (
            r'(>>?|tee)\s+/(etc|usr|bin|sbin|lib|boot)/',
            "Blocked: write to system directory"
        ),
        # Writing to Windows system dirs
        (
            r'(>>?|Out-File|Set-Content|Add-Content)\s+["\']?C:\\(Windows|System32|Program Files)',
            "Blocked: write to Windows system directory"
        ),
        # Dropping/truncating databases (extra caution)
        (
            r'DROP\s+(DATABASE|TABLE|SCHEMA)\s+\w+',
            "Blocked: destructive SQL statement — confirm manually if intentional"
        ),
    ]

    for pattern, reason in checks:
        if re.search(pattern, command, re.IGNORECASE):
            return True, reason

    if command_touches_secret(command):
        return True, "Blocked: command references potential secrets file"

    return False, ""


def main():
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)  # Can't parse input — allow and move on

    command = data.get("tool_input", {}).get("command", "")
    if not command:
        sys.exit(0)

    blocked, reason = check_command(command)

    if blocked:
        response = {"decision": "block", "reason": reason}
        print(json.dumps(response))
        sys.exit(2)

    sys.exit(0)


if __name__ == "__main__":
    main()
