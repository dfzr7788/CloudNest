"""Failure-path tests with a simulated command runner; no Docker or server access."""
import importlib.util
import json
import io
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('cloudnest_updater', Path(__file__).with_name('updater.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeUpdater(module.Updater):
    def prebuilt(self, revision):
        return False
    def __init__(self, directory, failure=None, dirty='', upstream=''):
        root = Path(directory)
        super().__init__(root, root / 'worker')
        self.state.mkdir(parents=True)
        self.requests.mkdir()
        (self.repo / '.env').write_text('PRIVATE_FIXTURE=yes')
        self.calls = []
        self.failure = failure
        self.dirty = dirty
        self.upstream = upstream
        self.runtime_image = 'sha256:fixture'
        self.addressable_image = 'sha256:fixture'
        self.platform_image = 'sha256:fixture'
        self.pinned_image = None

    def run(self, *args, **kwargs):
        self.calls.append(args)
        command = ' '.join(args)
        if self.failure and self.failure in command:
            raise RuntimeError('模拟执行失败')
        if command == 'git remote get-url origin': return module.ORIGIN
        if command == 'git branch --show-current': return 'main'
        if command == 'git rev-parse HEAD': return 'a' * 40
        if command == 'git rev-parse origin/main': return 'b' * 40
        if command == 'git diff --name-only HEAD': return self.dirty
        if command.startswith('git diff --name-only '): return self.upstream
        if command == 'docker compose config --format json':
            assert kwargs.get('log_output') is False
            return json.dumps({'services': {'server': {'environment': {'STORAGE_BACKEND': 'local'}}}})
        if command.startswith('docker compose images -q'): return 'sha256:fixture'
        if args[:4] == ('docker', 'compose', 'exec', '-T'): return '0'
        if command == 'docker compose ps -a -q server': return 'fixture-server-container'
        if command == 'docker compose ps -a -q web': return 'fixture-web-container'
        if args[:3] == ('docker', 'container', 'inspect'):
            service = 'server' if args[-1]=='fixture-server-container' else 'web'
            return json.dumps({'image':self.runtime_image,'reference':f'cloudnest-{service}:old','composeImage':self.runtime_image})
        if args[:3] == ('docker','image','inspect'):
            if '--platform' in args: return self.platform_image
            if '--format' in args and args[args.index('--format')+1]=='{{.Id}}': return self.pinned_image or self.addressable_image
            return json.dumps({'id':self.addressable_image,'os':'linux','architecture':'amd64'})
        if args[:2] == ('docker', 'inspect'):
            return json.dumps([{'Destination': '/app/data', 'Type': 'volume', 'Name': 'fixture-volume'}])
        if args[:3] == ('docker', 'compose', 'run'):
            backup = args[args.index('-v') + 1].removesuffix(':/backup')
            (Path(backup) / 'app-data.tar.gz').write_bytes(b'archive-fixture')
        return ''

    def backup(self, folder):
        self.publish('BACKUP', '模拟备份')
        self.stopped = True
        self.compose('stop', 'web', 'server')
        if self.failure == 'backup': raise RuntimeError('模拟备份失败')
        (folder / 'database.sql').write_text('fixture')
        (folder / 'app-data.tar.gz').write_bytes(b'fixture')


class Tests(unittest.TestCase):
    def test_worker_refresh_installs_missing_helper_even_if_main_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            (updater.repo/'scripts').mkdir()
            (updater.repo/'scripts/updater.py').write_text('main-fixture')
            (updater.repo/'scripts/split_backup.py').write_text('helper-fixture')
            installed=updater.base/'updater.py'
            installed.write_text('main-fixture')
            self.assertTrue(updater.refresh_worker_files(installed))
            self.assertEqual('helper-fixture',installed.with_name('split_backup.py').read_text())
            self.assertFalse(updater.refresh_worker_files(installed))

    def test_incomplete_worker_sources_do_not_replace_existing_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            (updater.repo/'scripts').mkdir()
            (updater.repo/'scripts/updater.py').write_text('new-main')
            installed=updater.base/'updater.py'
            installed.write_text('old-main')
            with self.assertRaisesRegex(RuntimeError,'不完整'):updater.refresh_worker_files(installed)
            self.assertEqual('old-main',installed.read_text())

    def test_unexpected_backup_exception_is_logged_without_exposing_details(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            updater.log=updater.base/'worker.log'
            def fail(folder):
                updater.stopped=True
                updater.backup_step('DATABASE','正在导出数据库')
                raise TypeError('private-fixture-detail')
            with patch.object(updater,'backup',side_effect=fail),patch.object(module.sys,'stderr',io.StringIO()) as journal:
                updater.perform('update')
            self.assertEqual('FAILED',updater.status['phase'])
            self.assertEqual('DATABASE',updater.status['backupStep'])
            self.assertIn('TypeError',updater.status['message'])
            self.assertNotIn('private-fixture-detail',json.dumps(updater.status))
            self.assertIn('Traceback',updater.log.read_text(encoding='utf-8'))
            self.assertIn('private-fixture-detail',journal.getvalue())
            self.assertIn(('docker','compose','start','server','web'),updater.calls)
            self.assertFalse(any('up' in command for command in updater.calls))

    def test_log_write_failure_still_recovers_original_containers(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory,failure='backup')
            updater.log=updater.base/'nonexistent'/'worker.log'
            with patch.object(module.sys,'stderr',io.StringIO()):updater.perform('update')
            self.assertEqual('FAILED',updater.status['phase'])
            self.assertIn(('docker','compose','start','server','web'),updater.calls)

    def test_missing_host_split_module_is_explicit_and_recovers(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            with patch.dict(module.os.environ,{'CLOUDNEST_BACKUP_MODE':'split'}),patch.dict('sys.modules',{'split_backup':None}),patch.object(module.sys,'stderr',io.StringIO()),patch.object(updater,'backup',side_effect=lambda folder:module.Updater.backup(updater,folder)):
                updater.perform('update')
            self.assertEqual('FAILED',updater.status['phase'])
            self.assertEqual('REMOTE_CHECKPOINT',updater.status['backupStep'])
            self.assertIn('缺少 split_backup.py',updater.status['message'])
            self.assertIn(('docker','compose','start','server','web'),updater.calls)

    def test_low_download_space_reports_actual_free_space_and_cleans_partial_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            payload=b'fixture-archive'
            checksum=hashlib.sha256(payload).hexdigest()
            responses=[io.BytesIO((checksum+'  cloudnest-images-linux-amd64.tar.gz\n').encode()),io.BytesIO(payload)]
            with patch.object(module.urllib.request,'urlopen',side_effect=responses),patch.object(module.shutil,'disk_usage',return_value=SimpleNamespace(free=int(2.9*1024**3))):
                with self.assertRaisesRegex(RuntimeError,'当前 2.90 GiB'):module.Updater.prebuilt(updater,'b'*40)
            self.assertFalse((updater.base/'images.download').exists())
            self.assertFalse(any(c[:2]==('docker','load') for c in updater.calls))

    def test_containerd_platform_ids_are_not_used_as_tag_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            updater.runtime_image='sha256:platform-specific-unaddressable'
            updater.platform_image=updater.runtime_image
            updater.addressable_image='sha256:taggable-index'
            updater.perform('update')
            self.assertEqual('COMPLETE',updater.status['phase'])
            self.assertFalse(any(c[:3]==('docker','compose','images') for c in updater.calls))
            tags=[c for c in updater.calls if c[:2]==('docker','tag')]
            self.assertEqual(['cloudnest-server:old','cloudnest-web:old'],[c[2] for c in tags])
            self.assertLess(updater.calls.index(tags[-1]),updater.calls.index(('docker','compose','build','server','web')))

    def test_retargeted_images_never_replace_the_actual_rollback_version(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            updater.addressable_image='sha256:new-index'
            updater.platform_image='sha256:new-platform'
            updater.perform('update')
            self.assertEqual('FAILED',updater.status['phase'])
            self.assertEqual('SNAPSHOT',updater.status['failedPhase'])
            self.assertIn('不匹配',updater.status['message'])
            self.assertFalse(any(c[:2]==('docker','tag') or 'stop' in c or 'merge' in c for c in updater.calls))

    def test_original_image_missing_stops_before_build_or_service_stop(self):
        updater=self.execute(failure='docker image inspect')
        self.assertEqual('FAILED',updater.status['phase'])
        self.assertIn('原镜像引用不可用',updater.status['message'])
        self.assertFalse(any('stop' in c or 'merge' in c or 'build' in c for c in updater.calls))
        self.assertIsNone(updater.status.get('backup'))

    def test_concurrent_image_retag_aborts_without_stopping(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            updater.pinned_image='sha256:concurrently-moved'
            updater.perform('update')
            self.assertEqual('FAILED',updater.status['phase'])
            self.assertIn('标签发生变化',updater.status['message'])
            self.assertFalse(any('stop' in c or 'merge' in c for c in updater.calls))

    def test_new_request_clears_previous_failure_and_backup_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            updater.status.update(failedPhase='BACKUP',backup='old-partial-folder')
            updater.perform('check')
            self.assertIsNone(updater.status['failedPhase'])
            self.assertIsNone(updater.status['backup'])

    def test_prebuilt_download_verifies_checksum_before_docker_import(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            payload=b'fixture-image-archive'
            checksum=hashlib.sha256(payload).hexdigest()
            responses=[io.BytesIO((checksum+'  cloudnest-images-linux-amd64.tar.gz\n'+'f'*64+'  cloudnest-windows-sync-dev.zip\n').encode()),io.BytesIO(payload)]
            with patch.object(module.urllib.request,'urlopen',side_effect=responses),patch.object(module.shutil,'disk_usage',return_value=SimpleNamespace(free=10*1024**3)):
                self.assertTrue(module.Updater.prebuilt(updater,'b'*40))
            self.assertTrue(any(c[:2]==('docker','load') for c in updater.calls))
            self.assertFalse((updater.base/'images.download').exists())

    def test_corrupt_prebuilt_never_imported(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            responses=[io.BytesIO(('0'*64+'  cloudnest-images-linux-amd64.tar.gz\n').encode()),io.BytesIO(b'corrupt')]
            with patch.object(module.urllib.request,'urlopen',side_effect=responses),patch.object(module.shutil,'disk_usage',return_value=SimpleNamespace(free=10*1024**3)):
                with self.assertRaisesRegex(RuntimeError,'SHA256'):module.Updater.prebuilt(updater,'b'*40)
            self.assertFalse(any(c[:2]==('docker','load') for c in updater.calls))

    def test_prebuilt_activation_preserves_existing_overlays_and_occurs_after_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            updater=FakeUpdater(directory)
            (updater.repo/'.env').write_text('COMPOSE_FILE=docker-compose.yml:deploy/compose.tls.yml:deploy/compose.updater.yml\nPRIVATE_FIXTURE=yes\n')
            with patch.object(updater,'prebuilt',return_value=True):updater.perform('update')
            self.assertEqual('COMPLETE',updater.status['phase'])
            self.assertIn('deploy/compose.tls.yml:deploy/compose.updater.yml:deploy/compose.images.yml',(updater.repo/'.env').read_text())
            self.assertNotIn(('docker','compose','build','server','web'),updater.calls)

    def execute(self, **kwargs):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        updater = FakeUpdater(directory.name, **kwargs)
        updater.perform('update')
        return updater

    def test_success_builds_before_stop_and_records_version(self):
        updater = self.execute()
        self.assertEqual('COMPLETE', updater.status['phase'])
        self.assertEqual('b' * 40, (updater.state / 'deployed').read_text())
        self.assertLess(updater.calls.index(('docker', 'compose', 'build', 'server', 'web')),
                        updater.calls.index(('docker', 'compose', 'stop', 'web', 'server')))

    def test_build_failure_keeps_old_containers_running(self):
        updater = self.execute(failure='compose build')
        self.assertEqual('FAILED', updater.status['phase'])
        self.assertFalse(any('stop' in c for c in updater.calls))
        self.assertEqual('a' * 40, (updater.state / 'deployed').read_text())

    def test_backup_failure_restarts_original_containers_without_recreate(self):
        updater = self.execute(failure='backup')
        self.assertEqual('FAILED', updater.status['phase'])
        self.assertIn(('docker', 'compose', 'start', 'server', 'web'), updater.calls)
        self.assertFalse(any('up' in c for c in updater.calls))

    def test_startup_failure_does_not_rollback_migrated_database(self):
        updater = self.execute(failure='compose up')
        self.assertEqual('FAILED', updater.status['phase'])
        self.assertIn('人工恢复', updater.status['message'])
        self.assertNotIn(('docker', 'compose', 'start', 'server', 'web'), updater.calls)

    def test_conflicting_local_changes_are_preserved(self):
        for path in ('README.md', 'docker-compose.yml'):
            updater = self.execute(dirty=path, upstream=path)
            self.assertEqual('FAILED', updater.status['phase'])
            self.assertFalse(any('merge' in c for c in updater.calls))

    def test_legacy_ip_binding_can_be_preserved(self):
        self.assertEqual('COMPLETE', self.execute(dirty='docker-compose.yml').status['phase'])

    def test_check_does_not_build_or_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            updater = FakeUpdater(directory)
            updater.perform('check')
            self.assertEqual('READY', updater.status['phase'])
            self.assertTrue(updater.status['available'])
            self.assertFalse(any(c[:2] == ('docker', 'compose') for c in updater.calls))

    def test_real_backup_flow_writes_dump_configuration_and_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            updater = FakeUpdater(directory)
            folder = Path(directory) / 'backup'
            folder.mkdir()
            def dump(*args, **kwargs):
                kwargs['stdout'].write(b'-- database fixture\n')
                return SimpleNamespace(returncode=0)
            with patch.object(module.subprocess, 'run', side_effect=dump):
                module.Updater.backup(updater, folder)
            self.assertEqual('-- database fixture\n', (folder / 'database.sql').read_text())
            self.assertEqual('PRIVATE_FIXTURE=yes', (folder / '.env').read_text())
            checksums = (folder / 'SHA256SUMS').read_text()
            self.assertIn('database.sql', checksums)
            self.assertIn('app-data.tar.gz', checksums)
            self.assertIn('.env', checksums)

    def test_failed_database_dump_prevents_file_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            updater = FakeUpdater(directory)
            folder = Path(directory) / 'backup'
            folder.mkdir()
            with patch.object(module.subprocess, 'run', return_value=SimpleNamespace(returncode=1,stderr=b'dump failed')),patch.object(module.sys,'stderr',io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, '数据库备份失败'):
                    module.Updater.backup(updater, folder)
            self.assertFalse((folder / 'app-data.tar.gz').exists())
            self.assertFalse(any(c[:3] == ('docker', 'compose', 'run') for c in updater.calls))


if __name__ == '__main__':
    unittest.main()
