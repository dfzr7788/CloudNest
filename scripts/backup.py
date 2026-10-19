#!/usr/bin/env python3
"""Consistent portable backups and isolated restoration. Run on the Docker host as root."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import time
from updater import Updater


def verify(folder):
    folder=Path(folder).resolve()
    if any(p.is_symlink() for p in folder.rglob('*')):
        raise RuntimeError('Unsafe backup symlink')
    lines=(folder/'SHA256SUMS').read_text(encoding='utf-8').splitlines()
    names=set()
    for line in lines:
        match=re.fullmatch(r'([a-f0-9]{64})  (.+)',line)
        if not match:
            raise RuntimeError('Invalid checksum manifest')
        name=match[2]
        target=(folder/name).resolve()
        if not target.is_relative_to(folder) or target.is_symlink() or name in names:
            raise RuntimeError('Unsafe backup path')
        names.add(name)
        with target.open('rb') as stream:
            if hashlib.file_digest(stream,'sha256').hexdigest()!=match[1]:
                raise RuntimeError('Backup checksum mismatch: '+name)
    if not {'.env','database.sql','app-data.tar.gz','docker-compose.yml','revisions.json'}<=names:
        raise RuntimeError('Incomplete backup')
    actual={p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file() and p!=folder/'SHA256SUMS'}
    if actual!=names:
        raise RuntimeError('Backup contains unverified files')
    if (folder/'batch.json').exists():
        from split_backup import validate_split
        validate_split(folder)
    # Reject archive traversal, links, devices, and absolute paths before passing it to Docker tar.
    matched_manifest = not (folder/'batch.json').exists()
    with tarfile.open(folder/'app-data.tar.gz','r:gz') as archive:
        for item in archive:
            path=PurePosixPath(item.name)
            if path.is_absolute() or '..' in path.parts or '\\' in item.name or ':' in item.name or not (item.isfile() or item.isdir()):
                raise RuntimeError('Unsafe archive entry')
            if (folder/'batch.json').exists() and path.as_posix().lstrip('./') == 'backup-manifest.json':
                if matched_manifest or not item.isfile() or item.size > 100 * 1024 * 1024:
                    raise RuntimeError('Invalid checkpoint archive manifest')
                if json.load(archive.extractfile(item)) != json.loads((folder/'object-manifest.json').read_text(encoding='utf-8')):
                    raise RuntimeError('Checkpoint manifests differ')
                matched_manifest = True
    if not matched_manifest:
        raise RuntimeError('Checkpoint archive manifest missing')
    return {'verified':True,'files':len(names)}


def take(repo,base):
    worker=Updater(repo,base)
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
    folder=base/'backups'/stamp
    folder.mkdir(parents=True,mode=0o700)
    worker.status.update(current=worker.run('git','rev-parse','HEAD'),latest=worker.run('git','rev-parse','HEAD'))
    outcome={'status':'FAILED','createdAt':int(time.time()*1000)}
    try:
        worker.backup(folder)
        verify(folder)
        outcome.update(status='READY',verified=True,size=sum(p.stat().st_size for p in folder.rglob('*') if p.is_file()))
        # restic credentials remain host-only: supplied by a root-owned systemd EnvironmentFile.
        if worker.status.get('backupOffsite') == 'COMPLETE':
            outcome['offsite']='COMPLETE'
        elif os.environ.get('RESTIC_REPOSITORY'):
            subprocess.run(['restic','backup','--tag','cloudnest',str(folder)],check=True,timeout=7200,stdin=subprocess.DEVNULL)
            outcome['offsite']='COMPLETE'
        else:
            outcome['offsite']='NOT_CONFIGURED'
    except Exception:
        if outcome['status']=='READY':outcome.update(status='PARTIAL',offsite='FAILED')
        outcome['error']='Backup or offsite transfer failed; inspect the host service journal.'
        raise
    finally:
        if worker.stopped:
            worker.compose('start','server','web',timeout=180)
        # result.json is operational metadata, deliberately outside the immutable verified snapshot.
        (base/'state'/('backup-'+stamp+'.json')).write_text(json.dumps(outcome),encoding='utf-8')
        # Catalog separately, without adding unverified files to a snapshot.
        rows=[]
        for state in sorted((base/'state').glob('backup-*.json'),reverse=True)[:100]:
            rows.append({'id':state.stem.removeprefix('backup-'),**json.loads(state.read_text(encoding='utf-8'))})
        output=base/'state'/'backups.tmp'
        output.write_text(json.dumps(rows),encoding='utf-8');output.chmod(0o644);output.replace(base/'state'/'backups.json')
    return folder


def restore(folder,target,project,port,execute,remote_endpoint=None):
    folder=Path(folder).resolve();target=Path(target).resolve()
    verify(folder)
    split = (folder/'batch.json').exists()
    if split:
        from split_backup import validate_split
        from urllib.parse import urlsplit
        _, manifest = validate_split(folder)
        uri = urlsplit(remote_endpoint or '')
        if (any(c.isspace() for c in (remote_endpoint or '')) or uri.scheme not in ('http','https') or not uri.hostname or uri.username or uri.password
                or uri.query or uri.fragment or uri.path not in ('','/')):
            raise RuntimeError('Split restore requires an isolated --remote-endpoint')
        original_hosts = {urlsplit(p['endpoint']).hostname for p in manifest.get('policies',[])}
        original_hosts.add(urlsplit(manifest.get('legacyEndpoint','')).hostname)
        if uri.hostname in original_hosts:
            raise RuntimeError('Restore endpoint must use a different host from production')
    if not re.fullmatch(r'cloudnest-restore-[a-z0-9-]{1,30}',project) or not 1024<=port<=65535:
        raise RuntimeError('Use a new cloudnest-restore-NAME project and a port in 1024..65535')
    plan={'source':str(folder),'target':str(target),'project':project,'port':port,'productionModified':False}
    if not execute:
        print(json.dumps(plan));return
    if target.exists():
        raise RuntimeError('Restore destination must not exist')
    existing=subprocess.check_output(['docker','volume','ls','--format','{{.Name}}'],text=True).splitlines()
    if any(v.startswith(project+'_') for v in existing):
        raise RuntimeError('Restore project already has volumes')
    containers=subprocess.check_output(['docker','ps','-a','--filter','label=com.docker.compose.project='+project,'--format','{{.ID}}'],text=True).strip()
    if containers:raise RuntimeError('Restore project already has containers')
    target.mkdir(mode=0o700,parents=True)
    for name in ('docker-compose.yml','.env','deploy'):
        source=folder/name
        if source.is_dir():shutil.copytree(source,target/name)
        elif source.is_file():shutil.copyfile(source,target/name)
    # Restore binds only loopback; publishing remains a separate reviewed server configuration step.
    env=target/'.env';text=env.read_text(encoding='utf-8')
    text=re.sub(r'^(COMPOSE_FILE|COMPOSE_PROJECT_NAME|WEB_PORT|STORAGE_BACKEND|SECURE_COOKIES)=.*\n?', '',text,flags=re.M)
    text+='\nSECURE_COOKIES=false\n'
    if split:
        text=re.sub(r'^MINIO_ENDPOINT=.*\n?', '',text,flags=re.M)
        text+='\nMINIO_ENDPOINT='+remote_endpoint.rstrip('/')+'\n'
    text+=f'\nCOMPOSE_PROJECT_NAME={project}\nWEB_PORT={port}\nSTORAGE_BACKEND=local\nCOMPOSE_FILE=docker-compose.yml:restore-bind.yml\n'
    env.write_text(text,encoding='utf-8');env.chmod(0o600)
    (target/'restore-bind.yml').write_text('services:\n  web:\n    ports: !override\n      - "127.0.0.1:'+str(port)+':80"\n')
    # Use the exact backed-up source revision; never restore an old DB into arbitrary newer code.
    revision=json.loads((folder/'revisions.json').read_text())['current']
    if not re.fullmatch('[a-f0-9]{40}',revision):raise RuntimeError('Invalid backup revision')
    images=all(subprocess.run(['docker','image','inspect',f'cloudnest-{service}:sha-{revision}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0 for service in ('server','web'))
    images=images and (target/'deploy/compose.images.yml').is_file()
    if images:
        text=env.read_text(encoding='utf-8')
        text=re.sub(r'^DEPLOY_REVISION=.*\n?', '',text,flags=re.M)
        text=text.replace('COMPOSE_FILE=docker-compose.yml:restore-bind.yml','COMPOSE_FILE=docker-compose.yml:restore-bind.yml:deploy/compose.images.yml')
        env.write_text(text+'\nDEPLOY_REVISION='+revision+'\n',encoding='utf-8')
    else:
        subprocess.run(['git','clone','--no-checkout','https://github.com/duyan9030-dotcom/CloudNest.git',str(target/'source')],check=True)
        subprocess.run(['git','checkout','--detach',revision],cwd=target/'source',check=True)
        for directory in ('cloudnest-server','cloudnest-web'):
            shutil.move(str(target/'source'/directory),str(target/directory))
    def compose(*args,**kwargs):return subprocess.run(['docker','compose','-p',project,*args],cwd=target,check=True,**kwargs)
    if not images:compose('build','server','web')
    compose('up','-d','--wait','mysql','redis')
    with (folder/'database.sql').open('rb') as sql:
        compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -ucloudnest cloudnest',stdin=sql)
    compose('run','--rm','--no-deps','--user','0','--entrypoint','tar','-v',str(folder)+':/backup:ro','server','-xzf','/backup/app-data.tar.gz','-C','/app/data')
    remote=subprocess.check_output(['docker','compose','-p',project,'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -ucloudnest -Dcloudnest -N -B -e "SELECT COUNT(*) FROM file_blob WHERE status=\'READY\' AND backend<>\'local\'"'],cwd=target,text=True).strip()
    if split:
        compose('run','--rm','--no-deps','server','--server.port=0','--cloudnest.maintenance=true',
                '--cloudnest.action=restore-split-target','--cloudnest.restore-endpoint='+remote_endpoint.rstrip('/'),
                '--cloudnest.index-enabled=false')
        compose('run','--rm','--no-deps','server','--server.port=0','--cloudnest.maintenance=true',
                '--cloudnest.action=verify-manifest','--cloudnest.index-enabled=false')
    elif remote!='0':compose('run','--rm','--no-deps','server','--server.port=0','--cloudnest.maintenance=true','--cloudnest.action=restore-local-objects','--cloudnest.index-enabled=false')
    compose('run','--rm','--no-deps','server','--server.port=0','--cloudnest.maintenance=true','--cloudnest.action=audit','--cloudnest.index-enabled=false')
    compose('up','-d','--no-build','--wait','--wait-timeout','180','server','web')
    print(json.dumps({**plan,'restored':True,'url':f'http://127.0.0.1:{port}'}))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['create','list','verify','restore'])
    parser.add_argument('--repo',default=str(Path(__file__).resolve().parent.parent))
    parser.add_argument('--base',type=Path,default=Path('/var/lib/cloudnest-updater'))
    parser.add_argument('--snapshot',type=Path)
    parser.add_argument('--target',type=Path)
    parser.add_argument('--project',default='cloudnest-restore-check')
    parser.add_argument('--port',type=int,default=18088)
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--remote-endpoint',help='Isolated B endpoint, required for split restoration')
    args=parser.parse_args();os.umask(0o077)
    if args.action=='verify':print(json.dumps(verify(args.snapshot)))
    elif args.action=='restore':restore(args.snapshot,args.target,args.project,args.port,args.execute,args.remote_endpoint)
    elif args.action=='list':print((args.base/'state'/'backups.json').read_text())
    else:
        import fcntl
        args.base.mkdir(parents=True,exist_ok=True);(args.base/'state').mkdir(exist_ok=True)
        with (args.base/'state'/'operation.lock').open('w') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            print(take(args.repo,args.base))
