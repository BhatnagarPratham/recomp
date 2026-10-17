#!/usr/bin/env python3
"""Keeps secrets and personal data out of the repo.

Modes:
  staged          pre-commit: scan lines being added, staged file paths, and the commit identity
  message <file>  commit-msg: scan the commit message
  all             CI / manual: scan every tracked (and untracked, non-ignored) file plus all commit emails

What it blocks:
  - secrets (API keys, tokens, private keys)
  - personal identifiers listed in .git/info/sensitive-patterns (never committed; one regex per line)
  - email addresses and Indian mobile numbers, except allow-listed ones
  - body-metric values ("weight 96.4 kg", "body fat 31%") outside tests
  - files that tend to hold personal data: databases, backups, exports, screenshots, photos, keystores
  - commits whose author or committer email isn't a GitHub noreply address

A line containing `pii-ok` skips the email, phone and body-metric checks (for illustrative numbers in
docs). Secrets and your personal patterns are never skippable.
"""
import fnmatch
import re
import subprocess
import sys
from pathlib import Path

SECRETS = [
    ("Anthropic API key", r"sk-ant-[A-Za-z0-9_\-]{20,}"),
    ("API key (sk-…)", r"\bsk-[A-Za-z0-9]{32,}"),
    ("AWS access key", r"\bAKIA[0-9A-Z]{16}\b"),
    ("Google API key", r"\bAIza[0-9A-Za-z_\-]{35}\b"),
    ("GitHub token", r"\bgh[pousr]_[A-Za-z0-9]{36,}|\bgithub_pat_[A-Za-z0-9_]{40,}"),
    ("Slack token", r"\bxox[abprs]-[A-Za-z0-9-]{10,}"),
    ("Private key", r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    ("JWT", r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
    ("Hard-coded secret", r"(?i)\b(api[_-]?key|secret|token|password|passwd)\b\s*[:=]\s*[\"'][^\"'\s]{12,}[\"']"),
]

EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
ALLOWED_EMAIL = re.compile(
    r"(@users\.noreply\.github\.com|^noreply@anthropic\.com|@example\.(com|org|net)|@recomp\.invalid)$", re.I
)
PHONE = re.compile(r"(?<![\w.])(\+91[\s-]?)?[6-9]\d{9}(?![\w.])")
NUM = r"(?<![\d.,])"  # not the tail of a bigger number such as 1,500 or 12.5
BODY_METRICS = [
    re.compile(r"(?i)\b(weigh(t|s|ed|-in)?|lean mass|muscle mass|skeletal muscle|fat mass)\b[^\n]{0,30}?" + NUM
               + r"\d{2,3}(\.\d+)?\s?(kg|lbs?)(?![A-Za-z])"),
    re.compile(r"(?i)\b(body[ -]?fat|bf|pbf)\b[^\n]{0,20}?" + NUM + r"\d{1,2}(\.\d+)?\s?%"),
    re.compile(r"(?i)\b(waist|height|chest|hips?|thighs?|arms?)\b[^\n]{0,20}?" + NUM + r"\d{2,3}(\.\d+)?\s?(cm|in)(?![A-Za-z])"),
    re.compile(r"(?i)\b(bmr|tdee)\b\s*(is|of|was|=|:|~|≈)?\s*" + NUM + r"\d{1,2},?\d{3}\s?kcal"),
]
SKIP_MARKER = "pii-ok"

BLOCKED_FILES = [
    "local.properties", ".env", ".env.*", "*.keystore", "*.jks", "*.p12", "*.pem", "*.key",
    "*.db", "*.db-wal", "*.db-shm", "*.sqlite", "*.sqlite3", "*.hprof", "*.log",
    "*backup*.zip", "recomp-backup-*", "*.apk", "*.aab",
]
MEDIA = ["*.png", "*.jpg", "*.jpeg", "*.webp", "*.heic", "*.gif", "*.pdf", "*.mp4", "*.mov"]
MEDIA_ALLOWED_UNDER = ["app/src/main/res/"]
TABULAR = ["*.csv", "*.tsv", "*.xls", "*.xlsx"]
TABULAR_ALLOWED_UNDER = ["app/src/main/assets/food/"]
TEST_DIRS = ("/src/test/", "/src/androidTest/")
SELF = ".githooks/sensitive_scan.py"


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def personal_patterns():
    path = Path(git("rev-parse", "--git-common-dir").strip()) / "info" / "sensitive-patterns"
    if not path.exists():
        return [], path
    pats = []
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            pats.append(re.compile(line, re.I))
    return pats, path


def redact(text):
    text = text.strip()
    return text if len(text) <= 6 else text[:4] + "…" + f"({len(text)} chars)"


class Scanner:
    def __init__(self):
        self.personal, self.personal_path = personal_patterns()
        self.findings = []

    def add(self, where, rule, text):
        self.findings.append(f"  {where}: [{rule}] {redact(text)}")

    def scan_line(self, where, line, path=""):
        for name, pattern in SECRETS:
            for m in re.finditer(pattern, line):
                self.add(where, name, m.group(0))
        for pat in self.personal:
            for m in pat.finditer(line):
                self.add(where, "personal pattern", m.group(0))
        if SKIP_MARKER in line:
            return
        for m in EMAIL.finditer(line):
            if not ALLOWED_EMAIL.search(m.group(0)):
                self.add(where, "email address", m.group(0))
        for m in PHONE.finditer(line):
            self.add(where, "phone number", m.group(0))
        if not any(d in "/" + path for d in TEST_DIRS):
            for pattern in BODY_METRICS:
                for m in pattern.finditer(line):
                    self.add(where, "body metric value", m.group(0))

    def scan_path(self, path):
        name = Path(path).name
        for pat in self.personal:
            if pat.search(path):
                self.add(path, "personal pattern in file name", path)
        if any(fnmatch.fnmatch(name, g) for g in BLOCKED_FILES):
            self.add(path, "file type that can hold secrets or personal data", name)
        if any(fnmatch.fnmatch(name.lower(), g) for g in MEDIA) and not path.startswith(tuple(MEDIA_ALLOWED_UNDER)):
            self.add(path, "image/PDF outside app/src/main/res (screenshots, photos, InBody sheets)", name)
        if any(fnmatch.fnmatch(name.lower(), g) for g in TABULAR) and not path.startswith(tuple(TABULAR_ALLOWED_UNDER)):
            self.add(path, "spreadsheet/CSV outside app/src/main/assets/food (exports, logs)", name)

    def check_identity_email(self, label, ident):
        m = re.search(r"<([^>]*)>", ident)
        email = m.group(1) if m else ident
        if not email.lower().endswith("@users.noreply.github.com"):
            self.add(label, "commit email is not a GitHub noreply address", email)


def is_binary(data: bytes) -> bool:
    return b"\0" in data[:8000]


def mode_staged(s: Scanner):
    for var in ("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT"):
        s.check_identity_email(var.lower().replace("git_", "").replace("_ident", ""), git("var", var))
    for path in git("diff", "--cached", "--name-only", "--diff-filter=ACMR").splitlines():
        s.scan_path(path)
    diff = git("diff", "--cached", "-U0", "--no-color", "--no-ext-diff", "--diff-filter=ACMR")
    path, lineno = None, 0
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            m = re.search(r"\+(\d+)", line)
            lineno = int(m.group(1)) if m else 0
        elif line.startswith("+") and path and path != SELF:
            s.scan_line(f"{path}:{lineno}", line[1:], path)
            lineno += 1


def mode_message(s: Scanner, msg_file):
    for i, line in enumerate(Path(msg_file).read_text().splitlines(), 1):
        if not line.startswith("#"):
            s.scan_line(f"commit message:{i}", line)


def mode_all(s: Scanner):
    files = git("ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    for path in files:
        s.scan_path(path)
        p = Path(path)
        if path == SELF or not p.is_file():
            continue
        data = p.read_bytes()
        if is_binary(data):
            continue
        for i, line in enumerate(data.decode("utf-8", errors="replace").splitlines(), 1):
            s.scan_line(f"{path}:{i}", line, path)
    try:
        emails = set(git("log", "--all", "--format=%ae%n%ce").split())
    except subprocess.CalledProcessError:
        emails = set()  # no commits yet
    for email in emails:
        s.check_identity_email("history", email)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "staged"
    s = Scanner()
    if mode == "staged":
        mode_staged(s)
    elif mode == "message":
        mode_message(s, sys.argv[2])
    elif mode == "all":
        mode_all(s)
    else:
        sys.exit(f"unknown mode {mode}")

    if not s.personal and mode != "all":
        print(f"warning: no personal patterns at {s.personal_path}; only generic checks ran.", file=sys.stderr)
    if s.findings:
        print("✋ Blocked: possible secrets or personal data.\n", file=sys.stderr)
        print("\n".join(s.findings), file=sys.stderr)
        print(
            "\nRemove them, or for an illustrative number/email in docs add `pii-ok` to that line."
            "\nSecrets and personal patterns can't be allow-listed.",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()
