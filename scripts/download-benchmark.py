#!/usr/bin/env python3
"""Compare ordinary proxy and authorized downloads using a fixed, checksum-labelled dataset.
No URLs, signed queries or session cookies are written to the report.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import statistics
import time
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, urlopen


def run(base, cookie, cases, mode, concurrency, rounds):
    def transfer(index):
        item=cases[index%len(cases)]
        start=time.perf_counter()
        try:
            proxy=base.rstrip('/')+'/api/files/'+item['id']+'/download'
            target=proxy
            headers={'Cookie':cookie}
            transport='proxy'
            if mode=='authorized':
                with urlopen(Request(proxy+'-authorization',headers=headers),timeout=30) as response:
                    authorization=json.load(response)
                target=urljoin(base,authorization['url'])
                transport=authorization['mode']
                if transport=='direct': headers={}
            if urlsplit(target).scheme not in ('http','https'): raise ValueError('Invalid transport')
            with urlopen(Request(target,headers=headers),timeout=120) as response:
                first=response.read(1)
                ttfb=time.perf_counter()-start
                digest=hashlib.sha256(first); size=len(first)
                for block in iter(lambda:response.read(1024*1024),b''):
                    digest.update(block);size+=len(block)
            valid=size==item['size'] and digest.hexdigest()==item['sha256']
            return {'ok':valid,'bytes':size,'ttfbMs':ttfb*1000,'transport':transport}
        except Exception:
            return {'ok':False,'bytes':0,'ttfbMs':None}
    start=time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        rows=list(pool.map(transfer,range(concurrency*rounds)))
    elapsed=time.perf_counter()-start
    times=sorted(r['ttfbMs'] for r in rows if r['ok'])
    return {'mode':mode,'concurrency':concurrency,'requests':len(rows),'successes':len(times),
            'successRate':len(times)/len(rows),'elapsedSeconds':elapsed,
            'transports':{name:sum(r.get('transport')==name for r in rows) for name in ('direct','proxy')},
            'verifiedBytes':sum(r['bytes'] for r in rows if r['ok']),
            'bytesPerSecond':sum(r['bytes'] for r in rows if r['ok'])/elapsed,
            'ttfbP50Ms':statistics.median(times) if times else None,
            'ttfbP95Ms':times[min(len(times)-1,int(len(times)*0.95))] if times else None}


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--base',required=True)
    parser.add_argument('--cookie-file',type=Path,required=True,help='Private JSON {name: value} file')
    parser.add_argument('--manifest',type=Path,required=True,help='JSON [{id,size,sha256}] fixed dataset')
    parser.add_argument('--rounds',type=int,default=1)
    parser.add_argument('--environment',required=True,help='Label such as loopback-s3-fixture or real-garage')
    args=parser.parse_args()
    if not 1<=args.rounds<=100: raise ValueError('Rounds must be 1..100')
    cookies=json.loads(args.cookie_file.read_text(encoding='utf-8'))
    cookie='; '.join(k+'='+v for k,v in cookies.items())
    cases=json.loads(args.manifest.read_text(encoding='utf-8'))
    if not cases: raise ValueError('Dataset must not be empty')
    results=[run(args.base,cookie,cases,mode,n,args.rounds)
             for mode in ('proxy','authorized') for n in (5,10,20,30)]
    print(json.dumps({'environment':args.environment,'fileCount':len(cases),'results':results},indent=2))
    raise SystemExit(0 if all(row['successRate']==1 for row in results) else 2)
