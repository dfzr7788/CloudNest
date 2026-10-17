#!/usr/bin/env python3
"""Import a verified prebuilt release for the checked-out commit, without changing running containers."""
import os
from pathlib import Path
from updater import Updater

if __name__=='__main__':
    os.umask(0o077)
    repo=Path(__file__).resolve().parent.parent
    base=Path('/var/lib/cloudnest-updater')
    base.mkdir(parents=True,exist_ok=True)
    worker=Updater(repo,base)
    revision=worker.run('git','rev-parse','HEAD')
    if not worker.prebuilt(revision):
        raise SystemExit('Prebuilt release not available for this revision/architecture yet. Wait for Actions, or use source builds.')
    worker.activate_prebuilt(revision)
    print('Images imported. Run: docker compose up -d --no-build --wait')
