"""Failure-path tests with a simulated command runner; no Docker or server access."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('cloudnest_updater', Path(__file__).with_name('updater.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeUpdater(module.Updater):
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
        if command == 'docker compose ps -a -q server': return 'fixture-container'
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
            with patch.object(module.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
                with self.assertRaisesRegex(RuntimeError, '数据库备份失败'):
                    module.Updater.backup(updater, folder)
            self.assertFalse((folder / 'app-data.tar.gz').exists())
            self.assertFalse(any(c[:3] == ('docker', 'compose', 'run') for c in updater.calls))


if __name__ == '__main__':
    unittest.main()
