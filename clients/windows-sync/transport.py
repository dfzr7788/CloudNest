"""Scoped sync HTTP transport. Redirects cannot forward credentials to another origin."""
import hashlib
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request


class SyncError(Exception):
    def __init__(self, status, code):
        super().__init__(f"HTTP {status}: {code}")
        self.status, self.code = status, code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def normalize_server(value, allow_local_http=False):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.username or parsed.password or parsed.query or parsed.fragment or
            parsed.path not in ("", "/") or not parsed.hostname):
        raise ValueError("Use the CloudNest origin without credentials, path or query")
    if parsed.scheme != "https" and not (allow_local_http and parsed.scheme == "http" and
                                         parsed.hostname in ("localhost", "127.0.0.1", "::1")):
        raise ValueError("HTTPS is required (local HTTP fixtures need --allow-local-http)")
    return value.rstrip("/")


class Api:
    def __init__(self, server, token):
        self.server, self.token, self.csrf = server, token, None
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(
            http.cookiejar.CookieJar()))

    def request(self, method, path, body=None, binary=False):
        if not path.startswith("/sync/client/") and path != "/auth/csrf":
            raise ValueError("Outside sync credential scope")
        if method not in ("GET", "HEAD") and self.csrf is None:
            self.csrf = self.request("GET", "/auth/csrf")["token"]
        headers = {"Authorization": "SyncDevice " + self.token, "Accept": "application/json"}
        if self.csrf is not None:
            headers["X-XSRF-TOKEN"] = self.csrf
        data = body if binary else (json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
        if data is not None:
            headers["Content-Type"] = "application/octet-stream" if binary else "application/json"
        req = urllib.request.Request(self.server + "/api" + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=120)
        except urllib.error.HTTPError as e:
            # Do not echo provider/server bodies, tokens or arbitrary HTML into logs.
            try:
                code = json.loads(e.read(4096)).get("code", "SYNC_HTTP_ERROR")
                if not isinstance(code, str) or not code.replace("_", "").isalnum():
                    code = "SYNC_HTTP_ERROR"
            except (ValueError, AttributeError):
                code = "SYNC_HTTP_ERROR"
            if e.code == 403:
                self.csrf = None  # Next user/watch cycle may refresh; no hidden write replay.
            raise SyncError(e.code, code) from None
        if binary:
            return response
        with response:
            content = response.read(16 * 1024 * 1024 + 1)
            if len(content) > 16 * 1024 * 1024:
                raise ValueError("Sync response exceeds 16 MiB")
            return json.loads(content) if content else {}

    def download(self, entry, destination):
        digest, size = hashlib.sha256(), 0
        with self.request("GET", f"/sync/client/files/{entry['id']}/download", binary=True) as stream:
            if stream.status != 200:
                raise ValueError("Expected a complete authenticated download")
            with destination.open("xb") as out:
                while chunk := stream.read(65536):
                    size += len(chunk)
                    if size > entry["size"]:
                        raise ValueError("Cloud file changed during download")
                    digest.update(chunk)
                    out.write(chunk)
                out.flush()
                import os
                os.fsync(out.fileno())
        if size != entry["size"] or digest.hexdigest() != entry["sha256"]:
            raise ValueError("Cloud file checksum changed; refresh metadata before retry")
