#!/usr/bin/env python3
"""Inspect a publication without printing the values that triggered a finding."""

import argparse
import ipaddress
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "PUBLIC_FILES.txt"
LEGACY_FILES = {"README.md", ".gitignore"}
SPECIAL_FILES = {".gitignore", ".gitattributes", "tools/hooks/pre-commit", "tools/hooks/pre-push"}
EXTENSIONS = {".md", ".txt", ".py", ".ps1", ".sh", ".cgi", ".c", ".rc", ".java", ".xml", ".example"}
RULES = {
    "ONION_HOST": r"(?i)\b[a-z2-7]{16,56}\.onion\b",
    "MAC_ADDRESS": r"(?i)(?<![\w:])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![\w:])",
    "PRIVATE_KEY": r"-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----",
    "ACCESS_TOKEN": r"(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{20,})",
    "SSH_IDENTITY": r"(?:ssh-(?:rsa|ed25519)\s+[A-Za-z0-9+/]{32,}|SHA256:[A-Za-z0-9+/]{32,})",
    "CARD_IDENTIFIER": r"(?i)(?<![\w-])[0-9a-f]{4}-[0-9a-f]{4}(?![\w-])",
    "TERMUX_ACCOUNT": r"\bu0_a\d+\b",
    "PERSONAL_PATH": r"(?i)(?:[a-z]:[\\/]Users[\\/][^\s\x22\x27]+|(?<![\w/])/" r"home/[^/\s\x22\x27]+)",
    "BITCOIN_IDENTIFIER": r"(?<!\w)(?:bc1[a-z0-9]{25,80}|[13][a-km-zA-HJ-NP-Z1-9]{25,34}|[xt]p(?:ub|rv)[a-km-zA-HJ-NP-Z1-9]{70,}|[5KL][a-km-zA-HJ-NP-Z1-9]{49,51})(?!\w)",
    "URL_CREDENTIAL": r"(?i)https?://[^\s/@:]+:[^\s/@]+@",
    "BEARER_TOKEN": r"(?i)\bBearer\s+[a-z0-9_.-]{20,}",
}
IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
IPV6 = re.compile(r"(?<![\w:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![\w:])")
EMAIL = re.compile(r"[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}")
ASSIGNMENT = re.compile(
    r"(?im)^\s*(?:\$)?(?:rpcuser|rpcpassword|rpcauth|password|passwd|secret|token|ssid|psk|passphrase)\s*[:=]\s*(.+)$"
)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.PIPE)


def inspect_text(raw, *, legacy_loopback=False):
    findings = []
    if b"\0" in raw:
        return [(1, "BINARY_CONTENT")]
    try:
        content = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return [(1, "NON_UTF8_CONTENT")]
    for number, line in enumerate(content.splitlines(), 1):
        for label, pattern in RULES.items():
            if re.search(pattern, line):
                findings.append((number, label))
        for pattern in (IPV4, IPV6):
            for match in pattern.finditer(line):
                try:
                    address = ipaddress.ip_address(match[0])
                except ValueError:
                    continue
                if not (legacy_loopback and address.is_loopback):
                    findings.append((number, "IP_ADDRESS"))
        for match in EMAIL.finditer(line):
            if not match[0].endswith("@users.noreply.github.com"):
                findings.append((number, "PERSONAL_EMAIL"))
        match = ASSIGNMENT.match(line)
        if match:
            value = match[1].strip().strip("\"'")
            if value and not value.startswith(("$", "@", "<")):
                findings.append((number, "CREDENTIAL_ASSIGNMENT"))
    return findings


def file_policy(path, mode="100644"):
    parts = PurePosixPath(path).parts
    if mode not in {"100644", "100755"}:
        return "NON_REGULAR_FILE"
    if any(p in {".git", "device-backup", "kit-v480-reproducible", "build", "__pycache__", ".local"} for p in parts):
        return "PRIVATE_OR_GENERATED_PATH"
    if path not in SPECIAL_FILES and PurePosixPath(path).suffix not in EXTENSIONS:
        return "UNREVIEWED_FILE_TYPE"
    return None


def manifest_entries(raw):
    entries = {line.strip() for line in raw.decode("utf-8-sig").splitlines()
               if line.strip() and not line.lstrip().startswith("#")}
    for path in entries:
        if path.startswith("/") or "\\" in path or ".." in PurePosixPath(path).parts or file_policy(path):
            raise ValueError("Invalid path in publication manifest")
    if MANIFEST not in entries:
        raise ValueError("Publication manifest must include itself")
    return entries


def index_files():
    files = {}
    for record in git("ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        meta, path = record.split(b"\t", 1)
        mode, oid, stage = meta.decode().split()
        if stage != "0":
            raise ValueError("Resolve merge conflicts before publication")
        files[path.decode("utf-8")] = (mode, oid)
    return files


def tree_files(ref):
    files = {}
    for record in git("ls-tree", "-r", "-z", ref).split(b"\0"):
        if record:
            meta, path = record.split(b"\t", 1)
            mode, kind, oid = meta.decode().split()
            files[path.decode("utf-8")] = (mode, oid)
    return files


def inspect_tree(files, read, label, *, legacy=False):
    problems = []
    if legacy and MANIFEST not in files and set(files) <= LEGACY_FILES:
        expected = LEGACY_FILES
        old_snapshot = True
    else:
        if MANIFEST not in files:
            return [f"{label}: MISSING_MANIFEST"]
        expected = manifest_entries(read(MANIFEST))
        old_snapshot = False
    for path in sorted(set(files) ^ expected):
        problems.append(f"{label}:{path}: FILE_NOT_IN_MANIFEST_OR_MISSING")
    for path, (mode, oid) in sorted(files.items()):
        policy = file_policy(path, mode)
        if policy:
            problems.append(f"{label}:{path}: {policy}")
            continue
        raw = read(path)
        if len(raw) > 250_000:
            problems.append(f"{label}:{path}: OVERSIZED_FILE")
            continue
        for number, rule in inspect_text(raw, legacy_loopback=old_snapshot):
            problems.append(f"{label}:{path}:{number}: {rule}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--staged", action="store_true", help="Check the entire Git index")
    mode.add_argument("--history", metavar="REF", help="Check every snapshot and commit reachable from REF")
    args = parser.parse_args()
    problems = []
    if args.history:
        for commit in git("rev-list", args.history).decode().splitlines():
            files = tree_files(commit)
            def read(path):
                return git("cat-file", "blob", files[path][1])
            problems.extend(inspect_tree(files, read, commit[:12], legacy=True))
            metadata = git("show", "-s", "--format=%an%n%ae%n%cn%n%ce%n%B", commit)
            problems.extend(f"{commit[:12]}:metadata:{n}: {rule}" for n, rule in inspect_text(metadata))
    elif args.staged:
        files = index_files()
        problems = inspect_tree(files, lambda path: git("cat-file", "blob", files[path][1]), "index")
    else:
        expected = manifest_entries((ROOT / MANIFEST).read_bytes())
        tracked = set(index_files())
        files = {path: ("120000" if (ROOT / path).is_symlink() else "100644", "")
                 for path in expected | tracked if (ROOT / path).exists()}
        problems = inspect_tree(files, lambda path: (ROOT / path).read_bytes(), "working-tree")
    if problems:
        print("PUBLICATION BLOCKED (values withheld):")
        print("\n".join(problems))
        return 1
    print("Publication check passed: manifest, file types and sensitive-data patterns.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"Publication check failed: {type(exc).__name__}. Review local configuration.", file=sys.stderr)
        sys.exit(2)
