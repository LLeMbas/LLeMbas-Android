#!/usr/bin/env python3
"""The server's contract, pinned: `contract/*.json` from `tests/fixtures/api_v2/` at a ref, and
the harness spec's conformance cases the app draws by (`harness_*.json`) from the server's copy.

    scripts/fetch-contract.py <server-ref>   copy the fixtures at that ref, write contract/lock.json
    scripts/fetch-contract.py                verify: contract/ matches contract/lock.json

The fixtures are written by the server's serialisers themselves (its
`tests/test_api_v2_contract.py`); `ContractTest` parses every one with this app's models. So a
shape the server changed is a failing test here, at the moment the pin moves -- not a blank
screen on a phone.
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = Path(os.environ.get("LEMBAS_SERVER", ROOT.parent / "LLeMbas"))
SOURCE = "tests/fixtures/api_v2"
# The harness spec's conformance cases a client draws by, from the server's vendored copy of the
# spec at the same ref: `harness_<name>.json` beside the fixtures, once the server has them.
HARNESS = "src/lembas/harness_spec/conformance"
HARNESS_CASES = ("progress.json",)
OUT = ROOT / "contract"
LOCK = OUT / "lock.json"


def digest() -> str:
    h = hashlib.sha256()
    for path in sorted(OUT.glob("*.json")):
        if path.name == LOCK.name:
            continue
        h.update(path.name.encode() + b"\0" + path.read_bytes())
    return h.hexdigest()


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(SERVER), *args], capture_output=True, text=True, check=True).stdout


def main() -> None:
    if len(sys.argv) == 1:
        lock = json.loads(LOCK.read_text())
        if digest() != lock["sha256"]:
            sys.exit(f"fetch-contract: contract/ does not match {LOCK.name} (server {lock['ref']})")
        print(f"fetch-contract: contract/ matches the server at {lock['ref']}")
        return
    ref = sys.argv[1]
    commit = git("rev-parse", "--verify", f"{ref}^{{commit}}").strip()
    names = [n for n in git("ls-tree", "--name-only", f"{commit}:{SOURCE}").split() if n.endswith(".json")]
    if not names:
        sys.exit(f"fetch-contract: no fixtures at {ref}:{SOURCE}")
    OUT.mkdir(exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    for name in names:
        (OUT / name).write_text(git("show", f"{commit}:{SOURCE}/{name}"), encoding="utf-8")
    have = set(git("ls-tree", "--name-only", f"{commit}:{HARNESS}").split()) if harness_at(commit) else set()
    for name in HARNESS_CASES:
        if name in have:
            (OUT / f"harness_{name}").write_text(git("show", f"{commit}:{HARNESS}/{name}"), encoding="utf-8")
            names.append(f"harness_{name}")
    LOCK.write_text(json.dumps({"ref": ref, "commit": commit, "sha256": digest()}, indent=2) + "\n")
    print(f"fetch-contract: {len(names)} files from {ref} ({commit[:12]})")


def harness_at(commit: str) -> bool:
    found = subprocess.run(["git", "-C", str(SERVER), "cat-file", "-e", f"{commit}:{HARNESS}"], capture_output=True)
    return found.returncode == 0


if __name__ == "__main__":
    main()
