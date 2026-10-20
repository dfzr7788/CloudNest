"""CloudNest Windows sync development CLI (Python 3.11+, standard library only)."""
import argparse
import getpass
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.error

from credentials import WindowsCredentials
from sync_engine import Engine, read_status, acknowledge
from transport import Api, SyncError, normalize_server


def target(root, server):
    return "CloudNestSync/" + hashlib.sha256((str(root).casefold() + "\n" + server).encode()).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["link", "once", "watch", "status", "acknowledge", "logout"])
    parser.add_argument("--folder", required=True, type=Path)
    parser.add_argument("--server", help="CloudNest HTTPS origin, required for link")
    parser.add_argument("--interval", type=int, default=30)
    parser.add_argument("--allow-local-http", action="store_true", help="Local fixture servers only")
    parser.add_argument("--include", action="append", help="Existing top-level cloud folder name; repeat for selective transfer. Choose at first link.")
    parser.add_argument("--record", action="append", help="Displayed conflict record ID to mark reviewed; repeat, up to 100. Files are retained.")
    args = parser.parse_args(argv)
    if args.command == "acknowledge":
        acknowledge(args.folder, args.record or [])
        print("Selected records marked reviewed. Files retained.")
        return 0
    if args.command == "status":
        print(json.dumps(read_status(args.folder), ensure_ascii=False, indent=2))
        return 0
    engine = Engine(args.folder, None)
    try:
        credentials = WindowsCredentials()
        server = engine.setting("server")
        if args.command == "link":
            if not args.server:
                parser.error("--server is required for link")
            selected = normalize_server(args.server, args.allow_local_http)
            if server and server != selected:
                raise ValueError("Folder already belongs to another server; use a separate directory")
            token = getpass.getpass("Device token (from CloudNest settings; input hidden): ").strip()
            engine.api = Api(selected, token)
            engine.refresh()  # Validate account identity before replacing any stored credential.
            if args.include is not None or engine.setting("scope") is None:
                engine.configure_scope(args.include or [])
            credentials.write(target(engine.root, selected), token)
            engine.setting("server", selected)
            print("Linked. No user files transferred. Run once with independent test data first.")
            return 0
        if not server:
            raise ValueError("Link this folder before running sync")
        normalize_server(server, args.allow_local_http)
        credential_target = target(engine.root, server)
        engine.api = Api(server, credentials.read(credential_target))
        if args.command == "logout":
            engine.api.request("POST", "/sync/client/logout")
            credentials.delete(credential_target)
            print("Device revoked. Local files, baselines and conflict copies retained.")
            return 0
        if args.command == "once":
            engine.once()
            print(json.dumps(engine.status(), ensure_ascii=False, indent=2))
            return 0
        delay = max(10, args.interval)
        backoff = delay
        while True:
            try:
                engine.once()
                print(json.dumps(engine.status(), ensure_ascii=False))
                backoff = delay
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                print("Network unavailable; durable operations retained.", file=sys.stderr)
                backoff = min(300, backoff * 2)
            except SyncError as error:
                if error.status < 500 and error.status != 429:
                    raise
                print(str(error), file=sys.stderr)
                backoff = min(300, backoff * 2)
            time.sleep(backoff)
    finally:
        engine.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Stopped. Durable operations retained.", file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        print(f"Sync stopped: {error}", file=sys.stderr)
        sys.exit(1)
