"""A/B checkpoint coordination. A stays stopped until the cold B snapshot is acknowledged."""
import json
import os
from pathlib import Path
import re
import subprocess
import uuid


def checkpoint(worker, folder):
    if not os.environ.get('RESTIC_REPOSITORY'):
        raise RuntimeError('Split backup requires an independent A metadata repository')
    host = os.environ.get('CLOUDNEST_B_BACKUP_HOST', '')
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.@-]{0,99}', host):
        raise RuntimeError('Split backup requires a pinned SSH host alias in CLOUDNEST_B_BACKUP_HOST')
    expected_endpoint = os.environ.get('CLOUDNEST_B_INTERNAL_ENDPOINT', '').rstrip('/')
    if not expected_endpoint:
        raise RuntimeError('Split backup requires CLOUDNEST_B_INTERNAL_ENDPOINT to prove backend coverage')
    worker.compose('run', '--rm', '--no-deps', 'server', '--server.port=0',
                   '--cloudnest.maintenance=true', '--cloudnest.action=object-manifest',
                   '--cloudnest.index-enabled=false', timeout=900, log_output=False)
    manifest = json.loads(worker.compose('run', '--rm', '--no-deps', '--entrypoint', 'cat',
                         'server', '/app/data/backup-manifest.json', log_output=False))
    validate_coverage(manifest, expected_endpoint)
    batch = uuid.uuid4().hex
    # No shell; a fixed remote helper with a restricted grammar. SSH host keys must be preinstalled.
    response = subprocess.run(
        ['ssh', '-oBatchMode=yes', '-oStrictHostKeyChecking=yes', host, 'sudo', '-n',
         '/usr/local/sbin/cloudnest-b-backup', 'create', batch],
        check=True, capture_output=True, text=True, timeout=7200)
    b = json.loads(response.stdout)
    if (b.get('batch') != batch or not re.fullmatch('[a-f0-9]{64}', b.get('snapshot', ''))
            or b.get('status') != 'READY'):
        raise RuntimeError('B did not acknowledge the matching checkpoint')
    (Path(folder) / 'object-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    (Path(folder) / 'batch.json').write_text(json.dumps(
        {'format': 1, 'mode': 'split', 'batch': batch, 'b': b}), encoding='utf-8')


def validate_coverage(manifest, endpoint):
    policies = {'s3:'+p['id']: p['endpoint'].rstrip('/') for p in manifest.get('policies', [])}
    policies['minio'] = manifest.get('legacyEndpoint', '').rstrip('/')
    backends = set(manifest.get('backends', [])) | {obj['backend'] for obj in manifest['objects']}
    if any(policies.get(backend) != endpoint for backend in backends if backend != 'local'):
        raise RuntimeError('Split checkpoint references an unknown backend or storage outside B')


def upload_metadata(worker, folder):
    batch, _ = validate_split(folder)
    subprocess.run(['restic', 'backup', '--tag', 'cloudnest-a', '--tag', batch['batch'], str(folder)],
                   check=True, timeout=7200, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
    worker.status['backupOffsite'] = 'COMPLETE'


def validate_split(folder):
    folder = Path(folder)
    batch = json.loads((folder / 'batch.json').read_text(encoding='utf-8'))
    manifest = json.loads((folder / 'object-manifest.json').read_text(encoding='utf-8'))
    if (batch.get('format') != 1 or batch.get('mode') != 'split'
            or not re.fullmatch('[a-f0-9]{32}', batch.get('batch', ''))
            or batch.get('b', {}).get('batch') != batch['batch']
            or batch['b'].get('status') != 'READY'
            or not re.fullmatch('[a-f0-9]{64}', batch['b'].get('snapshot', ''))):
        raise RuntimeError('Invalid split checkpoint')
    seen = set()
    for obj in manifest['objects']:
        if (obj['id'] in seen or not re.fullmatch(r'objects/[a-f0-9-]{36}', obj['object_key'])
                or obj['size'] < 0 or not re.fullmatch('[a-f0-9]{64}', obj['sha256'])):
            raise RuntimeError('Invalid split object manifest')
        seen.add(obj['id'])
    return batch, manifest
