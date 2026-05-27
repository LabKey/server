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
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from secrets_patterns import contains_secrets_reference, is_secrets_path


DEBUG = False


def _log(detail: str) -> None:
    if not DEBUG:
        return
    try:
        log_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hooks.log")
        with open(log_path, "a", encoding="utf-8") as fh:
            fh.write(f"{datetime.now().isoformat()} check-dangerous-commands {detail}\n")
    except Exception:
        pass


# Mask the entire user-info section of scheme://...@host URLs before logging. Covers all
# three common forms: user:pass@ (basic auth), TOKEN@ (token-as-user), and TOKEN:x-oauth-basic@
# (GitHub PAT convention). SSH-style git@host:path URLs have no scheme://, so they're untouched.
_CRED_URL_RE = re.compile(r'(://)[^@\s/]+(@)')


def _safe_command(command: str) -> str:
    return _CRED_URL_RE.sub(r'\1***\2', command)


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


GIT_ASK_PATTERNS = [
    # Leading gap is [^\n;&|]*? so flags that take a separate-token value (e.g. `git -C <path>`,
    # `git -c key=value`) don't bypass the match. The gap is constrained to a single logical
    # command (no pipe/semicolon/&&) and non-greedy to keep matches tight. Each verb is wrapped
    # with (?<=\s)<verb>(?=\s|$) so it must be a standalone token: dotted config keys like
    # `push.default` are rejected by the trailing lookahead, and trailing patterns inside flag
    # values like `--grep=commit` are rejected by the leading lookbehind.
    # Force push: match before plain push so we emit the more specific reason.
    (
        r'\bgit\s+[^\n;&|]*?(?<=\s)push(?=\s|$)[^\n;&|]*(?:--force\b|--force-with-lease\b|\s-f\b)',
        "git force-push detected — confirm before proceeding",
    ),
    (
        r'\bgit\s+[^\n;&|]*?(?<=\s)push(?=\s|$)',
        "git push detected — confirm before proceeding",
    ),
    (
        r'\bgit\s+[^\n;&|]*?(?<=\s)commit(?=\s|$)',
        "git commit detected — confirm before proceeding",
    ),
    (
        r'\bgit\s+[^\n;&|]*?(?<=\s)reset(?=\s|$)[^\n;&|]*\s--hard\b',
        "git reset --hard detected — confirm before proceeding",
    ),
    (
        r'\bgit\s+[^\n;&|]*?\bbranch\b[^\n;&|]*\s(?-i:-D)\b',
        "git branch -D detected — confirm before proceeding",
    ),
    (
        r'\bgit\s+[^\n;&|]*?(?:checkout\s+-[bB]|switch\s+(?:-[cC]|--(?:force-)?create)|branch\s+(?:(?!-)\S+|-t|--track|-[mMcCfF]|--(?:move|copy|force)))\b',
        "git branch creation detected — confirm name before proceeding",
    ),
    (
        r'\bgh\s+[^\n;&|]*?\bpr\s+create\b',
        "gh pr create detected — confirm title/body before proceeding",
    ),
    (
        r'\bgh\s+[^\n;&|]*?\bpr\s+edit\b',
        "gh pr edit detected — confirm title/body before proceeding",
    ),
    (
        r'\bgh\s+[^\n;&|]*?\bpr\s+merge\b',
        "gh pr merge detected — confirm before proceeding",
    ),
    (
        r'\bgh\s+[^\n;&|]*?\bpr\s+close\b',
        "gh pr close detected — confirm before proceeding",
    ),
]


def check_git_for_ask(command: str) -> tuple[bool, str]:
    """Returns (should_ask, joined_reason). Surfaces every distinct op in compound commands.

    For overlapping matches (e.g. the force-push pattern is a superset of the plain-push
    pattern), the wider/earlier-listed pattern wins and suppresses the narrower one.
    """
    matches = []  # (start, end, reason)
    for pattern, reason in GIT_ASK_PATTERNS:
        for m in re.finditer(pattern, command, re.IGNORECASE):
            matches.append((m.start(), m.end(), reason))

    # Sort by position; at equal start, prefer the wider span (negative end as tiebreaker).
    matches.sort(key=lambda t: (t[0], -t[1]))

    kept_spans = []
    ordered_reasons = []
    seen = set()
    for start, end, reason in matches:
        if any(ks <= start < ke for ks, ke in kept_spans):
            continue
        kept_spans.append((start, end))
        if reason not in seen:
            seen.add(reason)
            ordered_reasons.append(reason)

    if not ordered_reasons:
        return False, ""
    return True, "; ".join(ordered_reasons)


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
        _log("command='' decision=allow reason=unparseable-input")
        sys.exit(0)  # Can't parse input — allow and move on

    command = data.get("tool_input", {}).get("command", "")
    if not command:
        _log("command='' decision=allow reason=no-command")
        sys.exit(0)

    blocked, reason = check_command(command)

    if blocked:
        # Block-on-secret reasons reference the secret path that triggered the match; logging
        # the full command would re-emit that path. Drop the command for those cases.
        if "secrets file" in reason or "secrets" in reason:
            _log(f"decision=block reason={reason!r} (command omitted)")
        else:
            _log(f"command={_safe_command(command)!r} decision=block reason={reason!r}")
        response = {"decision": "block", "reason": reason}
        print(json.dumps(response))
        sys.exit(2)

    ask, ask_reason = check_git_for_ask(command)
    if ask:
        _log(f"command={_safe_command(command)!r} decision=ask reason={ask_reason!r}")
        response = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "ask",
                "permissionDecisionReason": ask_reason,
            }
        }
        print(json.dumps(response))
        sys.exit(0)

    _log(f"command={_safe_command(command)!r} decision=allow")
    sys.exit(0)


if __name__ == "__main__":
    main()
