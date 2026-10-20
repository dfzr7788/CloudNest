"""Scoped CloudNest contribution node. Serve only a dedicated authorized object directory."""
import argparse
import hmac
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import threading
import time
import urllib.request
import urllib.parse
import uuid

KEY = re.compile(r"/objects/([a-f0-9-]{36})\Z")


def linked(path):
    return path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction())


class Store:
    def __init__(self, directory, budget):
        source = Path(directory).absolute()
        if linked(source):
            raise ValueError("Contribution directory cannot be a link")
        self.root = source.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        marker = self.root / ".cloudnest-node.json"
        if not marker.exists():
            if any(self.root.iterdir()):
                raise ValueError("Select a new empty dedicated directory")
            marker.write_text(json.dumps({"format": 1}), encoding="utf-8")
        if linked(marker) or json.loads(marker.read_text(encoding="utf-8")) != {"format": 1}:
            raise ValueError("Invalid node directory marker")
        self.objects = self.root / "objects"
        if linked(self.objects):
            raise ValueError("Object directory cannot be a link")
        self.objects.mkdir(exist_ok=True)
        self.budget = budget
        self.lock = threading.Lock()
        # Crash leftovers have no published object name and can safely be reclaimed at startup.
        for path in self.objects.glob("*.part"):
            if linked(path):
                raise ValueError("Linked staging file")
            path.unlink()

    def path(self, request_path):
        match = KEY.fullmatch(request_path)
        if not match or str(uuid.UUID(match[1])) != match[1]:
            raise ValueError("Invalid object key")
        path = self.objects / match[1]
        if linked(self.root) or linked(self.objects) or linked(path):
            raise ValueError("Linked paths are forbidden")
        if path.resolve().parent != self.objects.resolve():
            raise ValueError("Object escaped directory")
        return path

    def used(self):
        total = 0
        for p in self.objects.iterdir():
            if linked(p):
                raise ValueError("Linked object")
            if p.is_file():
                total += p.stat().st_size
        return total


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CloudNestNode/1"

    def log_message(self, *_):
        pass  # Never log bearer tokens or object URLs.

    def reply(self, status, size=0, extra=None):
        self.send_response(status)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()

    def authenticated(self):
        self.connection.settimeout(30)
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied, "Bearer " + self.server.token):
            self.close_connection = True
            self.reply(401)
            return False
        return True

    def object(self):
        try:
            return self.server.store.path(self.path)
        except (ValueError, OSError):
            self.reply(400)
            return None

    def do_GET(self):
        if not self.authenticated():
            return
        if self.path == "/health":
            self.reply(200)
            return
        self.read_object(False)

    def do_HEAD(self):
        if self.authenticated():
            self.read_object(True)

    def read_object(self, head):
        path = self.object()
        if path is None:
            return
        try:
            # Open while holding the same lock as PUT/DELETE; in-flight reads own their descriptor.
            with self.server.store.lock:
                stream = path.open("rb")
                size = os.fstat(stream.fileno()).st_size
        except FileNotFoundError:
            self.reply(404)
            return
        with stream:
            start, end, status = 0, size - 1, 200
            headers = {"Accept-Ranges": "bytes", "Content-Type": "application/octet-stream"}
            value = self.headers.get("Range")
            if value:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", value)
                if not match:
                    self.reply(416, extra={"Content-Range": f"bytes */{size}"})
                    return
                start = int(match[1])
                end = min(int(match[2]) if match[2] else size - 1, size - 1)
                if start > end or start >= size:
                    self.reply(416, extra={"Content-Range": f"bytes */{size}"})
                    return
                headers["Content-Range"] = f"bytes {start}-{end}/{size}"
                status = 206
            length = max(0, end - start + 1)
            self.reply(status, length, headers)
            if not head:
                stream.seek(start)
                while length:
                    block = stream.read(min(65536, length))
                    if not block:
                        break
                    self.wfile.write(block)
                    length -= len(block)

    def do_PUT(self):
        if not self.authenticated():
            return
        path = self.object()
        if path is None:
            self.close_connection = True
            return
        value = self.headers.get("Content-Length", "")
        if not value.isdigit() or self.headers.get("Transfer-Encoding"):
            self.close_connection = True
            self.reply(411)
            return
        size = int(value)
        store = self.server.store
        with store.lock:
            old = path.stat().st_size if path.exists() else 0
            if size > store.budget - store.used() + old or size + 16777216 > shutil.disk_usage(store.root).free:
                self.close_connection = True
                self.reply(507)
                return
            staging = store.objects / (str(uuid.uuid4()) + ".part")
            try:
                remaining = size
                with staging.open("xb") as output:
                    while remaining:
                        block = self.rfile.read(min(65536, remaining))
                        if not block:
                            raise OSError("Truncated upload")
                        output.write(block)
                        remaining -= len(block)
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(staging, path)
                self.reply(201)
            except OSError:
                self.close_connection = True
                self.reply(500)
            finally:
                staging.unlink(missing_ok=True)

    def do_DELETE(self):
        if not self.authenticated():
            return
        path = self.object()
        if path is not None:
            with self.server.store.lock:
                path.unlink(missing_ok=True)
            self.reply(204)


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    # Bound connections as well as writes; avoid unbounded per-connection memory.
    slots = threading.BoundedSemaphore(8)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


def heartbeat(origin, node_id, token_file, stopped):
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme != "https" or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("Control center requires an HTTPS origin")
    while not stopped.is_set():
        token = Path(token_file).read_text(encoding="utf-8").strip()
        request = urllib.request.Request(origin.rstrip("/") + "/api/public/nodes/" + str(uuid.UUID(node_id)) + "/heartbeat", data=b"", headers={"X-Node-Token": token}, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                response.read(4096)
        except OSError:
            pass  # Control center expires admission after 120 seconds; data remains on disk.
        stopped.wait(30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--budget", type=int, required=True, help="Authorized maximum bytes")
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--initialize-token", action="store_true")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--cert")
    parser.add_argument("--key")
    parser.add_argument("--control-center")
    parser.add_argument("--node-id")
    parser.add_argument("--heartbeat-token-file")
    args = parser.parse_args()
    if args.budget < 1048576 or bool(args.cert) != bool(args.key):
        parser.error("Budget must be at least 1 MiB; TLS needs both certificate and key")
    token_path = Path(args.token_file)
    if args.initialize_token:
        with token_path.open("x", encoding="utf-8") as output:
            output.write(secrets.token_urlsafe(32))
        if os.name != "nt":
            token_path.chmod(0o600)
    token = token_path.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
        parser.error("Token file needs a 32–128 character random URL-safe token")
    server = Server((args.bind, args.port), Handler)
    server.store = Store(args.directory, args.budget)
    server.token = token
    if args.cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    stopped = threading.Event()
    if args.control_center:
        if not args.node_id or not args.heartbeat_token_file:
            parser.error("Heartbeat requires node id and heartbeat token file")
        threading.Thread(target=heartbeat, args=(args.control_center, args.node_id, args.heartbeat_token_file, stopped), daemon=True).start()
    print("CloudNest scoped storage node started. Stop with Ctrl+C; stored data is retained.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stopped.set()
        server.server_close()


if __name__ == "__main__":
    main()
