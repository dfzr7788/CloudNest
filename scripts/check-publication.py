"""Check tracked AND not-ignored untracked files; never print secret contents."""
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
raw = subprocess.check_output(
    ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
)
paths = sorted(set(p.decode("utf-8") for p in raw.split(b"\0") if p))
private_dirs = {".data", ".tools", "backups", "uploads", "secrets", "node_modules", "target", "dist"}
private_suffixes = {".pem", ".key", ".p12", ".pfx", ".jks", ".db", ".sqlite", ".sqlite3", ".dump", ".log", ".pid"}
patterns = [
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{40,}\b"),
    re.compile(rb"\bAKIA[A-Z0-9]{16}\b"),
]
problems = []
for name in paths:
    rel = pathlib.PurePosixPath(name)
    file = root / name
    if (private_dirs.intersection(rel.parts)
        or rel.suffix.lower() in private_suffixes
        or (rel.name.startswith(".env") and rel.name != ".env.example")
        or name.startswith("docs/evidence/")):
        problems.append((name, "private/runtime file"))
        continue
    if not file.is_file():
        continue
    if file.stat().st_size > 10 * 1024 * 1024:
        problems.append((name, "unexpected file over 10 MiB"))
        continue
    data = file.read_bytes()
    if any(pattern.search(data) for pattern in patterns):
        problems.append((name, "possible credential"))
for name, reason in problems:
    print(f"BLOCKED: {name}: {reason}")
print(f"Checked {len(paths)} candidate files; {len(problems)} issue(s).")
print("This checks common patterns, not a guarantee. Review staged changes before publishing.")
sys.exit(1 if problems else 0)
