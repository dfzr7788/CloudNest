"""Two isolated client directories against a real, disposable CloudNest HTTP server.

Requires the packaged server JAR. Never points at an existing deployment or user directory.
"""
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "clients/windows-sync"))
from sync_engine import Engine, component, read_status
from transport import Api, SyncError


class SyncClients(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="cloudnest-sync-http-")
        cls.home = Path(cls.temp.name)
        cls.log = (cls.home / "server.log").open("wb")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        cls.server = f"http://127.0.0.1:{port}"
        jar = ROOT / "cloudnest-server/target/cloudnest-server.jar"
        if not jar.is_file():
            raise RuntimeError("Package the server with mvn verify before running this test")
        allowed = {"PATH", "JAVA_HOME", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR",
                   "HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "LANG", "LC_ALL",
                   "PATHEXT", "COMSPEC", "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS", "SYSTEMDRIVE"}
        environment = {key: value for key, value in os.environ.items() if key.upper() in allowed}
        cls.process = subprocess.Popen([
            shutil.which("java") or "java", "-jar", str(jar),
            "--server.address=127.0.0.1", f"--server.port={port}",
            "--spring.profiles.active=connected-dev", "--spring.datasource.url=jdbc:h2:mem:sync_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            f"--cloudnest.data-dir={cls.home / 'data'}", "--cloudnest.worker.enabled=false",
            "--cloudnest.index-enabled=false", "--cloudnest.node.disk-reserve-bytes=0",
            "--cloudnest.storage=local", "--cloudnest.ai.enabled=false", "--cloudnest.redis-enabled=false",
            "--management.health.redis.enabled=false", "--cloudnest.secure-cookies=false",
            "--cloudnest.registration-open=true", "--cloudnest.quota=10000000",
            "--cloudnest.part-size=1024"], stdout=cls.log, stderr=subprocess.STDOUT, env=environment,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        for _ in range(240):
            if cls.process.poll() is not None:
                cls.log.flush()
                raise RuntimeError("Disposable server exited: " + (cls.home / "server.log").read_text(errors="replace")[-8000:])
            if f"Tomcat started on port {port} " not in (cls.home / "server.log").read_text(errors="replace"):
                time.sleep(.25)
                continue
            try:
                with urllib.request.urlopen(cls.server + "/actuator/health", timeout=1) as response:
                    if response.status == 200:
                        return
            except OSError:
                time.sleep(.25)
        cls.tearDownClass()
        raise RuntimeError("Disposable server did not become healthy")

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate()
        try:
            cls.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            cls.process.kill()
            cls.process.wait(timeout=5)
        cls.log.close()
        cls.temp.cleanup()

    def setUp(self):
        name = uuid.uuid4().hex[:12]
        self.directory = self.home / name
        self.directory.mkdir()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        def account(method, path, body=None):
            headers = {"Content-Type": "application/json"}
            if hasattr(self, "csrf"):
                headers["X-XSRF-TOKEN"] = self.csrf
            request = urllib.request.Request(self.server + "/api" + path,
                data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
            with opener.open(request, timeout=10) as response:
                return json.load(response)
        self.csrf = account("GET", "/auth/csrf")["token"]
        account("POST", "/auth/register", {"username": "sync" + name, "password": "fixture-password-123"})
        self.csrf = account("GET", "/auth/csrf")["token"]
        self.a_api = Api(self.server, account("POST", "/sync/devices", {"name": "A"})["token"])
        self.b_api = Api(self.server, account("POST", "/sync/devices", {"name": "B"})["token"])
        self.a = Engine(self.directory / "A", self.a_api)
        self.b = Engine(self.directory / "B", self.b_api)
        self.addCleanup(self.a.close)
        self.addCleanup(self.b.close)

    def write(self, engine, name, value):
        path = engine.safe(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)

    def files(self, engine):
        return {p: engine.safe(p).read_bytes() for p, value in engine.scan().items() if value["kind"] == "FILE"}

    def test_initial_upload_download_edit_and_rename_keep_ids(self):
        self.write(self.a, "资料/hello.txt", "初始内容".encode())
        self.a.once()
        self.b.once()
        self.assertEqual(self.files(self.a), self.files(self.b))
        original = {e["name"]: eid for eid, e in self.a.table("remote").items()}
        os.rename(self.a.safe("资料"), self.a.safe("moved"))
        self.write(self.a, "moved/hello.txt", b"edited while moved")
        self.a.once()
        self.b.once()
        self.assertEqual(b"edited while moved", self.b.safe("moved/hello.txt").read_bytes())
        self.assertEqual(original["hello.txt"], next(eid for eid, e in self.a.table("remote").items() if e["name"] == "hello.txt"))
        self.assertEqual(original["资料"], next(eid for eid, e in self.a.table("remote").items() if e["name"] == "moved"))
        self.write(self.a, "outside.txt", b"not selected")
        self.a.once()
        selected = Engine(self.directory / "selected", self.b_api)
        try:
            selected.refresh()
            selected.configure_scope(["moved"])
            selected.once()
            self.assertEqual({"moved/hello.txt": b"edited while moved"}, self.files(selected))
            self.write(selected, "unselected.txt", b"local only")
            selected.once()
            self.assertNotIn("unselected.txt", {entry["name"] for entry in selected.table("remote").values()})
            os.rename(selected.safe("moved"), selected.safe("selected-renamed"))
            selected.once()
            self.a.once()
            self.assertEqual(b"edited while moved", self.a.safe("selected-renamed/hello.txt").read_bytes())
            self.assertTrue(selected.safe("unselected.txt").exists())
            self.assertEqual(2, read_status(selected.root)["trackedEntries"])
        finally:
            selected.close()

    def test_two_offline_edits_create_visible_conflict_copy(self):
        self.write(self.a, "file.txt", b"base")
        self.a.once(); self.b.once()
        self.write(self.a, "file.txt", b"cloud edit")
        self.write(self.b, "file.txt", b"offline B edit")
        self.a.once(); self.b.once(); self.a.once()
        self.assertEqual({b"cloud edit", b"offline B edit"}, set(self.files(self.a).values()))
        self.assertEqual(self.files(self.a), self.files(self.b))
        self.assertTrue(self.b.status()["conflicts"])

    def test_cloud_delete_preserves_unsynced_local_content(self):
        self.write(self.a, "file.txt", b"base")
        self.a.once(); self.b.once()
        self.write(self.b, "file.txt", b"only local copy")
        self.a.safe("file.txt").unlink()
        self.a.once(); self.b.once(); self.a.once()
        self.assertEqual({b"only local copy"}, set(self.files(self.a).values()))
        self.assertEqual(self.files(self.a), self.files(self.b))

    def test_local_delete_conflicting_with_cloud_change_restores_cloud_copy(self):
        self.write(self.a, "file.txt", b"base")
        self.a.once(); self.b.once()
        self.b.safe("file.txt").unlink()
        self.write(self.a, "file.txt", b"new cloud content")
        self.a.once(); self.b.once()
        self.assertEqual(b"new cloud content", self.b.safe("file.txt").read_bytes())
        self.assertTrue(self.b.status()["conflicts"])

    def test_invalid_cursor_bootstrap_retains_offline_baselines(self):
        self.write(self.a, "file.txt", b"base")
        self.a.once(); self.b.once()
        self.write(self.a, "file.txt", b"cloud")
        self.write(self.b, "file.txt", b"local")
        self.a.once()
        self.b.setting("cursor", "expired:0")
        self.b.once()
        self.assertEqual({b"cloud", b"local"}, set(self.files(self.b).values()))

    def test_lost_upload_init_response_and_client_restart_do_not_duplicate(self):
        self.write(self.a, "large.bin", b"abc" * 2000)
        real = self.a_api
        class LostResponse:
            def request(_, method, path, body=None, binary=False):
                value = real.request(method, path, body, binary)
                if method == "POST" and path == "/sync/client/operations":
                    raise ConnectionError("fixture response lost after server committed")
                return value
        self.a.api = LostResponse()
        with self.assertRaises(ConnectionError):
            self.a.once()
        root = self.a.root
        self.a.close()
        self.a = Engine(root, real)
        self.addCleanup(self.a.close)
        self.a.once(); self.b.once()
        self.assertEqual({"large.bin": b"abc" * 2000}, self.files(self.b))
        self.assertEqual(0, self.a.status()["pendingOperations"])

    def test_device_revocation_stops_client_before_mutations(self):
        self.a.once()
        self.a_api.request("POST", "/sync/client/logout")
        self.write(self.a, "private.txt", b"must not upload")
        with self.assertRaises(SyncError) as raised:
            self.a.once()
        self.assertEqual(401, raised.exception.status)
        self.b.once()
        self.assertFalse(self.files(self.b))

    def test_clean_delete_retains_local_trash_and_cloud_restore_is_downloaded(self):
        self.write(self.a, "restore.txt", b"restorable")
        self.a.once(); self.b.once()
        entry = next(iter(self.a.table("remote").values()))
        self.a.safe("restore.txt").unlink()
        self.a.once(); self.b.once()
        self.assertFalse(self.files(self.b))
        self.assertEqual([b"restorable"], [p.read_bytes() for p in (self.b.root / ".cloudnest-trash").iterdir()])
        self.a_api.request("POST", "/sync/client/operations", {
            "operationId": str(uuid.uuid4()), "createdAt": int(time.time()*1000), "kind": "RESTORE",
            "entryId": entry["id"], "expectedRevision": entry["revision"]})
        self.b.once()
        self.assertEqual(b"restorable", self.b.safe("restore.txt").read_bytes())

    def test_10k_local_files_are_scanned_and_limit_fails_before_mutation(self):
        # Local scanner scale check; does not substitute for 10k-file dual-PC transfer acceptance.
        for index in range(10000):
            self.a.safe(f"f{index:05}.txt").write_bytes(b"x")
        self.assertEqual(10000, len(self.a.scan()))
        self.a.safe("one-too-many.txt").write_bytes(b"retain")
        with self.assertRaises(ValueError):
            self.a.once()
        self.assertEqual(b"retain", self.a.safe("one-too-many.txt").read_bytes())

    def test_windows_namespace_hazards_and_process_lock_fail_closed(self):
        for name in ("../escape", "CON", "nul.txt", "a:b", "x.", "x ", ".cloudnest-sync", "LPT¹.txt"):
            with self.assertRaises(ValueError, msg=name):
                component(name)
        with self.assertRaises(RuntimeError):
            Engine(self.a.root, self.a_api)
        self.a.root_id = str(uuid.uuid4())
        entries = {str(uuid.uuid4()): {"name": name, "kind": "FOLDER", "parentId": self.a.root_id} for name in ("A", "a")}
        with self.assertRaises(ValueError):
            self.a.paths(entries)


class LocalPrototypeTest(unittest.TestCase):
    def test_download_commit_never_overwrites_a_concurrently_created_file(self):
        from unittest.mock import patch
        from sync_engine import publish_no_replace
        with tempfile.TemporaryDirectory(prefix="cloudnest-sync-atomic-") as folder:
            engine=Engine(folder,None)
            try:
                engine.safe("file.txt").write_bytes(b"old local bytes")
                class Download:
                    def download(_,entry,path):path.write_bytes(b"verified cloud bytes")
                engine.api=Download()
                def race(source,target):
                    target.write_bytes(b"concurrent editor bytes")
                    publish_no_replace(source,target)
                with patch("sync_engine.publish_no_replace",side_effect=race):
                    with self.assertRaises(FileExistsError):
                        engine.install({},"file.txt")
                self.assertEqual(b"concurrent editor bytes",engine.safe("file.txt").read_bytes())
                self.assertEqual([b"old local bytes"],[p.read_bytes() for p in (engine.root/".cloudnest-trash").iterdir()])
                from sync_engine import acknowledge
                records=read_status(engine.root)["conflicts"]
                acknowledge(engine.root,[record["id"] for record in records])
                self.assertEqual([],read_status(engine.root)["conflicts"])
                self.assertEqual(b"concurrent editor bytes",engine.safe("file.txt").read_bytes())
            finally:engine.close()

    @unittest.skipUnless(os.name == "nt", "Native Tk preview is validated on Windows")
    def test_desktop_constructs_and_language_change_keeps_folder_input(self):
        from desktop import Desktop
        window = Desktop()
        try:
            window.root.withdraw()
            window.folder.set("D:/fixture-not-accessed")
            window.language.set("English")
            window.translate()
            window.root.update_idletasks()
            self.assertIn("Development preview", window.root.title())
            self.assertEqual("D:/fixture-not-accessed", window.folder.get())
        finally:
            window.root.destroy()

    @unittest.skipUnless(os.name == "nt", "Windows Credential Manager is only available on Windows")
    def test_own_fixture_credential_roundtrip_and_delete(self):
        from credentials import WindowsCredentials
        store = WindowsCredentials()
        target = "CloudNestSync/test-only/" + uuid.uuid4().hex
        token = "generated-fixture-" + uuid.uuid4().hex
        try:
            store.write(target, token)
            self.assertEqual(token, store.read(target))
        finally:
            store.delete(target)
        with self.assertRaises(RuntimeError):
            store.read(target)


if __name__ == "__main__":
    unittest.main()
