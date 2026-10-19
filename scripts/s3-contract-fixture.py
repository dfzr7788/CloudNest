#!/usr/bin/env python3
"""Loopback-only, in-memory S3 fixture for browser tests. NOT Garage or a deployable store.
Public GETs validate SigV4; internal requests support the SDK's small-object contract.
"""
import datetime
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from urllib.parse import parse_qsl, quote, unquote, urlsplit

ACCESS = 'fixture-access'
SECRET = 'fixture-secret-key-for-browser-tests'
objects = {}
stats = {'internalBytes':0, 'publicBytes':0}
lock = threading.Lock()


def valid_signature(method, path, host):
    uri = urlsplit(path)
    query = dict(parse_qsl(uri.query, keep_blank_values=True))
    try:
        credential = query['X-Amz-Credential'].split('/')
        stamp = query['X-Amz-Date']
        date = datetime.datetime.strptime(stamp, '%Y%m%dT%H%M%SZ').replace(tzinfo=datetime.timezone.utc)
        age = (datetime.datetime.now(datetime.timezone.utc)-date).total_seconds()
        if (method != 'GET' or query['X-Amz-Algorithm'] != 'AWS4-HMAC-SHA256'
                or credential[0] != ACCESS or credential[2:] != ['garage','s3','aws4_request']
                or query['X-Amz-SignedHeaders'] != 'host'
                or not -1 <= age <= int(query['X-Amz-Expires'])):
            return False
        encoded = lambda value: quote(value, safe='-_.~')
        params = '&'.join(encoded(k)+'='+encoded(v) for k,v in sorted(query.items()) if k!='X-Amz-Signature')
        canonical = '\n'.join([method,quote(unquote(uri.path),safe='/-_.~'),params,
                               'host:'+host+'\n','host','UNSIGNED-PAYLOAD'])
        scope = '/'.join(credential[1:])
        message = '\n'.join(['AWS4-HMAC-SHA256',stamp,scope,hashlib.sha256(canonical.encode()).hexdigest()])
        key = ('AWS4'+SECRET).encode()
        for segment in credential[1:]:
            key = hmac.new(key,segment.encode(),hashlib.sha256).digest()
        expected = hmac.new(key,message.encode(),hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected,query['X-Amz-Signature'])
    except (KeyError,ValueError,IndexError):
        return False


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self,*args):
        pass  # Never log signed query strings.
    def reply(self,status,body=b'',headers=None):
        self.close_connection=True
        self.send_response(status)
        self.send_header('Connection','close')
        for key,value in (headers or {}).items():
            self.send_header(key,value)
        self.send_header('Content-Length',str(len(body)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)
    def handle_request(self):
        uri=urlsplit(self.path)
        public=self.server.server_address[1]==18093
        if uri.path=='/__stats':
            return self.reply(200,json.dumps(stats).encode(),{'Content-Type':'application/json'})
        if public:
            if self.command not in ('GET','HEAD'):
                return self.reply(405)
            if not valid_signature(self.command,self.path,self.headers['Host']):
                return self.reply(403,b'<Error><Code>AccessDenied</Code></Error>')
        elif not self.headers.get('Authorization','').startswith('AWS4-HMAC-SHA256 '):
            return self.reply(403)
        key=uri.path
        if self.command=='HEAD' and key=='/cloudnest':
            return self.reply(200)
        if self.command=='GET' and 'location' in dict(parse_qsl(uri.query,keep_blank_values=True)):
            return self.reply(200,b'<LocationConstraint>garage</LocationConstraint>',{'Content-Type':'application/xml'})
        if self.command=='PUT':
            body=self.rfile.read(int(self.headers['Content-Length']))
            with lock: objects[key]=body
            return self.reply(200,headers={'ETag':'"'+hashlib.md5(body).hexdigest()+'"'})
        if self.command=='DELETE':
            with lock: objects.pop(key,None)
            return self.reply(204)
        with lock: body=objects.get(key)
        if body is None:
            return self.reply(404,b'<Error><Code>NoSuchKey</Code></Error>',{'Content-Type':'application/xml'})
        headers={'Content-Type':'application/octet-stream','Last-Modified':'Wed, 01 Jan 2025 00:00:00 GMT',
                 'ETag':'"fixture"','Accept-Ranges':'bytes','Cache-Control':'private, no-store'}
        query=dict(parse_qsl(uri.query))
        if 'response-content-disposition' in query:
            headers['Content-Disposition']=query['response-content-disposition']
        status=200
        if self.headers.get('Range'):
            bounds=self.headers['Range'].removeprefix('bytes=').split('-')
            start=int(bounds[0]);end=min(len(body)-1,int(bounds[1]) if bounds[1] else len(body)-1)
            if start>=len(body):return self.reply(416)
            headers['Content-Range']=f'bytes {start}-{end}/{len(body)}'
            body=body[start:end+1];status=206
        if self.command=='GET':
            with lock: stats['publicBytes' if public else 'internalBytes']+=len(body)
        self.reply(status,body,headers)
    do_GET=handle_request
    do_HEAD=handle_request
    do_PUT=handle_request
    do_DELETE=handle_request
    do_POST=handle_request


if __name__=='__main__':
    servers=[ThreadingHTTPServer(('127.0.0.1',port),Handler) for port in (18092,18093)]
    for server in servers:
        threading.Thread(target=server.serve_forever,daemon=True).start()
    print('Loopback S3 contract fixture ready (not Garage)',flush=True)
    threading.Event().wait()
