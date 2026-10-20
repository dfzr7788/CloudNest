"""Build an allowlisted source installer archive; never include runtime state or credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FILES = ["README.md", "cloudnest_sync.py", "credentials.py", "desktop.py",
         "install.ps1", "uninstall.ps1", "sync_engine.py", "transport.py"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    payload = {name: (ROOT / "clients/windows-sync" / name).read_bytes() for name in FILES}
    manifest = {"schema": 1, "development": True, "revision": args.revision,
                "python": "3.11+ with Tcl/Tk", "platform": "Windows",
                "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in payload.items()}}
    payload["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payload.items()):
            entry = zipfile.ZipInfo("cloudnest-windows-sync/" + name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    with zipfile.ZipFile(args.output) as archive:
        assert archive.testzip() is None
        for name, digest in manifest["sha256"].items():
            assert hashlib.sha256(archive.read("cloudnest-windows-sync/" + name)).hexdigest() == digest
    print(f"Packaged {len(FILES)} allowlisted files: {args.output}")


if __name__ == "__main__":
    main()
