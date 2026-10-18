"""Export an importable OpenAPI contract for Apifox or other API clients."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
paths={}
def field(kind='string',**kw): return {'type':kind,**kw}
def obj(properties,required=None):
    schema={'type':'object','properties':properties}
    names=list(properties) if required is None else required
    if names: schema['required']=names
    return schema
def endpoint(method,path,summary,body=None,queries=None,public=False,binary=False,multipart=False):
    parameters=[]
    for name in ('id','token','type','number','version'):
        if '{'+name+'}' in path:
            parameters.append(dict(name=name,**{'in':'path'},required=True,schema=field('integer',minimum=0) if name=='number' else field()))
    for name,schema in (queries or {}).items(): parameters.append(dict(name=name,**{'in':'query'},schema=schema))
    if method not in ('get','head'):
        parameters.append(dict(name='X-XSRF-TOKEN',**{'in':'header'},required=True,description='GET /auth/csrf 后取得 token，并携带 XSRF-TOKEN Cookie',schema=field()))
    array_response=method=='get' and path in ('/folders','/shares','/trash','/uploads','/folders/{id}/breadcrumbs','/user/sessions','/user/activity','/admin/users')
    schema=field(format='binary') if binary else {'type':'array','items':{'type':'object'}} if array_response else {'type':'object'}
    success={'description':'成功；具体 JSON 结构见 docs/api.md','content':{'application/octet-stream' if binary else 'application/json':{'schema':schema}}}
    if method=='delete' or method=='put' or path=='/auth/logout':success={'description':'成功，空响应体'}
    responses={'200':success,'400':{'description':'参数无效'},'401':{'description':'未登录'},'403':{'description':'授权或 CSRF 校验失败'},'404':{'description':'资源不存在或不可访问'},'409':{'description':'名称、配额或状态冲突'},'429':{'description':'请求过于频繁'}}
    if method=='post' and path.endswith('/downloads'): responses['413']={'description':'递归展开数量或总大小超过打包上限'}
    if method=='get' and path.endswith('/preview'):
        success['description']='预览：Word 返回阅读 HTML 的 JSON，Markdown/文本返回 UTF-8 文本，其他支持类型返回原始内容'
        success['content']['application/json']={'schema':obj({'html':field(description='转义后的 Word 阅读内容；客户端仍须清理 HTML')})}
        success['content']['text/plain']={'schema':field()}
        responses.update({'413':{'description':'预览大小或复杂度超限'},'415':{'description':'不支持的预览类型'},'422':{'description':'文档损坏、已加密或无法解析'},'503':{'description':'文档预览繁忙'}})
    operation=dict(summary=summary,operationId=method+'_'+path.replace('/','_').replace('{','').replace('}',''),parameters=parameters,responses=responses,security=[] if public else [{'cookieAuth':[]}])
    if body: operation['requestBody']={'required':True,'content':{'multipart/form-data' if multipart else 'application/octet-stream' if binary and method=='put' else 'application/json':{'schema':body}}}
    paths.setdefault(path,{})[method]=operation

credentials=obj({'username':field(pattern='^[A-Za-z0-9_]{3,40}$'),'password':field(minLength=8,maxLength=64,format='password'),'invitation':field(maxLength=100)},['username','password'])
endpoint('get','/auth/csrf','取得 CSRF token',public=True)
for path in ('register','login'):endpoint('post','/auth/'+path,'注册' if path=='register' else '登录',credentials,public=True)
endpoint('post','/auth/logout','退出并撤销会话')
endpoint('get','/user/me','用户资料')
endpoint('patch','/user/me','修改显示名称',obj({'nickname':field(minLength=1,maxLength=80)}))
endpoint('get','/user/storage','已用/预留/总配额')
endpoint('get','/stats','有效文件和目录统计')
endpoint('get','/entries','统一文件列表、搜索、分页',queries={'parentId':field(),'q':field(),'scope':field(enum=['all','current']),'sort':field(enum=['name','size','updatedAt']),'direction':field(enum=['asc','desc']),'page':field('integer',minimum=1),'pageSize':field('integer',minimum=1,maximum=100)})
endpoint('get','/folders','子文件夹列表',queries={'parentId':field()})
endpoint('post','/folders','创建文件夹',obj({'name':field(),'parentId':field()},['name']))
endpoint('get','/folders/{id}/breadcrumbs','目录面包屑')
for resource in ('files','folders'):
    endpoint('patch','/'+resource+'/{id}','重命名或移动',obj({'name':field(),'parentId':field()},[]))
    endpoint('delete','/'+resource+'/{id}','移入回收站')
for action in ('preview','download'):endpoint('get','/files/{id}/'+action,'预览' if action=='preview' else '附件下载',binary=True)
endpoint('post','/files/upload','普通上传（100 MiB）',obj({'file':field(format='binary'),'folderId':field()},['file']),multipart=True)
endpoint('get','/trash','回收站批次根列表')
endpoint('post','/trash/{type}/{id}/restore','恢复 files 或 folders')
endpoint('delete','/trash/{type}/{id}','彻底删除 files 或 folders')
endpoint('post','/uploads/init','初始化分片或同用户秒传',obj({'folderId':field(),'name':field(),'size':field('integer',minimum=0,maximum=2147483648),'sha256':field(pattern='^[a-f0-9]{64}$')},['name','size','sha256']))
endpoint('get','/uploads','可续传会话')
endpoint('get','/uploads/{id}','分片状态')
endpoint('put','/uploads/{id}/parts/{number}','提交 0 起编号的分片',field(format='binary'),binary=True)
endpoint('post','/uploads/{id}/complete','校验并幂等完成')
endpoint('delete','/uploads/{id}','取消并释放预留')
endpoint('post','/shares','创建分享（原始 token 仅返回一次）',obj({'fileId':field(),'hours':field('integer',minimum=1,maximum=720),'code':field(maxLength=32),'allowDownload':field('boolean')},['fileId','hours','allowDownload']))
endpoint('get','/shares','我的分享列表')
endpoint('delete','/shares/{id}','撤销分享')
endpoint('get','/public/shares/{token}','分享锁定状态或文件资料',public=True)
endpoint('post','/public/shares/{token}/verify','验证提取码并写入访客 Cookie',obj({'code':field(maxLength=32)}),public=True)
for action in ('preview','download'):endpoint('get','/public/shares/{token}/'+action,'授权后的分享内容',public=True,binary=True)
endpoint('post','/downloads','创建文件或文件夹 ZIP 任务，保留目录树与空目录',obj({'fileIds':field('array',items=field(description='文件或文件夹ID'),minItems=1,maxItems=100)}))
endpoint('get','/downloads/{id}','打包状态')
endpoint('get','/downloads/{id}/file','授权下载 ZIP',binary=True)
endpoint('get','/downloads','当前用户下载任务列表（含文件ID和有效期）')
endpoint('post','/user/password','修改密码并撤销全部会话',obj({'oldPassword':field(format='password'),'newPassword':field(minLength=8,maxLength=64,format='password')}))
endpoint('get','/user/sessions','登录会话列表')
endpoint('delete','/user/sessions','退出其他会话')
endpoint('delete','/user/sessions/{id}','撤销指定会话')
endpoint('get','/user/activity','个人活动记录',queries={'page':field('integer',minimum=1)})
endpoint('get','/user/storage-breakdown','容量分项')
endpoint('get','/user/trash-policy','回收站保留天数')
endpoint('put','/user/trash-policy','设置回收站保留天数，0关闭',obj({'days':field('integer',minimum=0,maximum=365)}))
endpoint('patch','/entries/{id}/favorite','收藏/取消收藏',obj({'favorite':field('boolean')}))
endpoint('post','/entries/{id}/opened','记录拥有者成功预览')
endpoint('post','/entries/batch','批量移动/删除/恢复/彻删',obj({'ids':field('array',items=field(),minItems=1,maxItems=100),'action':field(enum=['move','trash','restore','purge']),'parentId':field()},['ids','action']))
endpoint('post','/folders/resolve-path','解析或创建相对目录',obj({'parentId':field(),'segments':field('array',items=field(),maxItems=32)},['segments']))
endpoint('post','/entries/upload-name','同名上传预检查',obj({'parentId':field(),'name':field(),'policy':field(enum=['skip','keep'])}))
endpoint('get','/files/{id}/versions','历史版本与当前修订号')
endpoint('post','/files/{id}/versions','把已上传的独立文件提交为新版本',obj({'sourceId':field(),'expectedRevision':field('integer',minimum=1)}))
endpoint('post','/files/{id}/versions/{version}/restore','交换当前与历史版本',obj({'expectedRevision':field('integer',minimum=1)}))
endpoint('delete','/files/{id}/versions/{version}','删除历史版本')
endpoint('get','/files/{id}/versions/{version}/download','下载历史版本',binary=True)
endpoint('get','/files/{id}/thumbnail','缩略图，生成中202',binary=True)
endpoint('patch','/shares/{id}','修改有效期/下载权限/提取码并撤销旧授权',obj({'hours':field('integer',minimum=1,maximum=720),'allowDownload':field('boolean'),'code':field(maxLength=32)},['hours','allowDownload']))
endpoint('get','/public/shares/{token}/entries','共享目录内容',queries={'parentId':field(),'page':field('integer',minimum=1)},public=True)
for action in ('preview','download'):endpoint('get','/public/shares/{token}/entries/{id}/'+action,'共享目录内文件'+action,public=True,binary=True)
endpoint('post','/public/shares/{token}/downloads','共享目录内文件或文件夹打包，逐项校验后代权限',obj({'fileIds':field('array',items=field(),minItems=1,maxItems=100)}),public=True)
endpoint('get','/public/shares/{token}/downloads/{id}','共享打包状态',public=True)
endpoint('get','/public/shares/{token}/downloads/{id}/file','共享打包下载',public=True,binary=True)
endpoint('get','/admin/users','管理员：用户列表',queries={'page':field('integer',minimum=1)})
endpoint('patch','/admin/users/{id}','管理员：配额与启用状态',obj({'quota':field('integer',minimum=0),'enabled':field('boolean')}))
endpoint('get','/admin/status','管理员：磁盘与任务概况')
endpoint('get','/admin/updates','管理员：更新服务、运行版本与任务状态')
endpoint('post','/admin/updates','管理员：检查或执行系统更新',obj({'action':field(enum=['check','update'])}))
endpoint('post','/admin/invitations','管理员：生成48小时一次性邀请',obj({'quota':field('integer',minimum=1048576)}))
paths['/entries']['get']['parameters'].extend([{'name':name,'in':'query','schema':schema}for name,schema in {'collection':field(enum=['all','favorites','opened','uploaded']),'type':field(enum=['all','image','document','folder']),'minSize':field('integer',minimum=0),'maxSize':field('integer',minimum=0),'after':field('integer',minimum=0),'before':field('integer',minimum=0)}.items()])
connection=obj({'name':field(minLength=1,maxLength=60),'baseUrl':field(maxLength=2048),'apiKey':field(format='password',maxLength=4096),'enabled':field('boolean')})
model=obj({'connectionId':field(),'name':field(maxLength=60),'modelId':field(maxLength=200),'contextLimit':field('integer',minimum=1024,maximum=2000000),'maxOutput':field('integer',minimum=64,maximum=2048),'outputParamMode':field(enum=['max_tokens','max_completion_tokens']),'enabled':field('boolean')})
endpoint('get','/ai/capabilities','AI 站点开关和支持范围')
for resource,schema in [('connections',connection),('models',model)]:
    endpoint('get','/ai/'+resource,'当前用户 AI '+resource)
    endpoint('post','/ai/'+resource,'保存 AI 配置，不外发请求',schema)
    patch=obj({**schema['properties'],'configVersion':field('integer',minimum=1)},['configVersion'])
    endpoint('patch','/ai/'+resource+'/{id}','修改本人配置，版本冲突返回409',patch)
    endpoint('delete','/ai/'+resource+'/{id}','删除本人配置并停止关联任务')
endpoint('delete','/ai/connections/{id}/credential','清除密钥并停用连接')
endpoint('get','/ai/preferences','默认聊天模型')
endpoint('put','/ai/preferences','设置本人默认模型',obj({'defaultChatModelId':field(nullable=True)}))
endpoint('post','/ai/consents','确认目的地的数据发送与费用',obj({'connectionId':field(),'destinationFingerprint':field(minLength=64,maxLength=64)}))
endpoint('post','/ai/models/{id}/test','显式测试聊天模型（可能收费），202')
endpoint('get','/ai/files/{id}/analysis','检查提取范围和预计调用数，不调用模型',queries={'modelId':field(),'rangeChars':field('integer',minimum=100,maximum=262144,default=120000)})
generation=obj({'modelId':field(),'expectedRevision':field('integer'),'rangeChars':field('integer',minimum=100,maximum=262144,default=120000),'allowPartial':field('boolean'),'language':field(enum=['zh','original']),'forceRefresh':field('boolean')},['modelId','expectedRevision'])
for action in ['summaries','tag-suggestions']:
    endpoint('post','/ai/files/{id}/'+action,'创建本人文件 AI 任务，202',generation)
endpoint('get','/ai/tasks','本人任务列表，每页20',queries={'fileId':field(),'page':field('integer',minimum=1)})
endpoint('get','/ai/tasks/{id}','AI 任务状态与经鉴权的结果，异步错误在JSON中表达')
endpoint('post','/ai/tasks/{id}/cancel','请求停止 AI 任务，202')
endpoint('delete','/ai/artifacts/{id}','清除本人终态任务结果；不删除源文件')
endpoint('get','/ai/usage','本人用量，缺少实际token为null',queries={'days':field('integer',minimum=1,maximum=90,default=30),'page':field('integer',minimum=1)})
for path in ['/ai/models/{id}/test','/ai/files/{id}/summaries','/ai/files/{id}/tag-suggestions']:
    op=paths[path]['post']
    op['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'description':'用户作用域，24小时去重，同键不同输入409','schema':field(minLength=1,maxLength=100)})
    op['responses']['202']=op['responses'].pop('200')
    op['responses']['202']['description']='任务已接受，含 taskId/status/pollAfterMs；缓存命中额外 cached=true'
paths['/ai/tasks/{id}/cancel']['post']['responses']['202']=paths['/ai/tasks/{id}/cancel']['post']['responses'].pop('200')
endpoint('get','/entries/{id}/tags','当前文件标签')
endpoint('put','/entries/{id}/tags','设置标签，可选乐观并发检查',obj({'tags':field('array',items=field(minLength=1,maxLength=40),maxItems=20),'expectedTags':field('array',items=field(maxLength=40),maxItems=20,description='可选；当前标签须与此前读取的数组一致，否则409')},['tags']))
for parameter in paths['/entries']['get']['parameters']:
    if parameter['name']=='sort': parameter['schema']['enum'].extend(['createdAt','lastOpened'])
spec={'openapi':'3.0.3','info':{'title':'CloudNest API','version':'1.0.0','description':'单实例私人云盘。完整请求与成功结果见 docs/api.md；导入后先执行 GET /auth/csrf，再填写当前 X-XSRF-TOKEN 并启用 Cookie jar。'},'servers':[{'url':'http://127.0.0.1:8080/api'}],'paths':paths,'components':{'securitySchemes':{'cookieAuth':{'type':'apiKey','in':'cookie','name':'CN_AUTH'}}}}
# Some older endpoints were documented directly in the contract; preserve those operations.
contract=ROOT/'docs/openapi.json'
if contract.exists():
    previous=json.loads(contract.read_text(encoding='utf-8'))
    merged=previous.get('paths',{})
    for path,operations in paths.items(): merged.setdefault(path,{}).update(operations)
    spec['paths']=merged
    spec['components']={**previous.get('components',{}),**spec['components']}
contract.write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Exported {sum(len(ops) for ops in paths.values())} operations')
