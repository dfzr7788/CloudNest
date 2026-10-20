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
import platform
import re
import urllib.request
import urllib.error
import traceback

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

    def record_failure(self, phase):
        """Keep exception details in root-only logs, never in the browser status."""
        detail = f'CloudNest worker failure: phase={phase}, backupStep={self.status.get("backupStep")}\n{traceback.format_exc()}'
        if self.log:
            try:
                if self.log.exists() and self.log.stat().st_size > 10 * 1024 * 1024:
                    self.log.replace(self.log.with_suffix('.log.1'))
                with self.log.open('a', encoding='utf-8') as stream:
                    self.log.chmod(0o600)
                    stream.write(detail + '\n')
            except OSError:
                pass  # A full log disk must not prevent original-container recovery.
        print(detail, file=sys.stderr, flush=True)

    def backup_step(self, step, message):
        self.publish('BACKUP', message, backupStep=step)

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
        self.backup_step('STOP', '暂停写入并备份数据库与文件，请保持页面打开')
        self.stopped = True
        self.compose('stop', 'web', 'server')
        self.backup_step('INSPECT_OBJECTS', '正在检查远端对象与备份方式')
        remote_count=self.compose('exec','-T','mysql','sh','-c',
                     'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ucloudnest -Dcloudnest -N -B -e "SELECT COUNT(*) FROM file_blob WHERE status=\'READY\' AND backend<>\'local\'"',log_output=False)
        mode = os.environ.get('CLOUDNEST_BACKUP_MODE', 'split' if remote_count.strip() != '0' else 'portable')
        if mode not in ('split', 'portable'):
            raise RuntimeError('Invalid CLOUDNEST_BACKUP_MODE')
        if mode == 'split':
            self.backup_step('REMOTE_CHECKPOINT', '正在创建远端对象检查点')
            try:
                from split_backup import checkpoint
            except ModuleNotFoundError as error:
                if error.name != 'split_backup':
                    raise
                raise RuntimeError('宿主机更新器缺少 split_backup.py，请同步宿主机脚本后重试') from error
            checkpoint(self, folder)
        elif remote_count.strip() != '0':
            self.backup_step('REMOTE_EXPORT', '正在导出并校验远端对象')
            self.compose('run','--rm','--no-deps','server','--server.port=0',
                         '--cloudnest.maintenance=true','--cloudnest.action=export-objects',
                         '--cloudnest.index-enabled=false',timeout=3600)
        # Dump through the container environment; passwords are never argv or status data.
        self.backup_step('DATABASE', '正在导出 MySQL 数据库')
        dump = folder / 'database.sql'
        with dump.open('wb') as stream:
            result = subprocess.run(
                ['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
                 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -ucloudnest --single-transaction --no-tablespaces cloudnest'],
                cwd=self.repo, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.PIPE, timeout=900)
        if result.returncode or not dump.stat().st_size:
            if result.stderr:
                print(result.stderr.decode(errors='replace'), file=sys.stderr, flush=True)
            raise RuntimeError('数据库备份失败，已中止更新')
        self.backup_step('FILES', '正在归档文件数据与签名密钥')
        container = self.compose('ps', '-a', '-q', 'server')
        mounts = json.loads(self.run('docker', 'inspect', '--format', '{{json .Mounts}}', container))
        volume = next((m['Name'] for m in mounts if m['Destination'] == '/app/data' and m['Type'] == 'volume'), None)
        if not volume:
            raise RuntimeError('只支持标准 Compose 本地数据卷；请改用人工部署')
        # Split snapshots exclude legacy exported B objects: B bytes never stage on A.
        self.compose('run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'tar',
                     '-v', str(folder) + ':/backup', 'server',
                     *(['--exclude=./backup-export'] if mode == 'split' else []),
                     '-czf', '/backup/app-data.tar.gz', '-C', '/app/data', '.', timeout=1800)
        self.backup_step('CONFIG', '正在保存部署配置和版本信息')
        shutil.copyfile(self.repo / '.env', folder / '.env')
        for name in ['docker-compose.yml', 'deploy/compose.public.yml',
                     'deploy/compose.updater.yml', 'deploy/compose.tls.yml', 'deploy/compose.images.yml']:
            source = self.repo / name
            if source.exists():
                target = folder / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        (folder / 'revisions.json').write_text(json.dumps({k: self.status.get(k) for k in ('current', 'latest')}))
        if not (folder / 'app-data.tar.gz').is_file():
            raise RuntimeError('文件备份产物缺失')
        self.backup_step('CHECKSUM', '正在生成备份校验清单')
        with (folder / 'SHA256SUMS').open('w') as sums:
            for item in sorted(folder.rglob('*')):
                if item.is_file() and item != folder / 'SHA256SUMS':
                    with item.open('rb') as stream:
                        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    sums.write(f'{digest}  {item.relative_to(folder).as_posix()}\n')
        if mode == 'split':
            self.backup_step('OFFSITE', '正在上传分离备份元数据')
            from split_backup import upload_metadata
            upload_metadata(self, folder)

    def prebuilt(self, revision):
        """Only consume assets from this repository and this exact commit, before stopping service."""
        if platform.machine().lower() not in ('x86_64', 'amd64'):
            return False
        if not re.fullmatch(r'[a-f0-9]{40}', revision):
            raise RuntimeError('版本号无效')
        base = f'https://github.com/duyan9030-dotcom/CloudNest/releases/download/build-{revision}/'
        asset = 'cloudnest-images-linux-amd64.tar.gz'
        try:
            with urllib.request.urlopen(base + 'SHA256SUMS', timeout=30) as response:
                checksums = response.read(8193).decode('ascii')
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return False
            raise RuntimeError('预构建镜像校验清单下载失败') from error
        matches = re.findall(r'^([a-f0-9]{64})  '+re.escape(asset)+r'$', checksums, re.M)
        if len(checksums)>8192 or len(matches)!=1:
            raise RuntimeError('预构建镜像校验清单无效')
        archive = self.base / 'images.download'
        try:
            digest=hashlib.sha256()
            size=0
            with urllib.request.urlopen(base + asset, timeout=60) as response, archive.open('wb') as out:
                while block:=response.read(1024*1024):
                    size+=len(block)
                    if size>2*1024**3:
                        raise RuntimeError('镜像归档超过 2 GiB 下载上限，未导入镜像')
                    free = shutil.disk_usage(self.base).free
                    if free-len(block)<3*1024**3:
                        raise RuntimeError(f'更新盘可用空间不足：当前 {free/1024**3:.2f} GiB，下载期间需保留至少 3 GiB')
                    digest.update(block)
                    out.write(block)
            if digest.hexdigest()!=matches[0]:
                raise RuntimeError('预构建镜像 SHA256 校验失败，未导入镜像')
            self.run('docker','load','--input',str(archive),timeout=900)
            for service in ('server','web'):
                self.run('docker','image','inspect',f'cloudnest-{service}:sha-{revision}',log_output=False)
            return True
        finally:
            archive.unlink(missing_ok=True)

    def activate_prebuilt(self, revision):
        env=self.repo/'.env'
        text=env.read_text(encoding='utf-8')
        match=re.search(r'^COMPOSE_FILE=(.*)$',text,re.M)
        files=match[1].strip().strip('\"\'') if match else 'docker-compose.yml:deploy/compose.public.yml'
        if 'deploy/compose.images.yml' not in files.split(':'):
            files+=':deploy/compose.images.yml'
        for key,value in [('COMPOSE_FILE',files),('DEPLOY_REVISION',revision)]:
            pattern=r'^'+key+r'=.*$'
            text=re.sub(pattern,key+'='+value,text,flags=re.M) if re.search(pattern,text,re.M) else text.rstrip()+'\n'+key+'='+value+'\n'
        temporary=env.with_name('.env.update')
        temporary.write_text(text,encoding='utf-8')
        temporary.chmod(0o600)
        temporary.replace(env)

    def refresh_worker_files(self, installed):
        """Adopt the trusted revision's worker and helper together after a successful update."""
        installed = Path(installed)
        if not installed.is_file():
            return False
        files = [(self.repo/'scripts/split_backup.py', installed.with_name('split_backup.py'), 0o644),
                 (self.repo/'scripts/updater.py', installed, 0o755)]
        # Validate all sources before replacing either installed file.
        if not all(source.is_file() for source, _, _ in files):
            raise RuntimeError('受信源码中的宿主机更新脚本不完整')
        changed = False
        for source, target, mode in files:
            if target.is_file() and source.read_bytes() == target.read_bytes():
                continue
            temporary = target.with_suffix(target.suffix+'.new')
            shutil.copyfile(source, temporary)
            temporary.chmod(mode)
            temporary.replace(target)
            changed = True
        return changed

    def snapshot_images(self, stamp):
        """Pin the images actually used by the single-instance containers, before building.

        A containerd platform/config digest from `compose images -q` is not necessarily
        a taggable image reference. Resolve the original reference and verify its runtime
        identity instead; never silently snapshot a tag that has moved to different content.
        Inspect only selected fields, not container environments or credentials.
        """
        images = {}
        container_format = '{"image":{{json .Image}},"reference":{{json .Config.Image}},"composeImage":{{json (index .Config.Labels "com.docker.compose.image")}}}'
        image_format = '{"id":{{json .Id}},"os":{{json .Os}},"architecture":{{json .Architecture}}}'
        for service in ('server', 'web'):
            containers = self.compose('ps', '-a', '-q', service).splitlines()
            if len(containers) != 1:
                raise RuntimeError(f'{service} 需要恰好一个现有容器，无法保存更新前镜像')
            info = json.loads(self.run('docker', 'container', 'inspect', '--format', container_format,
                                       containers[0], log_output=False))
            reference = info.get('reference') or info.get('image')
            known = {value for value in (info.get('image'), info.get('composeImage')) if value}
            if not reference or not known:
                raise RuntimeError(f'{service} 当前容器缺少镜像身份，已停止更新')
            try:
                image = json.loads(self.run('docker', 'image', 'inspect', '--format', image_format,
                                            reference, log_output=False))
            except RuntimeError as error:
                raise RuntimeError(f'{service} 原镜像引用不可用，无法保存回退镜像；请检查服务器镜像，原容器未停止') from error
            canonical = image.get('id')
            matches = canonical in known
            if not matches and image.get('os') and image.get('architecture'):
                try:
                    platform_id = self.run('docker', 'image', 'inspect', '--platform',
                                           image['os'] + '/' + image['architecture'], '--format', '{{.Id}}',
                                           reference, log_output=False)
                    matches = platform_id in known
                except RuntimeError:
                    matches = False
            if not canonical or not matches:
                raise RuntimeError(f'{service} 镜像标签与运行容器不匹配，无法确认回退版本；原容器未停止')
            rollback = f'cloudnest-rollback-{service}:{stamp}'
            self.run('docker', 'tag', reference, rollback)
            pinned = self.run('docker', 'image', 'inspect', '--format', '{{.Id}}', rollback, log_output=False)
            if pinned != canonical:
                raise RuntimeError(f'{service} 保存回退镜像时标签发生变化，已停止更新；原容器未停止')
            images[service] = canonical
        return images

    def perform(self, action):
        self.stopped = False
        self.deploying = False
        self.publish('CHECKING', '正在检查 GitHub main 分支', busy=True, failedPhase=None, backup=None, backupStep=None)
        try:
            head, target = self.inspect_target()
            if action == 'check' or not self.status['available']:
                self.publish('READY', '发现新版本，可以更新' if self.status['available'] else '已经是最新版本', busy=False)
                return
            # The supported quick deployment uses a local data volume. Refuse to imply
            # that a remote bucket can be protected by a local-only backup.
            config = json.loads(self.compose('config', '--format', 'json', log_output=False))
            # All READY remote objects are exported and verified by the offline maintenance command.
            if shutil.disk_usage(self.base).free < 1024 ** 3:
                raise RuntimeError('更新盘可用空间不足 1 GiB，请先清理或扩容')
            stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
            folder = self.base / 'backups' / stamp
            folder.mkdir(parents=True, mode=0o700)
            # Save image IDs before rebuilding tags. Never automatically roll back a migrated database.
            self.publish('SNAPSHOT', '正在保存当前运行镜像的回退标签')
            images = self.snapshot_images(stamp)
            (folder / 'images.json').write_text(json.dumps(images))
            self.run('git', 'merge', '--ff-only', target)
            # Build while the old containers still serve requests.
            self.publish('BUILDING', '正在构建新版，当前服务仍可使用；首次下载可能较慢')
            ready=self.prebuilt(target)
            if not ready:
                if 'deploy/compose.images.yml' in (self.repo/'.env').read_text(encoding='utf-8'):
                    raise RuntimeError('该版本预构建镜像尚未就绪，请等待 GitHub Actions 完成后重试')
                self.compose('build', 'server', 'web', timeout=3600)
            self.status['backup'] = stamp
            self.backup(folder)
            if ready:
                self.activate_prebuilt(target)
            self.deploying = True
            self.publish('RESTARTING', '备份完成，正在切换新版；页面会短暂断开')
            self.compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '180', 'server', 'web', timeout=240)
            (self.state / 'deployed').write_text(target)
            self.publish('COMPLETE', '更新完成，服务健康检查通过，请刷新页面', busy=False, current=target, available=False)
        except Exception as error:
            failed_phase = self.status.get('phase', 'CHECKING')
            self.record_failure(failed_phase)
            message = str(error) if isinstance(error, RuntimeError) else f'更新执行异常（{type(error).__name__}），请查看服务器日志'
            if failed_phase == 'BACKUP' and self.status.get('backupStep'):
                message = f'备份步骤 {self.status["backupStep"]} 失败：' + message
            if self.stopped and not self.deploying:
                try:
                    # Start original containers, not the newly built image tags.
                    self.compose('start', 'server', 'web', timeout=180)
                    message += '；原服务已恢复'
                except Exception:
                    self.record_failure('RECOVER_ORIGINAL')
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
                    if action not in ('check', 'update', 'backup'):
                        raise ValueError('Invalid action')
                    if action=='backup':
                        self.publish('BACKUP','正在创建一致性备份，服务将短暂停止',busy=True,backupStep=None)
                        self.run(sys.executable,str(self.repo/'scripts/backup.py'),'create','--repo',str(self.repo),'--base',str(self.base),timeout=10800)
                        self.publish('COMPLETE','备份与校验完成；详情见备份列表',busy=False)
                    else:
                        with (self.state/'operation.lock').open('w') as operation:
                            fcntl.flock(operation,fcntl.LOCK_EX|fcntl.LOCK_NB)
                            self.perform(action)
                    reload_worker = action == 'update' and self.status.get('phase') == 'COMPLETE'
                except Exception:
                    self.record_failure('REQUEST')
                    self.publish('FAILED', '更新请求无效或执行中断，请查看服务器日志', busy=False)
                finally:
                    active.unlink(missing_ok=True)
                # Adopt the worker from the same trusted revision, after completing the request.
                installed = Path('/opt/cloudnest-updater/updater.py')
                if reload_worker and self.refresh_worker_files(installed):
                    os.execv(sys.executable, [sys.executable, str(installed), '--repo', str(self.repo), '--base', str(self.base)])
            time.sleep(2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', required=True)
    parser.add_argument('--base', default='/var/lib/cloudnest-updater')
    args = parser.parse_args()
    os.umask(0o077)
    Updater(args.repo, args.base).serve()
