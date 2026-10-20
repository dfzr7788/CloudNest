# Horizon Next API

API 前缀 `/api`。除节点心跳外，使用现有登录 Cookie，写操作保留 XSRF Cookie 并发送 `X-XSRF-TOKEN`。字段采用以下实际约定：请求用 camelCase；新持久化元数据/任务的响应沿用数据库的 snake_case。个人文件视图与上传会话继续使用既有 camelCase 契约。所有 ID 都是服务端分配的非路径标识。

## 快照与任务

个人接口前缀 `/resilience`；团队接口前缀 `/teams/{team}/resilience`，团队读写快照均要求 Owner/Admin。`folderId` 缺省是归属账户根目录；历史快照即使源目录删除仍可按归属访问。

| 方法和路径 | 请求与返回 |
| --- | --- |
| GET `/snapshots?folderId=` | 返回快照数组：`id,user_id,folder_id,name,created_at,total_bytes,entry_count`；不传目录返回所有历史目录（最多 500） |
| POST `/snapshots` | `{folderId?,name?}`；返回上述快照记录；在归属账户锁内创建，不完整结果回滚 |
| GET `/snapshots/{id}/entries` | 数组：`snapshot_id,entry_id,parent_entry_id,ordinal,kind,name,blob_id,size,mime,revision`，按父先子的 ordinal 排序 |
| GET `/snapshots/{id}/diff?against=` | `{changes:[{entryId,name,kind}],restoreMode:'COPY',preservesCurrentFiles:true}`；kind 为 ADDED/MODIFIED/DELETED/MOVED；against 只能是相同目录的另一个自有快照，缺省比较当前状态 |
| DELETE `/snapshots/{id}` | 释放快照引用和逻辑配额；可恢复任务仍在引用时返回 409 |
| POST `/snapshots/{id}/preview` | `{targetFolder?,selectedEntry?}`；返回 `snapshotId,targetFolder,entries,totalBytes,mode:'COPY',preservesCurrentFiles:true`；不会创建任务或改写文件 |
| POST `/snapshots/{id}/restore` | 在 preview 请求基础上增加 `requestKey`（8–80 个字母、数字、下划线或连字符）；返回 `resilience_task`；相同内容/标识重放返回同任务，标识复用于其他内容返回 409 |
| GET `/policies` | 当前归属账户的目录策略数组 |
| PUT `/policies` | `{folderId,enabled,intervalHours:1..8760,keepCount:1..365,protection:'NORMAL'|'IMPORTANT'|'ARCHIVE'}` |
| GET `/tasks` | 最多 200 条任务；字段 `id,user_id,kind,request_key,request_scope,status,progress,total,attempts,error_code,created_at,updated_at,restored_root` |
| POST `/tasks/{id}/{action}` | action 为 pause/resume/retry/cancel；不接受任意命令；按当前状态转换 |

个人历史文件另有 GET `/resilience/snapshots/{id}/entries/{entry}/download`，支持既有 Range 下载。团队历史内容通过管理员恢复副本读取，未开放绕过角色的公开历史链接。

系统管理员接口：

- POST `/resilience/integrity`：`{requestKey,backend?,ownerId?,folderId?}`；指定 folderId 时必须提供 ownerId。返回任务记录；标识绑定筛选范围。
- GET `/resilience/integrity/{id}`：仅任务创建管理员可读，返回每个物理对象的 `ordinal,blob_id,backend,object_key,status,error_code,repair_task,checked_at`。结果状态包括 VERIFIED/CORRUPT/UNAVAILABLE/SKIPPED；任务 COMPLETE 表示批次处理完毕，不代表没有损坏。
- GET/PUT `/resilience/integrity-policy`：请求 `{enabled,intervalHours:1..8760,bytesPerSecond:65536..1073741824}`；响应使用 `interval_hours,bytes_per_second,next_run,administrator_id,last_error`。执行前再次检查管理员身份。
- GET `/resilience/tiering`：`{suggestions:[{file,reason,candidates,requiresApproval:true}],automaticMigration:false,independentBackupRequired:true}`。不提交迁移；该管理员界面可见文件名，Agent 的候选工具只返回对象句柄。
- PUT `/resilience/nodes/{backend}/capabilities`：`{publicUpload,supportsRange,performanceClass:'FAST'|'STANDARD'|'CAPACITY'}`；publicUpload 只允许已启用 HTTPS 公开地址的 S3 节点，默认关闭。

## 团队

