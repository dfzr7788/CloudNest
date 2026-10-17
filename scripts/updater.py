#!/usr/bin/env python3
"""Host-only update worker. Fixed repository/actions; never accepts shell commands from the app."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import threading
import time

ORIGIN = 'https://github.com/duyan9030-dotcom/CloudNest.git'


class Updater:
    def __init__(self, repo, base):
        self.repo = Path(repo).resolve()
        self.base = Path(base)
        self.state = self.base / 'state'
        self.requests = self.base / 'requests'
        self.status = {'busy': False, 'phase': 'IDLE', 'message': '等待检查更新'}
        self.log = None
        self.stopped = False
        self.deploying = False

    def publish(self, phase, message, **extra):
        self.status.update(phase=phase, message=message, updatedAt=int(time.time() * 1000), **extra)
        temp = self.state / 'status.tmp'
        temp.write_text(json.dumps(self.status, ensure_ascii=False))
        temp.chmod(0o644)
        temp.replace(self.state / 'status.json')

    def run(self, *args, timeout=900, log_output=True):
        result = subprocess.run(args, cwd=self.repo, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if self.log and log_output:
            if self.log.exists() and self.log.stat().st_size > 10 * 1024 * 1024:
                self.log.replace(self.log.with_suffix('.log.1'))
            with self.log.open('ab') as stream:
                stream.write(result.stdout)
        if result.returncode:
            raise RuntimeError('命令执行失败，请在服务器查看更新日志')
        return result.stdout.decode(errors='replace').strip()

    def compose(self, *args, **kwargs):
        return self.run('docker', 'compose', *args, **kwargs)

    def inspect_target(self):
        if self.run('git', 'remote', 'get-url', 'origin') != ORIGIN:
            raise RuntimeError('origin 地址与已配置的官方仓库不符，已停止')
        if self.run('git', 'branch', '--show-current') != 'main':
            raise RuntimeError('更新服务仅支持 main 分支')
        self.run('git', 'fetch', 'origin', 'main', timeout=180)
        head = self.run('git', 'rev-parse', 'HEAD')
        target = self.run('git', 'rev-parse', 'origin/main')
        self.run('git', 'merge-base', '--is-ancestor', head, target)
        changed = self.run('git', 'diff', '--name-only', 'HEAD').splitlines()
        # Existing deployments may have changed the HTTP bind address in this file.
        # Preserve it only when this release does not modify that same tracked file.
        upstream_changes = self.run('git', 'diff', '--name-only', head, target).splitlines()
        if any(p != 'docker-compose.yml' or p in upstream_changes for p in changed):
            raise RuntimeError('存在与更新冲突的本地修改，请先在服务器处理；不会覆盖修改')
        deployed = self.state / 'deployed'
        if not deployed.exists():
            deployed.write_text(head)
        current = deployed.read_text().strip()
        self.status.update(current=current, latest=target, available=current != target)
        return head, target

    def backup(self, folder):
        self.publish('BACKUP', '暂停写入并备份数据库与文件，请保持页面打开')
        self.stopped = True
        self.compose('stop', 'web', 'server')
        # Dump through the container environment; passwords are never argv or status data.
        dump = folder / 'database.sql'
        with dump.open('wb') as stream:
            result = subprocess.run(
                ['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
                 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -ucloudnest --single-transaction --no-tablespaces cloudnest'],
                cwd=self.repo, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.PIPE, timeout=900)
        if result.returncode or not dump.stat().st_size:
            raise RuntimeError('数据库备份失败，已中止更新')
        container = self.compose('ps', '-a', '-q', 'server')
        mounts = json.loads(self.run('docker', 'inspect', '--format', '{{json .Mounts}}', container))
        volume = next((m['Name'] for m in mounts if m['Destination'] == '/app/data' and m['Type'] == 'volume'), None)
        if not volume:
            raise RuntimeError('只支持标准 Compose 本地数据卷；请改用人工部署')
        # Disabled for remote object storage: this archive would not back up the bucket.
        self.compose('run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'tar',
                     '-v', str(folder) + ':/backup', 'server',
                     '-czf', '/backup/app-data.tar.gz', '-C', '/app/data', '.', timeout=1800)
        shutil.copyfile(self.repo / '.env', folder / '.env')
        for name in ['docker-compose.yml', 'deploy/compose.public.yml',
                     'deploy/compose.updater.yml', 'deploy/compose.tls.yml']:
            source = self.repo / name
            if source.exists():
                target = folder / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        (folder / 'revisions.json').write_text(json.dumps({k: self.status.get(k) for k in ('current', 'latest')}))
        if not (folder / 'app-data.tar.gz').is_file():
            raise RuntimeError('文件备份产物缺失')
        with (folder / 'SHA256SUMS').open('w') as sums:
            for item in sorted(folder.rglob('*')):
                if item.is_file() and item.name != 'SHA256SUMS':
                    with item.open('rb') as stream:
                        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    sums.write(f'{digest}  {item.relative_to(folder).as_posix()}\n')

    def perform(self, action):
        self.stopped = False
        self.deploying = False
        self.publish('CHECKING', '正在检查 GitHub main 分支', busy=True)
        try:
            head, target = self.inspect_target()
            if action == 'check' or not self.status['available']:
                self.publish('READY', '发现新版本，可以更新' if self.status['available'] else '已经是最新版本', busy=False)
                return
            # The supported quick deployment uses a local data volume. Refuse to imply
            # that a remote bucket can be protected by a local-only backup.
            config = json.loads(self.compose('config', '--format', 'json', log_output=False))
            if config['services']['server']['environment'].get('STORAGE_BACKEND', 'local') != 'local':
                raise RuntimeError('对象存储部署须先完成远程桶备份，请使用人工更新')
            if shutil.disk_usage(self.base).free < 1024 ** 3:
                raise RuntimeError('更新盘可用空间不足 1 GiB，请先清理或扩容')
            stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
            folder = self.base / 'backups' / stamp
            folder.mkdir(parents=True, mode=0o700)
            self.status['backup'] = stamp
            # Save image IDs before rebuilding tags. Never automatically roll back a migrated database.
            images = {s: self.compose('images', '-q', s) for s in ('server', 'web')}
            for service, image in images.items():
                if not image:
                    raise RuntimeError('找不到当前镜像，无法保存更新前版本')
                self.run('docker', 'tag', image, f'cloudnest-rollback-{service}:{stamp}')
            (folder / 'images.json').write_text(json.dumps(images))
            self.run('git', 'merge', '--ff-only', target)
            # Build while the old containers still serve requests.
            self.publish('BUILDING', '正在构建新版，当前服务仍可使用；首次下载可能较慢')
            self.compose('build', 'server', 'web', timeout=3600)
            self.backup(folder)
            self.deploying = True
            self.publish('RESTARTING', '备份完成，正在切换新版；页面会短暂断开')
            self.compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '180', 'server', 'web', timeout=240)
            (self.state / 'deployed').write_text(target)
            self.publish('COMPLETE', '更新完成，服务健康检查通过，请刷新页面', busy=False, current=target, available=False)
        except Exception as error:
            failed_phase = self.status.get('phase', 'CHECKING')
            message = str(error) if isinstance(error, RuntimeError) else '更新执行异常，请查看服务器日志'
            if self.stopped and not self.deploying:
                try:
                    # Start original containers, not the newly built image tags.
                    self.compose('start', 'server', 'web', timeout=180)
                    message += '；原服务已恢复'
                except Exception:
                    message += '；恢复原服务失败，需要服务器维护'
            if self.deploying:
                message += '；新版启动失败，请根据备份人工恢复，未自动回退数据库'
            self.publish('FAILED', message, busy=False, failedPhase=failed_phase)

    def serve(self):
        import fcntl
        lock = (self.state / 'worker.lock').open('w')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        def heartbeat():
            while True:
                p = self.state / 'heartbeat'
                p.touch()
                p.chmod(0o644)
                time.sleep(5)
        threading.Thread(target=heartbeat, daemon=True).start()
        self.log = self.base / 'worker.log'
        active = self.state / 'active.json'
        if active.exists():
            self.publish('FAILED', '上次更新被中断，请在服务器检查服务与备份后重试', busy=False)
            active.unlink()
        elif (self.state / 'status.json').exists():
            self.status = json.loads((self.state / 'status.json').read_text())
            self.status['busy'] = False
        else:
            self.publish('IDLE', '更新服务已就绪，点击检查更新', busy=False)
        while True:
            request = self.requests / 'request.json'
            if request.exists() or request.is_symlink():
                reload_worker = False
                try:
                    request.replace(active)
                    # Reject symlinks, devices and oversized input from the app-writable queue.
                    fd = os.open(active, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                    with os.fdopen(fd) as stream:
                        info = os.fstat(stream.fileno())
                        if not stat.S_ISREG(info.st_mode) or info.st_size > 4096:
                            raise ValueError('Invalid request file')
                        action = json.load(stream).get('action')
                    if action not in ('check', 'update'):
                        raise ValueError('Invalid action')
                    self.perform(action)
                    reload_worker = action == 'update' and self.status.get('phase') == 'COMPLETE'
                except Exception:
                    self.publish('FAILED', '更新请求无效或执行中断，请查看服务器日志', busy=False)
                finally:
                    active.unlink(missing_ok=True)
                # Adopt the worker from the same trusted revision, after completing the request.
                installed = Path('/opt/cloudnest-updater/updater.py')
                source = self.repo / 'scripts/updater.py'
                if reload_worker and installed.exists() and source.read_bytes() != installed.read_bytes():
                    temporary = installed.with_suffix('.new')
                    shutil.copyfile(source, temporary)
                    temporary.chmod(0o755)
                    temporary.replace(installed)
                    os.execv(sys.executable, [sys.executable, str(installed), '--repo', str(self.repo), '--base', str(self.base)])
            time.sleep(2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', required=True)
    parser.add_argument('--base', default='/var/lib/cloudnest-updater')
    args = parser.parse_args()
    os.umask(0o077)
    Updater(args.repo, args.base).serve()
