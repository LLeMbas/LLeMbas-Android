#!/usr/bin/env python3
"""The wake-word engine and its model, for the `full` build -- fetched, never committed.

    scripts/fetch-wakeword.py      download (or check) both, hash-pinned

The engine is sherpa-onnx (Apache-2.0, k2-fsa): its Android library, as the project publishes it
on its GitHub releases (onnxruntime inside, MIT). The model is its English keyword spotter,
`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (Apache-2.0, trained with icefall), of
which the int8 encoder, decoder and joiner and the token table go into the APK's assets, beside a
`version` file (a hash of the four pinned hashes): the app copies the model out of the APK once
per version, so a new model replaces the old one on the phone.

Neither file is in the repository: prebuilt binaries are not source. CI runs this before Gradle;
an F-Droid build, which builds every library from source, uses the `fdroid` flavour, which has
no engine (`app/src/fdroid/.../wake/Engine.kt`). Every download is checked against the SHA-256
below before anything is written where the build reads it.
"""

from __future__ import annotations

import hashlib
import io
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION = "2.0.1"
AAR_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar"
AAR_SHA = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
AAR = ROOT / "app/libs" / f"sherpa-onnx-{VERSION}.aar"

MODEL = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
MODEL_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/{MODEL}.tar.bz2"
MODEL_SHA = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
ASSETS = ROOT / "app/src/full/assets/wakeword"
# The file in the archive -> its name in the assets, and its SHA-256.
FILES = {
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx": ("encoder.int8.onnx", "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"),
    "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx": ("decoder.int8.onnx", "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a"),
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx": ("joiner.int8.onnx", "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"),
    "tokens.txt": ("tokens.txt", "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"),
}
# What the app keys its copy of the model by: changes whenever a pinned file does.
MODEL_VERSION = hashlib.sha256("".join(sorted(d for _, d in FILES.values())).encode()).hexdigest()[:16]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def download(url: str, expected: str) -> bytes:
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            break
        except OSError as e:  # GitHub's release assets answer 5xx for minutes at a time
            if attempt == 4:
                sys.exit(f"fetch-wakeword: {url}: {e}")
    if sha(data) != expected:
        sys.exit(f"fetch-wakeword: {url} is not the pinned file (sha256 {sha(data)})")
    return data


def main() -> None:
    if not (AAR.exists() and sha(AAR.read_bytes()) == AAR_SHA):
        AAR.parent.mkdir(parents=True, exist_ok=True)
        AAR.write_bytes(download(AAR_URL, AAR_SHA))
        print(f"fetch-wakeword: {AAR.relative_to(ROOT)}")
    wanted = {ASSETS / name: digest for name, digest in FILES.values()}
    version = ASSETS / "version"
    if all(p.exists() and sha(p.read_bytes()) == d for p, d in wanted.items()):
        version.write_text(MODEL_VERSION + "\n")
        print("fetch-wakeword: engine and model are in place")
        return
    archive = tarfile.open(fileobj=io.BytesIO(download(MODEL_URL, MODEL_SHA)), mode="r:bz2")
    ASSETS.mkdir(parents=True, exist_ok=True)
    for source, (name, digest) in FILES.items():
        data = archive.extractfile(f"{MODEL}/{source}").read()
        if sha(data) != digest:
            sys.exit(f"fetch-wakeword: {source} in the archive is not the pinned file")
        (ASSETS / name).write_bytes(data)
    version.write_text(MODEL_VERSION + "\n")
    print(f"fetch-wakeword: {ASSETS.relative_to(ROOT)} ({len(FILES)} files)")


if __name__ == "__main__":
    main()
