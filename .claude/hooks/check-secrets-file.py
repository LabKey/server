#!/usr/bin/env python3
#
# Copyright (c) 2026 LabKey Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""
Claude Code PreToolUse hook - blocks Read/Edit/Grep access to secrets files.
Closes the bypass where tools other than Bash can access sensitive files.
Works on macOS, Linux, and Windows.
"""

import json
import sys
import os
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from secrets_patterns import contains_secrets_reference, is_secrets_path, is_secrets_directory


DEBUG = False


def _log(detail: str) -> None:
    if not DEBUG:
        return
    try:
        log_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hooks.log")
        with open(log_path, "a", encoding="utf-8") as fh:
            fh.write(f"{datetime.now().isoformat()} check-secrets-file {detail}\n")
    except Exception:
        pass


def iter_candidate_paths(tool_input: dict) -> list[str]:
    """Collect direct and combined selectors used by file-oriented tools."""
    candidates = []

    file_path = tool_input.get("file_path")
    if isinstance(file_path, str) and file_path:
        candidates.append(file_path)

    path = tool_input.get("path")
    globs = []
    glob_value = tool_input.get("glob")

    if isinstance(glob_value, str) and glob_value:
        globs.append(glob_value)
    elif isinstance(glob_value, list):
        globs.extend(item for item in glob_value if isinstance(item, str) and item)

    if isinstance(path, str) and path:
        candidates.append(path)
        candidates.extend(os.path.join(path, pattern) for pattern in globs)
    else:
        candidates.extend(globs)

    return candidates


def main():
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        _log("decision=allow reason=unparseable-input")
        sys.exit(0)

    tool_input = data.get("tool_input", {})
    candidates = iter_candidate_paths(tool_input)
    if not candidates:
        _log("decision=allow reason=no-candidates")
        sys.exit(0)

    for file_path in candidates:
        if is_secrets_path(file_path) or contains_secrets_reference(file_path):
            reason = f"Blocked: accessing potential secrets file: {file_path}"
            _log(f"candidates={candidates!r} decision=block reason={reason!r}")
            response = {
                "decision": "block",
                "reason": reason
            }
            print(json.dumps(response))
            sys.exit(2)
        if is_secrets_directory(file_path):
            reason = f"Blocked: accessing directory that contains secrets: {file_path}"
            _log(f"candidates={candidates!r} decision=block reason={reason!r}")
            response = {
                "decision": "block",
                "reason": reason
            }
            print(json.dumps(response))
            sys.exit(2)

    _log(f"candidates={candidates!r} decision=allow")
    sys.exit(0)


if __name__ == "__main__":
    main()
