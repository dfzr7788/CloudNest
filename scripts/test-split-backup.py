"""Local failure-path tests. SSH/restic/Docker are simulated, not a recovery rehearsal."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import backup
import split_backup

spec = importlib.util.spec_from_file_location('b_backup', Path(__file__).with_name('b-backup.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


class Tests(unittest.TestCase):
    def test_b_stops_before_snapshot_and_restarts_on_backup_failure(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            if args[:2] == ['restic', 'backup']:
                raise subprocess.CalledProcessError(1, args)
            return SimpleNamespace()
        with patch.object(b, 'config_environment', return_value={}):
            with self.assertRaises(subprocess.CalledProcessError):
                b.create('a'*32, run)
        self.assertEqual(['stop','garage'], calls[0][-2:])
        self.assertEqual(['start','garage'], calls[-1][-2:])

    def test_b_ready_requires_snapshot_check_and_restart(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            return SimpleNamespace(stdout=json.dumps({'message_type':'summary','snapshot_id':'b'*64}))
        with patch.object(b,'config_environment',return_value={}):
            result=b.create('a'*32,run)
        self.assertEqual('READY',result['status'])
        self.assertIn(['restic','check'],calls)
        self.assertFalse(result['fullRestoreVerified'])

    def test_b_invalid_batch_never_executes(self):
        with self.assertRaisesRegex(RuntimeError,'Invalid batch'):
            b.create('a; dangerous')

    def test_a_requires_matching_b_batch_and_does_not_export_remote_bytes(self):
        class Worker:
            calls=[]
            def compose(self,*args,**kwargs):
                self.calls.append(args)
                return json.dumps({'objects':[],'policies':[]})
        with tempfile.TemporaryDirectory() as directory:
            worker=Worker()
            with patch.dict(os.environ,{'CLOUDNEST_B_BACKUP_HOST':'backup-host','RESTIC_REPOSITORY':'fixture','CLOUDNEST_B_INTERNAL_ENDPOINT':'http://b.internal:3900'}), patch.object(
                    split_backup.subprocess,'run',return_value=SimpleNamespace(stdout=json.dumps(
                        {'batch':'c'*32,'snapshot':'b'*64,'status':'READY'}))):
                with self.assertRaisesRegex(RuntimeError,'matching checkpoint'):
                    split_backup.checkpoint(worker,Path(directory))
            self.assertEqual(2,len(worker.calls))
            self.assertFalse(any('export-objects' in str(call) for call in worker.calls))

    def test_uncovered_backend_prevents_false_complete_checkpoint(self):
        manifest={'objects':[], 'backends':['local','s3:abcdef123456'],
                  'policies':[{'id':'abcdef123456','endpoint':'http://other.internal:3900'}]}
        with self.assertRaisesRegex(RuntimeError,'outside B'):
            split_backup.validate_coverage(manifest,'http://b.internal:3900')

    def fixture(self,folder):
        batch={'format':1,'mode':'split','batch':'a'*32,
               'b':{'batch':'a'*32,'snapshot':'b'*64,'status':'READY'}}
        manifest={'objects':[],'policies':[{'endpoint':'http://production.internal:3900'}]}
        (folder/'batch.json').write_text(json.dumps(batch))
        (folder/'object-manifest.json').write_text(json.dumps(manifest))
        return batch,manifest

    def test_checkpoint_metadata_and_restore_isolation(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            batch,_=self.fixture(folder)
            self.assertEqual('split',split_backup.validate_split(folder)[0]['mode'])
            for endpoint in (None,'http://production.internal:3901','http://other\nINJECT=yes'):
                with patch.object(backup,'verify',return_value={'verified':True}):
                    with self.assertRaises(RuntimeError):
                        backup.restore(folder,folder/'new','cloudnest-restore-test',18088,False,endpoint)
            batch['b']['batch']='c'*32
            (folder/'batch.json').write_text(json.dumps(batch))
            with self.assertRaisesRegex(RuntimeError,'Invalid split'):
                split_backup.validate_split(folder)


if __name__=='__main__':unittest.main()