| 方法和路径 | 契约 |
| --- | --- |
| GET `/teams` | 仅当前成员所属团队：`id,name,owner_id,created_at,file_role,contributor,root_id,storage_used,storage_quota,storage_reserved` |
| POST `/teams` | `{name,quota}`，quota 为字节，从所有者个人可用配额划拨；返回 `{id,rootId,name}` |
| PUT `/teams/{team}/quota` | Owner；`{bytes}`，调整双方总配额分配 |
| GET `/teams/{team}/members` | 团队成员；数组含 `user_id,file_role,contributor,nickname,username` |
| PUT `/teams/{team}/members/{user}` | Owner/Admin；`{role,contributor}`；只有 Owner 可授予/修改 Admin；Owner 只允许自己切换 contributor，不能变更 OWNER 角色 |
| DELETE `/teams/{team}/members/{user}` | Owner/Admin；存在已接入贡献时要求先停用；不能移除 Owner |
| POST `/teams/{team}/invitations` | Owner/Admin；`{role,contributor,hours:1..168}`；返回 `{id,token,expiresAt}`，仅本次显示明文令牌 |
| GET `/teams/{team}/invitations` | Owner/Admin；仅元数据，不返回令牌 |
| DELETE `/teams/{team}/invitations/{id}` | Owner/Admin；撤销未使用邀请 |
| POST `/teams/accept` | 登录成员；`{token}`；消费有效单次邀请，返回 `{id:teamId}`；不重复提升已有成员权限 |
| GET `/teams/{team}/audit` | Owner/Admin；最新 200 项审计 |
| PUT `/teams/{team}/entries/{id}/acl` | Owner/Admin；`{userId,permission:'INHERIT'|'READ'|'WRITE'|'DENY'}` |
| GET `/teams/{team}/entries/{id}/acl` | Owner/Admin；该资源显式 ACL 数组 |
| GET `/teams/{team}/files?parentId=&page=1` | Viewer+ 并通过目录 ACL；过滤后分页，100 项/页，返回 `{items,total,parentId,breadcrumbs}`；items 使用既有文件视图 |
| GET `/teams/{team}/folders?parentId=` | Viewer+；返回授权子目录 `{items,breadcrumbs}`，用于移动选择器 |
| POST `/teams/{team}/folders` | Editor+；`{parentId?,name}`；返回文件夹视图 |
| PATCH `/teams/{team}/entries/{id}` | Editor+；`{name?,parentId?}`；检查源子树和目标写入权限 |
| DELETE `/teams/{team}/entries/{id}` | Editor+；移入回收站，检查整个子树 |
| GET `/teams/{team}/files/{id}/download` | Viewer+ 与 ACL；既有 Range/内容响应，不接受内部资源账户身份作为授权依据 |
| GET `/teams/{team}/trash` | Owner/Admin；团队删除根数组 |
| POST `/teams/{team}/trash/{id}/restore` | Owner/Admin；复用既有冲突保留恢复 |
| DELETE `/teams/{team}/trash/{id}` | Owner/Admin；永久删除逻辑资源；快照引用仍保护内容 |

团队上传前缀 `/teams/{team}/uploads`，请求和响应与个人分片协议一致：POST `/init` 为 `{folderId?,name,size,sha256,targetId?,expectedRevision?}`；GET `/{id}` 查询；PUT `/{id}/parts/{number}` 上传二进制；POST `/{id}/complete` 提交；DELETE `/{id}` 取消。每一步要求发起成员仍为 Editor+ 且满足当前 ACL。分片暂停后使用相同会话和文件继续，跨浏览器重启后可用相同 init 内容重新取得服务端会话；不自动保存浏览器的本地 File 句柄。

团队存储前缀 `/teams/{team}/storage`，Owner/Admin 可 GET `/targets` 查看有资格的全局及本团队贡献节点；POST `/jobs` 接受 `{target,kind:'MIGRATE'|'REPLICATE',fileIds:[最多200项]}`，返回 `{ids}`；GET `/jobs` 返回该团队任务；POST `/jobs/{id}/retry`、DELETE `/jobs/{id}` 复用原有迁移重试/取消。不得选择其他团队贡献节点。

## 贡献节点

- POST `/teams/{team}/contributions`：独立 Contributor 权限；`{name,endpoint,nodeToken,capacityBytes}`。nodeToken 为 32–128 字符节点访问令牌，程序生成的随机 URL-safe 值；capacityBytes 至少 1 MiB。HTTP 只允许受控私网字面地址或 Tailscale 域名；其他地址需 HTTPS。登记不发起网络探测。返回 `{id,heartbeatToken,status:'PENDING'}`，心跳令牌仅显示一次。
- GET 同路径：当前团队贡献元数据，不返回 endpoint 凭据或令牌。
- POST `/teams/{team}/contributions/{id}/review`：Owner/Admin，将 PENDING 变为 REVIEWED。
- GET `/admin/contributions`：系统管理员查看地址与待接入记录，不返回密钥。
- POST `/admin/contributions/{id}/approve`：系统管理员；`{failureDomain,reliability:0..100,performanceClass}`。检查 Contributor 仍有效，执行认证读写探测，成功后返回 `{backend,status:'APPROVED'}`；失败保留待验证状态。接入后仍需要心跳及健康检测。
- POST `/teams/{team}/contributions/{id}/stop`：贡献者本人或 Owner/Admin；停止新分配，保留对象和配额记录。
- POST `/public/nodes/{id}/heartbeat`：唯一机器接口，使用 `X-Node-Token`，不使用 Cookie/CSRF。仅更新本节点心跳，返回 `{accepted:true}`；不能操作文件或读数据库。

## 直传原型

POST `/uploads/{id}/plan` 仅接受自有活动上传会话。默认结果 `{mode:'proxy',sessionId,supportsResume:true,partSize,reason:'DIRECT_UNAVAILABLE'}`。符合管理员配置与大小限制时返回 `{mode:'direct-post',url,fields,expiresAt,sessionId,fallback,requiresServerCommit:true}`；客户端以 multipart/form-data 提交 fields，最后添加 file。签名不可缓存或记录。

POST `/uploads/{id}/direct-commit` 无 JSON 请求体；仅在暂存内容通过大小和 SHA-256 校验后导入现有发布流程，返回既有 UploadSession。完成后重复调用返回相同 fileId。校验失败不记录坏分片，客户端可以回到原会话代理上传。

普通下载授权新增长期兼容的可选字段 `fallbackUrl,supportsRange`；其余 `mode,url,expiresAt` 继续兼容。
