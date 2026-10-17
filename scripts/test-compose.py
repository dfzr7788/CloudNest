#!/usr/bin/env python3
"""Disposable CI Docker smoke and restore drill. Never run against an existing deployment."""
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request
import backup
from updater import Updater


class Browser:
    def __init__(self,port):
        self.origin=f'http://127.0.0.1:{port}'
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(self,path,data=None,raw=False):
        headers={}
        if data is not None:
            token=json.loads(self.opener.open(self.origin+'/api/auth/csrf',timeout=15).read())['token']
            headers={'X-XSRF-TOKEN':token,'Content-Type':'application/json'}
        response=self.opener.open(urllib.request.Request(self.origin+'/api'+path,data=None if data is None else json.dumps(data).encode(),headers=headers),timeout=30)
        body=response.read()
        return body if raw else json.loads(body or '{}')
    def ready(self):
        for n in range(60):
            try:self.request('/auth/csrf');return
            except Exception:time.sleep(2)
        raise RuntimeError('Smoke service did not become ready')


if __name__=='__main__':
    if os.environ.get('CI')!='true':raise SystemExit('CI-only: creates disposable Docker projects')
    repo=Path(__file__).resolve().parent.parent
    if (repo/'.env').exists():raise SystemExit('Refusing to use an existing .env')
    subprocess.run(['bash','scripts/init-env.sh'],cwd=repo,check=True)
    env=repo/'.env';text=env.read_text().replace('WEB_PORT=8088','WEB_PORT=18088').replace('REGISTRATION_OPEN=false','REGISTRATION_OPEN=true');env.write_text(text)
    base=Path(tempfile.mkdtemp(prefix='cloudnest-drill-'));(base/'state').mkdir()
    worker=Updater(repo,base);revision=worker.run('git','rev-parse','HEAD');worker.activate_prebuilt(revision)
    subprocess.run(['docker','compose','up','-d','--no-build','--wait','--wait-timeout','240'],cwd=repo,check=True)
    first=Browser(18088);first.ready();password=secrets.token_urlsafe(24)
    user=first.request('/auth/register',{'username':'restore_fixture','password':password})
    content=b'portable backup verification'
    import hashlib
    job=first.request('/uploads/init',{'folderId':user['rootId'],'name':'restore.txt','size':len(content),'sha256':hashlib.sha256(content).hexdigest()})
    csrf=first.request('/auth/csrf')['token']
    first.opener.open(urllib.request.Request(first.origin+'/api/uploads/'+job['id']+'/parts/0',data=content,method='PUT',headers={'Content-Type':'application/octet-stream','X-XSRF-TOKEN':csrf}),timeout=30).close()
    file=first.request('/uploads/'+job['id']+'/complete',{})['fileId']
    snapshot=backup.take(repo,base)
    target=base/'restored'
    backup.restore(snapshot,target,'cloudnest-restore-ci',18089,True)
    restored=Browser(18089);restored.ready()
    restored.request('/auth/login',{'username':'restore_fixture','password':password})
    assert restored.request('/files/'+file+'/download',raw=True)==content
    first.ready()
    first.request('/auth/login',{'username':'restore_fixture','password':password})
    assert first.request('/files/'+file+'/download',raw=True)==content
    print('Docker health, portable backup, isolated restore and original deployment preservation passed.')
