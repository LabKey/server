"""
Shared secrets file patterns used by both check-dangerous-commands.py and check-secrets-file.py.
Edit this single file to update secrets detection across all hooks.
"""

import re

# Patterns matching secrets file paths (case-insensitive)
SECRETS_PATTERNS = [
    r'\.env$',
    r'\.env\.(?:local|development|test|staging|production)$',
    r'\.pem$',
    r'_rsa$',
    r'_ed25519$',
    r'\.key$',
    r'(^|[/\\])credentials$',
    r'(^|[/\\])\.aws[/\\]',
    r'(^|[/\\])\.ssh[/\\]',
    r'server[/\\]configs[/\\](application|mssql|pg)\.properties$',
]

# Patterns matching directories known to contain secrets files
SECRETS_DIR_PATTERNS = [
    r'(^|[/\\])\.aws[/\\]?$',
    r'(^|[/\\])\.ssh[/\\]?$',
]


# Boundary assertion: characters that typically follow a secrets filename in
# commands, glob patterns, or string literals. Prevents false positives like
# .keystore, .environment. Includes quotes and commas to catch paths embedded
# in code strings (e.g., open('.env')).
# NOTE: When adding patterns to SECRETS_PATTERNS, also add a corresponding entry here.
_END = r"""(?=[\s;|&<>)*?'",]|$)"""

REFERENCE_PATTERNS = [
    r'\.env' + _END,
    r'\.env\.(?:local|development|test|staging|production)' + _END,
    r'\.pem' + _END,
    r'_rsa' + _END,
    r'_ed25519' + _END,
    r'\.key' + _END,
    r'[/\\]credentials' + _END,
    r'[/\\]\.aws[/\\]',
    r'[/\\]\.ssh[/\\]',
    r'server[/\\]configs[/\\](?:(?:application|mssql|pg)\.properties|\*\.properties)' + _END,
]


def is_secrets_path(file_path: str) -> bool:
    """Check if a file path matches any secrets pattern."""
    for pattern in SECRETS_PATTERNS:
        if re.search(pattern, file_path, re.IGNORECASE):
            return True
    return False


def is_secrets_directory(dir_path: str) -> bool:
    """Check if a directory path points to a directory known to contain secrets."""
    normalized = dir_path.rstrip("/\\").strip()
    for pattern in SECRETS_DIR_PATTERNS:
        if re.search(pattern, normalized, re.IGNORECASE):
            return True
    return False


def contains_secrets_reference(text: str) -> bool:
    """Check whether arbitrary text contains a secret-looking path reference."""
    for pattern in REFERENCE_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE):
            return True
    return False
