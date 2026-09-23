"""Validate a compiled CS3 and produce a CloudStream/Nuvio repository bundle.

Only standard-library modules are used. The result is suitable for a GitHub release.
Run this after Gradle make; this script does not compile Kotlin or invent binaries.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile


def package(cs3: Path, repository: str, tag: str, output: Path):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("repository must be GitHub owner/repository")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", tag):
        raise ValueError("release tag must contain only letters, digits, dots, dashes or underscores")
    data = cs3.read_bytes()
    if len(data) > 10 * 1024 * 1024:
        raise ValueError("CS3 exceeds Nuvio's 10 MiB limit")
    with zipfile.ZipFile(cs3) as archive:
        dex = archive.read("classes.dex")
        if not dex.startswith(b"dex\n"):
            raise ValueError("classes.dex does not have a DEX header")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("pluginClassName") != "ua.nuvio.rezkadiagnostics.RezkaDiagnosticsPlugin":
            raise ValueError("Unexpected plugin entry point")
    output.mkdir(parents=True, exist_ok=True)
    filename = "RezkaDiagnostics.cs3"
    shutil.copyfile(cs3, output / filename)
    base = f"https://github.com/{repository}/releases/download/{tag}"
    plugins = [{
        "name": "Rezka Diagnostics", "internalName": "RezkaDiagnostics",
        "url": f"{base}/{filename}", "version": 6, "apiVersion": 1,
        "status": 3, "language": "uk", "authors": ["nuvio-uk-providers"],
        "description": "Нативний прототип для фільмів; перше доступне озвучення.",
        "tvTypes": ["Movie"], "repositoryUrl": f"https://github.com/{repository}",
        "fileSize": len(data), "fileHash": "sha256-" + hashlib.sha256(data).hexdigest(),
    }]
    repo = {
        "name": "Nuvio Rezka Native Diagnostics",
        "description": "Тестовий нативний плагін Rezka для Nuvio Android TV",
        "manifestVersion": 1, "pluginLists": [f"{base}/plugins.json"],
    }
    for name, value in [("repo.json", repo), ("plugins.json", plugins)]:
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("After publishing all three files to the specified release, install using:")
    print(f"{base}/repo.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cs3", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    package(args.cs3, args.repository, args.tag, args.output)
