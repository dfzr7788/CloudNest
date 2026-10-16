"""Small-dataset real HTTP pagination/latency sample; not a production load test."""
from pathlib import Path
import datetime, http.cookiejar, json, statistics, time, urllib.request

root=Path(__file__).resolve().parents[1]
jar=http.cookiejar.CookieJar()
http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
base='http://127.0.0.1:8080/api'
def call(method,path,body=None,raw=None,content_type=None):
    headers={}
    if method!='GET':headers['X-XSRF-TOKEN']=call('GET','/auth/csrf')['token']
    if raw is not None:data=raw;headers['Content-Type']=content_type
    elif body is not None:data=json.dumps(body).encode();headers['Content-Type']='application/json'
    else:data=None if method=='GET' else b''
    with http.open(urllib.request.Request(base+path,data=data,headers=headers,method=method),timeout=60) as r:
        data=r.read()
        return json.loads(data) if data else None
user=call('POST','/auth/register',dict(username='smoke_'+str(int(time.time())),password='Smoke-test-123'))
folder=call('POST','/folders',dict(name='HTTP benchmark',parentId=user['rootId']))['id']
for i in range(35):call('POST','/folders',dict(name=f'folder-{i:02}',parentId=folder))
boundary='CloudNestSmokeBoundary'
raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="folderId"\r\n\r\n{folder}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.txt"\r\nContent-Type: text/plain\r\n\r\nreal HTTP sample\r\n--{boundary}--\r\n').encode()
call('POST','/files/upload',raw=raw,content_type='multipart/form-data; boundary='+boundary)
latency=[]
for _ in range(10):
    started=time.perf_counter();listing=call('GET',f'/entries?parentId={folder}&pageSize=30');latency.append((time.perf_counter()-started)*1000)
    assert listing['total']==36 and len(listing['items'])==30
second=call('GET',f'/entries?parentId={folder}&page=2&pageSize=30')
assert len(second['items'])==6
result=dict(id='PERF01',status='PASS',environment='Real HTTP / local H2 / small dataset',expected='36 项内容正确分页为 30+6；记录 10 次列表响应与临时分片占用',actual=dict(datasetEntries=36,page1=30,page2=6,requests=10,medianMs=round(statistics.median(latency),3),p95Ms=round(sorted(latency)[-1],3),temporaryPartBytes=sum(p.stat().st_size for p in (root/'cloudnest-server/.data/parts').rglob('*') if p.is_file())),limits='仅本机小数据集样本，不是并发压力测试或生产 SLA')
call('DELETE','/folders/'+folder)
call('DELETE','/trash/folders/'+folder)
(root/'docs/evidence/http-smoke-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
