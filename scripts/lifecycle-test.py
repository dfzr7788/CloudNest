"""Real HTTP verification with a 256 MiB Java heap, restart, and offline backup restore.
No test password or authentication cookie is written to the report.
"""
import ctypes
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.tools' / ('lifecycle-' + str(int(time.time())))
WORK.mkdir(parents=True)
REPORT = ROOT / 'docs/evidence/lifecycle-results.json'
JAVA = os.environ.get('JAVA_EXE', 'java')
JAR = WORK / 'server.jar'
shutil.copyfile(ROOT / 'cloudnest-server/target/cloudnest-server-1.0.0.jar', JAR)
BASE = 'http://127.0.0.1:18081'
results = []

def record(case, expected, actual, started):
    results.append(dict(id=case, expected=expected, actual=actual, status='PASS', seconds=round(time.time()-started, 3)))
    REPORT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(case, 'PASS', flush=True)

class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
    def call(self, method, path, body=None, raw=None):
        headers = {}
        if method not in ('GET', 'HEAD'):
            csrf = self.call('GET', '/api/auth/csrf')['token']
            headers['X-XSRF-TOKEN'] = csrf
        if raw is not None:
            data = raw
            headers['Content-Type'] = 'application/octet-stream'
        elif body is not None:
            data = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        else:
            data = b'' if method != 'GET' else None
        request = urllib.request.Request(BASE+path, data=data, headers=headers, method=method)
        with self.http.open(request, timeout=600) as response:
            text = response.read()
            return json.loads(text) if text else None
    def download_hash(self, path):
        digest = hashlib.sha256()
        count = 0
        with self.http.open(BASE+path, timeout=600) as response:
            while chunk := response.read(1024*1024):
                digest.update(chunk)
                count += len(chunk)
        return digest.hexdigest(), count

def start(data, tag):
    log = open(WORK / (tag+'.log'), 'wb')
    args = [JAVA, '-Xmx256m', '-jar', str(JAR), '--server.port=18081', '--cloudnest.data-dir='+str(data),
            '--spring.datasource.url=jdbc:h2:file:'+str(data/'cloudnest').replace('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0']
    process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError('Server exited; see '+str(WORK/(tag+'.log')))
        try:
            with urllib.request.urlopen(BASE+'/actuator/health', timeout=2) as response:
                if response.status==200:
                    return process,log
        except (OSError,urllib.error.URLError):
            time.sleep(.5)
    process.kill()
    raise RuntimeError('Server startup timed out')

def stop(process, log):
    process.terminate()
    try: process.wait(timeout=15)
    except subprocess.TimeoutExpired: process.kill();process.wait(timeout=10)
    log.close()

process = log = None
try:
    original = WORK/'original'
    process,log = start(original,'initial')
    client = Client()
    user = client.call('POST','/api/auth/register',{'username':'lifecycle','password':'Lifecycle-test-123'})
    # Fixed 8 MiB chunk repeated 128 times: no 1 GiB Python buffer or source file.
    chunk = bytes(range(256))*32768
    size = len(chunk)*128
    digest = hashlib.sha256()
    for _ in range(128): digest.update(chunk)
    expected_hash = digest.hexdigest()
    started = time.time()
    session = client.call('POST','/api/uploads/init',{'folderId':user['rootId'],'name':'1GiB-test.bin','size':size,'sha256':expected_hash})
    sid = session['id']
    for index in range(64): client.call('PUT',f'/api/uploads/{sid}/parts/{index}',raw=chunk)
    stop(process,log);process=log=None
    process,log = start(original,'resume')
    resumed = client.call('GET',f'/api/uploads/{sid}')
    assert resumed['uploadedParts']==list(range(64)), resumed['uploadedParts']
    record('L01', '128 分片中的前 64 片在重启后保留；旧登录凭证仍可用于未过期会话', {'partsBeforeRestart':64,'partsAfterRestart':len(resumed['uploadedParts'])}, started)
    started = time.time()
    for index in range(64,128): client.call('PUT',f'/api/uploads/{sid}/parts/{index}',raw=chunk)
    complete = client.call('POST',f'/api/uploads/{sid}/complete',{})
    file_id = complete['fileId']
    actual_hash,actual_size = client.download_hash(f'/api/files/{file_id}/download')
    assert actual_size==size and actual_hash==expected_hash
    record('L02','1 GiB 上传、合并、下载摘要完全一致；Java 最大堆 256 MiB',{'bytes':actual_size,'sha256':actual_hash,'maxHeapMiB':256,'partCount':128},started)
    started = time.time()
    instant = client.call('POST','/api/uploads/init',{'folderId':user['rootId'],'name':'second-copy.bin','size':size,'sha256':expected_hash})
    assert instant['status']=='COMPLETE' and not instant['uploadedParts']
    account = client.call('GET','/api/user/me')
    assert account['used']==size*2 and account['reserved']==0
    record('L03','同用户秒传不传分片；两个逻辑文件分别占用空间',{'instantStatus':instant['status'],'uploadedParts':0,'used':account['used'],'reserved':account['reserved']},started)
    started = time.time()
    stop(process,log);process=log=None
    # Consistent offline backup: include DB, private objects, parts, and signing key together.
    backup = WORK/'backup'
    shutil.copytree(original,backup)
    restored = WORK/'restored'
    shutil.copytree(backup,restored)
    process,log = start(restored,'restored')
    profile = client.call('GET','/api/user/me')
    restored_hash,restored_size = client.download_hash(f'/api/files/{file_id}/download')
    assert profile['id']==user['id'] and profile['used']==size*2
    assert restored_size==size and restored_hash==expected_hash
    record('L04','离线备份恢复到全新目录，账号、配额、签名会话与 1 GiB 文件保持一致',{'sameUser':True,'used':profile['used'],'bytes':restored_size,'sha256':restored_hash,'restoredIntoDifferentDirectory':True},started)
    stop(process,log);process=log=None
    started=time.time()
    audit=subprocess.run([JAVA,'-Xmx256m','-jar',str(JAR),'--server.port=18089','--cloudnest.maintenance=true','--cloudnest.action=audit','--cloudnest.data-dir='+str(restored),'--spring.datasource.url=jdbc:h2:file:'+str(restored/'cloudnest').replace('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    output=audit.stdout.decode('utf-8',errors='replace')
    (WORK/'audit.log').write_text(output,encoding='utf-8')
    result=json.loads(next(line.split('=',1)[1] for line in output.splitlines() if line.startswith('CLOUDNEST_MAINTENANCE_RESULT=')))
    assert audit.returncode==0 and result['problems']==0,result
    record('L05','离线维护核对配额与 1 GiB 对象摘要，无差异退出 0',{'exitCode':audit.returncode,'problems':result['problems'],'records':result['records']},started)
except Exception as error:
    results.append(dict(id='FAILURE',status='FAIL',error=str(error)))
    REPORT.write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    raise
finally:
    if process is not None: stop(process,log)

