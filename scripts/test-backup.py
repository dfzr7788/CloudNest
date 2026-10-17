import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import backup


class Tests(unittest.TestCase):
    def fixture(self,root,path='storage/objects/example',kind=tarfile.REGTYPE):
        folder=Path(root)
        for name in ('.env','database.sql','docker-compose.yml','revisions.json'):
            (folder/name).write_text('fixture')
        with tarfile.open(folder/'app-data.tar.gz','w:gz') as archive:
            item=tarfile.TarInfo(path);item.type=kind
            if kind==tarfile.REGTYPE:item.size=4;archive.addfile(item,io.BytesIO(b'data'))
            else:item.linkname='/etc/passwd';archive.addfile(item)
        self.manifest(folder)
        return folder
    def manifest(self,folder):
        (folder/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(folder.iterdir()) if p.name!='SHA256SUMS'))
    def test_valid_snapshot(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertTrue(backup.verify(self.fixture(root))['verified'])
    def test_corrupt_payload(self):
        with tempfile.TemporaryDirectory() as root:
            folder=self.fixture(root);(folder/'database.sql').write_text('corrupt')
            with self.assertRaisesRegex(RuntimeError,'checksum'):backup.verify(folder)
    def test_archive_traversal_and_links(self):
        for path,kind in [('../escape',tarfile.REGTYPE),('/absolute',tarfile.REGTYPE),('link',tarfile.SYMTYPE),('device',tarfile.CHRTYPE)]:
            with tempfile.TemporaryDirectory() as root:
                folder=self.fixture(root,path,kind)
                with self.assertRaisesRegex(RuntimeError,'Unsafe archive'):backup.verify(folder)
    def test_manifest_escape(self):
        with tempfile.TemporaryDirectory() as root:
            folder=self.fixture(root);(folder/'SHA256SUMS').write_text('0'*64+'  ../escape\n')
            with self.assertRaisesRegex(RuntimeError,'Unsafe backup'):backup.verify(folder)
    def test_unlisted_file_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            folder=self.fixture(root);(folder/'unexpected').write_text('x')
            with self.assertRaisesRegex(RuntimeError,'unverified'):backup.verify(folder)
    def test_restore_never_overwrites_existing_target(self):
        with tempfile.TemporaryDirectory() as root:
            folder=self.fixture(root)
            with self.assertRaisesRegex(RuntimeError,'must not exist'):
                backup.restore(folder,folder,'cloudnest-restore-test',18088,True)


if __name__=='__main__':unittest.main()
