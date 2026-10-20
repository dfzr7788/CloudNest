"""Exercise the source installer only in explicit disposable directories; never the user's app folder."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TEMP_BASE = (ROOT / ".tools/sync-installer-tests").resolve()


class PackageTest(unittest.TestCase):
    def setUp(self):
        TEMP_BASE.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=TEMP_BASE)
        self.root = Path(self.temp.name).resolve()
        assert self.root.is_relative_to(TEMP_BASE) and self.root != TEMP_BASE
        self.addCleanup(self.temp.cleanup)
        archive = self.root / "client.zip"
        subprocess.run([sys.executable, str(ROOT / "scripts/package-sync-client.py"),
                        "--output", str(archive), "--revision", "installer-fixture"],
                       check=True, capture_output=True, timeout=30)
        with zipfile.ZipFile(archive) as package:
            package.extractall(self.root)
        self.package = self.root / "cloudnest-windows-sync"

    def test_allowlisted_archive_is_complete_and_has_no_runtime_state(self):
        manifest = json.loads((self.package / "manifest.json").read_text(encoding="utf-8"))
        self.assertTrue(manifest["development"])
        self.assertEqual("installer-fixture", manifest["revision"])
        self.assertEqual(10, len(manifest["sha256"]))
        self.assertIn("mount.ps1", manifest["sha256"])
        self.assertIn("uninstall.ps1", manifest["sha256"])
        self.assertEqual(set(manifest["sha256"]) | {"manifest.json"},
                         {p.name for p in self.package.iterdir()})
        for name, digest in manifest["sha256"].items():
            self.assertEqual(digest, hashlib.sha256((self.package / name).read_bytes()).hexdigest())


@unittest.skipUnless(sys.platform == "win32", "Native PowerShell/Start-menu shortcut tests require Windows")
class WindowsInstallerTest(PackageTest):
    def setUp(self):
        super().setUp()
        self.app = self.root / "CloudNest 客户端 🌥"
        self.menu = self.root / "Programs"
        self.powershell = shutil.which("powershell.exe")
        if not self.powershell:
            self.skipTest("Windows PowerShell is unavailable")

    def invoke(self, script, *args, success=True):
        result = subprocess.run([self.powershell, "-NoProfile", "-NonInteractive",
                                 "-ExecutionPolicy", "Bypass", "-File", str(script), *map(str, args)],
                                capture_output=True, timeout=90,
                                env={**os.environ, "PYTHONUTF8": "1"})
        self.assertEqual(success, result.returncode == 0,
                         (result.stdout + result.stderr).decode("utf-8", errors="replace"))

    def install(self, success=True):
        self.invoke(self.package / "install.ps1", "-InstallDirectory", self.app,
                    "-ShortcutDirectory", self.menu, success=success)

    def uninstall(self, success=True):
        self.invoke(self.app / "uninstall.ps1", "-ShortcutDirectory", self.menu, success=success)

    def test_install_upgrade_and_uninstall_leave_sync_data(self):
        sync = self.root / "Synced files"
        sync.mkdir()
        (sync / "keep.txt").write_text("user data", encoding="utf-8")
        self.install()
        marker = json.loads((self.app / "install-state.json").read_text(encoding="utf-8-sig"))
        self.assertEqual("CloudNestSyncDevelopment", marker["app"])
        self.assertEqual(9, len(marker["files"]))
        self.assertTrue((self.app / "mount.ps1").is_file())
        self.assertTrue((self.menu / "CloudNest Sync Development.lnk").is_file())
        self.install()
        self.uninstall()
        self.assertFalse(self.app.exists())
        self.assertFalse((self.menu / "CloudNest Sync Development.lnk").exists())
        self.assertEqual("user data", (sync / "keep.txt").read_text(encoding="utf-8"))

    def test_modified_and_unknown_files_are_preserved(self):
        self.install()
        (self.app / "README.md").write_text("personal notes", encoding="utf-8")
        (self.app / "unknown.dat").write_bytes(b"preserve")
        runtime = self.app / ".cloudnest-sync"
        runtime.mkdir()
        (runtime / "format").write_text("preserve journal", encoding="utf-8")
        self.install(success=False)
        self.uninstall()
        self.assertEqual("personal notes", (self.app / "README.md").read_text(encoding="utf-8"))
        self.assertEqual(b"preserve", (self.app / "unknown.dat").read_bytes())
        self.assertEqual("preserve journal", (runtime / "format").read_text(encoding="utf-8"))
        self.assertTrue((self.app / "uninstall.ps1").is_file())
        self.assertTrue((self.app / "shortcut.ps1").is_file())
        self.assertTrue((self.app / "install-state.json").is_file())
        shutil.copyfile(self.package / "README.md", self.app / "README.md")
        self.uninstall()
        self.assertEqual(b"preserve", (self.app / "unknown.dat").read_bytes())
        self.assertFalse((self.app / "desktop.py").exists())

    def test_modified_shortcut_helper_is_not_executed_by_uninstaller(self):
        self.install()
        (self.app / "shortcut.ps1").write_text("throw 'Do not execute modified helpers'\n", encoding="utf-8")
        self.uninstall(success=False)
        self.assertTrue((self.app / "desktop.py").exists())

    def test_tampered_package_and_foreign_destination_fail_before_copying(self):
        source = self.package / "desktop.py"
        original = source.read_bytes()
        source.write_bytes(original + b"\n# changed\n")
        self.install(success=False)
        self.assertFalse(self.app.exists())
        source.write_bytes(original)
        self.app.mkdir()
        (self.app / "foreign.txt").write_text("keep", encoding="utf-8")
        self.install(success=False)
        self.assertEqual(["foreign.txt"], [p.name for p in self.app.iterdir()])

    def test_tampered_ownership_marker_cannot_delete_a_parent_file(self):
        self.install()
        outside = self.root / "keep.txt"
        outside.write_text("must remain", encoding="utf-8")
        state_path = self.app / "install-state.json"
        state = json.loads(state_path.read_text(encoding="utf-8-sig"))
        digest = state["files"].pop("README.md")
        state["files"]["../keep.txt"] = digest
        state_path.write_text(json.dumps(state), encoding="utf-8")
        self.uninstall(success=False)
        self.assertEqual("must remain", outside.read_text(encoding="utf-8"))
        self.assertTrue((self.app / "desktop.py").exists())

    def test_install_refuses_junctions_before_writing_into_their_target(self):
        target = self.root / "Actual files"
        target.mkdir()
        sentinel = target / "keep.txt"
        sentinel.write_text("preserve", encoding="utf-8")
        # Both paths are verified descendants of this disposable fixture root.
        assert target.resolve().is_relative_to(self.root)
        script = self.root / "junction.ps1"
        script.write_text("param($Link,$Target)\nNew-Item -ItemType Junction -Path $Link -Target $Target | Out-Null\n", encoding="utf-8")
        self.invoke(script, "-Link", self.app, "-Target", target)
        self.install(success=False)
        self.assertEqual(["keep.txt"], [p.name for p in target.iterdir()])
        self.assertEqual("preserve", sentinel.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
