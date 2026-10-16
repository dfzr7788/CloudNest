"""Isolated real-process checks for offline administration and closed registration.
Credentials and cookies are generated in memory and are never included in evidence.
"""
import hashlib,http.cookiejar,json,os,secrets,shutil,subprocess,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.tools'/('operator-'+str(int(time.time())))
WORK.mkdir(parents=True)
JAR=WORK/'server.jar';shutil.copyfile(ROOT/'cloudnest-server/target/cloudnest-server-1.0.0.jar',JAR)
JAVA=os.environ.get('JAVA_EXE','java');BASE='http://127.0.0.1:18089'
ARGS=[JAVA,'-jar',str(JAR),'--cloudnest.data-dir='+str(WORK/'data'),'--spring.datasource.url=jdbc:h2:file:'+str(WORK/'data/cloudnest').replace('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0','--cloudnest.registration-open=false']
results=[]
class Client:
 def __init__(self):self.http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(self,method,path,body=None):
  headers={}
  if method!='GET':headers['X-XSRF-TOKEN']=self.call('GET','/api/auth/csrf')['token']
  if body is not None:headers['Content-Type']='application/octet-stream' if isinstance(body,bytes) else 'application/json'
  req=urllib.request.Request(BASE+path,data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else (None if method=='GET' else b''),headers=headers,method=method)
  with self.http.open(req,timeout=30)as response:
   content=response.read();return json.loads(content)if content else None
 def download(self,path):
  with self.http.open(BASE+path,timeout=30)as response:return response.read()
def expected_error(client,method,path,body,status):
 try:client.call(method,path,body)
 except urllib.error.HTTPError as error:assert error.code==status,(error.code,status);return
 raise AssertionError('Expected error '+str(status))
def maintenance(action,password):
 env=dict(os.environ,CN_OPERATOR_PASSWORD=password)
 out=subprocess.run(ARGS+['--server.port=0','--cloudnest.maintenance=true','--cloudnest.action='+action,'--cloudnest.operator-username=operator'],env=env,cwd=ROOT,capture_output=True,timeout=60)
 (WORK/(action+'.log')).write_bytes(out.stdout+out.stderr)
 assert out.returncode==0,action+' failed; see isolated log'
 assert b'CLOUDNEST_MAINTENANCE_RESULT='in out.stdout
def start(tag,restored=False):
 args=[arg.replace(str(WORK/'data'),str(WORK/'restored')).replace(str(WORK/'data').replace('\\','/'),str(WORK/'restored').replace('\\','/')) if restored else arg for arg in ARGS]
 log=open(WORK/(tag+'.log'),'wb');process=subprocess.Popen(args+['--server.port=18089'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
 for _ in range(100):
  try:
   if urllib.request.urlopen(BASE+'/actuator/health',timeout=1).status==200:return process,log
  except Exception:pass
  if process.poll()is not None:raise RuntimeError('Server exited; see '+tag+'.log')
  time.sleep(.2)
 process.terminate();process.wait(timeout=15);log.close();raise TimeoutError('Server startup timeout')
def stop(process,log):process.terminate();process.wait(timeout=20);log.close()
def record(case,expected,actual):results.append(dict(id=case,status='PASS',expected=expected,actual=actual));print(case,'PASS',flush=True)
first=secrets.token_urlsafe(18);second=secrets.token_urlsafe(18);process=None;log=None
try:
 maintenance('create-admin',first);process,log=start('first');admin=Client()
 admin.call('POST','/api/auth/login',dict(username='operator',password=first));assert admin.call('GET','/api/user/me')['administrator']
 expected_error(Client(),'POST','/api/auth/register',dict(username='blocked',password=first),403)
 invitation=admin.call('POST','/api/admin/invitations',dict(quota=1048576))['token'];invited=Client()
 user=invited.call('POST','/api/auth/register',dict(username='invited',password=first,invitation=invitation));assert user['quota']==1048576
 expected_error(Client(),'POST','/api/auth/register',dict(username='again',password=first,invitation=invitation),409)
 record('OP01','Offline admin login; public registration closed; invitation single use','Admin role verified; 403 closed registration; invited quota 1048576; reuse 409')
 stop(process,log);process=None;log=None;maintenance('reset-password',second);process,log=start('second')
 expected_error(admin,'GET','/api/user/me',None,401);expected_error(Client(),'POST','/api/auth/login',dict(username='operator',password=first),401)
 updated=Client();updated.call('POST','/api/auth/login',dict(username='operator',password=second))
 record('OP02','Offline reset revokes sessions and changes password','Old cookie 401; old password 401; new password 200 after restart')
 root=updated.call('GET','/api/user/me')['rootId']
 def upload(name,content):
  session=updated.call('POST','/api/uploads/init',dict(folderId=root,name=name,size=len(content),sha256=hashlib.sha256(content).hexdigest()))
  if session['status']!='COMPLETE':updated.call('PUT','/api/uploads/'+session['id']+'/parts/0',content);session=updated.call('POST','/api/uploads/'+session['id']+'/complete',{})
  return session['fileId']
 old=b'original-version';new=b'updated-version-content';file=upload('version.txt',old);source=upload('source.txt',new)
 token=updated.call('POST','/api/shares',dict(fileId=file,hours=1,allowDownload=True))['token'];updated.call('POST','/api/files/'+file+'/versions',dict(sourceId=source,expectedRevision=1))
 stop(process,log);process=None;log=None;shutil.copytree(WORK/'data',WORK/'restored');process,log=start('restored',True)
 assert updated.download('/api/files/'+file+'/download')==new
 version=updated.call('GET','/api/files/'+file+'/versions')['items'][0]['id']
 assert updated.download('/api/files/'+file+'/versions/'+version+'/download')==old
 assert Client().download('/api/public/shares/'+token+'/download')==old
 assert updated.call('GET','/api/user/me')['used']==len(old)+len(new)
 record('OP03','Restore preserves current content, historical content, fixed share and quota','Restored into new directory; both contents and fixed-share bytes match; logical quota matches all versions; old login cookie remains valid')
finally:
 if process is not None:stop(process,log)
 (ROOT/'docs/evidence/improvement-operator-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
