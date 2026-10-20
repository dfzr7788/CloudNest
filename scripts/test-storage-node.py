"""Isolated contract checks for the scoped contribution node; no existing data is used."""
import importlib.util
import http.client
import io
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("storage_node", ROOT / "clients/storage-node/node.py")
node = importlib.util.module_from_spec(spec)
spec.loader.exec_module(node)


class NodeContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cloudnest-node-test-")
        self.store = node.Store(Path(self.temp.name) / "contributed", 1024 * 1024)
        self.server = node.Server(("127.0.0.1", 0), node.Handler)
        self.server.store = self.store
        self.server.token = "x" * 43
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = "http://127.0.0.1:" + str(self.server.server_port)
        self.key = "/objects/" + str(uuid.uuid4())

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.temp.cleanup()

    def call(self, method, path, data=None, token=None, headers=None):
        request = urllib.request.Request(self.origin + path, method=method, data=data, headers={
            "Authorization": "Bearer " + (token or self.server.token), **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers

    def test_authentication_ranges_replacement_and_deletion(self):
        self.assertEqual(401, self.call("GET", "/health", token="wrong")[0])
        self.assertEqual(201, self.call("PUT", self.key, b"abcdef")[0])
        self.assertEqual(b"abcdef", self.call("GET", self.key)[1])
        status, body, headers = self.call("GET", self.key, headers={"Range": "bytes=1-3"})
        self.assertEqual((206, b"bcd"), (status, body))
        self.assertEqual("bytes 1-3/6", headers["Content-Range"])
        self.assertEqual(416, self.call("GET", self.key, headers={"Range": "bytes=10-"})[0])
        self.assertEqual(200, self.call("HEAD", self.key)[0])
        self.assertEqual(201, self.call("PUT", self.key, b"")[0])
        self.assertEqual(b"", self.call("GET", self.key)[1])
        self.assertEqual(204, self.call("DELETE", self.key)[0])
        self.assertEqual(404, self.call("GET", self.key)[0])
        self.assertEqual(204, self.call("DELETE", self.key)[0])

    def test_budget_and_directory_boundary(self):
        # A real client may still be sending a rejected body when Windows closes the socket.
        # Test admission using headers alone, before spending bandwidth on an oversized body.
        client = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        client.putrequest("PUT", self.key)
        client.putheader("Authorization", "Bearer " + self.server.token)
        client.putheader("Content-Length", str(1024 * 1024 + 1))
        client.endheaders()
        self.assertEqual(507, client.getresponse().status)
        client.close()
        self.assertEqual(404, self.call("GET", self.key)[0])
        for path in ("/../private.txt", "/objects/../../private", "/objects/%2e%2e/private", "/objects/" + "a" * 36, "/.cloudnest-node.json"):
            self.assertEqual(400, self.call("GET", path)[0], path)
        outsider = Path(self.temp.name) / "private.txt"
        outsider.write_bytes(b"private")
        path = self.store.objects / self.key.rsplit("/", 1)[1]
        try:
            path.symlink_to(outsider)
        except OSError:
            return  # Windows without symlink privilege still runs all HTTP boundary checks.
        self.assertEqual(400, self.call("GET", self.key)[0])
        self.assertEqual(b"private", outsider.read_bytes())

    def test_reject_nonempty_arbitrary_directory(self):
        selected = Path(self.temp.name) / "personal"
        selected.mkdir()
        (selected / "personal.txt").write_bytes(b"retain")
        with self.assertRaises(ValueError):
            node.Store(selected, 1024 * 1024)
        self.assertEqual(b"retain", (selected / "personal.txt").read_bytes())


if __name__ == "__main__":
    unittest.main()
