#!/usr/bin/env python3
"""Install as /usr/local/sbin/cloudnest-b-backup, root:root 0755, on B only.
Cold Garage snapshots through restic; never streams B objects through A.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path('/etc/cloudnest-b')


def config_environment(path):
    if path.stat().st_uid != 0 or path.stat().st_mode & 0o077:
        raise RuntimeError('Restic environment must be root-owned 0600')
    env = dict(os.environ)
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        if not re.fullmatch('[A-Z][A-Z0-9_]*', key):
            raise RuntimeError('Invalid environment key')
        env[key] = value
    if not env.get('RESTIC_REPOSITORY') or not env.get('RESTIC_PASSWORD_FILE'):
        raise RuntimeError('Independent restic repository and password file are required')
    return env


def create(batch, run=subprocess.run):
    if not re.fullmatch('[a-f0-9]{32}', batch):
        raise RuntimeError('Invalid batch')
    env = config_environment(ROOT / 'restic.env')
    compose = ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(ROOT/'compose.yml')]
    stopped = False
    try:
        # B has one storage node. A is already stopped by the coordinator.
        stopped = True
        run([*compose, 'stop', 'garage'], check=True, stdout=subprocess.DEVNULL, timeout=180)
        result = run(['restic', 'backup', '--json', '--tag', 'cloudnest-b', '--tag', batch,
                      '/var/lib/cloudnest-garage', str(ROOT)], env=env, check=True,
                     capture_output=True, text=True, timeout=7200)
        summaries = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        summary = next(row for row in summaries if row.get('message_type') == 'summary')
        snapshot = summary['snapshot_id']
        if not re.fullmatch('[a-f0-9]{64}', snapshot):
            raise RuntimeError('Invalid snapshot ID')
        run(['restic', 'check'], env=env, check=True, stdout=subprocess.DEVNULL, timeout=7200)
    finally:
        if stopped:
            run([*compose, 'start', 'garage'], check=True, stdout=subprocess.DEVNULL, timeout=180)
    return {'status': 'READY', 'batch': batch, 'snapshot': snapshot, 'method': 'cold-restic',
            'repositoryMetadataChecked': True, 'fullRestoreVerified': False}


def restore(snapshot, target):
    if not re.fullmatch('[a-f0-9]{64}', snapshot):
        raise RuntimeError('Invalid snapshot ID')
    target = Path(target).resolve()
    # Restrict extraction to a new, dedicated isolation tree; never production paths.
    if target.parent != Path('/var/lib/cloudnest-restore') or target.exists():
        raise RuntimeError('Use a new direct child of /var/lib/cloudnest-restore')
    env = config_environment(ROOT/'restic.env')
    target.mkdir(parents=True, mode=0o700)
    subprocess.run(['restic', 'restore', snapshot, '--target', str(target), '--verify'],
                   env=env, check=True, timeout=86400)
    return {'restored': True, 'target': str(target), 'started': False}


if __name__ == '__main__':
    import fcntl
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['create', 'restore'])
    parser.add_argument('id')
    parser.add_argument('--target')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise RuntimeError('Root helper required')
    os.umask(0o077)
    with Path('/run/cloudnest-b-backup.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print(json.dumps(create(args.id) if args.action == 'create' else restore(args.id, args.target)))
