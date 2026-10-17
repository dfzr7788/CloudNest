# API 契约

所有路径以 `/api` 开头。JSON 字段使用 camelCase；条目 ID 为 UUID 字符串。时间为 Unix 毫秒；容量/长度为字节；分页从 1 开始。成功返回 JSON 或空响应；文件返回二进制流。错误：`{"code":"CONFLICT","message":"目标目录已存在同名文件"}`。

## 认证与 CSRF

浏览器先 GET `/auth/csrf`，取得 `{token,headerName}` 和 XSRF-TOKEN Cookie。所有写请求将当前 token 放在 X-XSRF-TOKEN，携带 Cookie。登录后 token 可能轮换，应重新获取。认证 Cookie CN_AUTH 为 HttpOnly，仅用于 `/api`；HTTPS 部署启用 Secure，普通 HTTP 部署须设置 SECURE_COOKIES=false。

| 方法与路径 | 输入 | 成功结果/主要失败 |
| --- | --- | --- |
| POST /auth/register | username、password | 用户资料 + CN_AUTH；重复 409，格式不符 400，频率 429 |
| POST /auth/login | username、password | 用户资料 + CN_AUTH；凭据错误 401 |
| POST /auth/logout | 无 | 空响应，撤销当前会话并清 Cookie |
| GET /user/me | 无 | id、username、nickname、rootId、quota、used、reserved |
| PATCH /user/me | nickname，1～80 字符 | 更新后的资料 |
| GET /user/storage | 无 | quota、used、reserved |
| GET /stats | 无 | files、folders（不含根）、images、documents（PDF/文本） |

注册/登录要求密码 8～64 字符且 UTF-8 不超过 72 字节。资料不包含密码摘要或会话 token。未认证受保护接口 401，CSRF 缺失/错误 403。

## 文件与目录

条目：`{id,parentId,kind:"FILE"|"FOLDER",name,size,mime,createdAt,updatedAt,deletedAt,path?}`。

| 方法与路径 | 输入 | 成功结果 |
| --- | --- | --- |
| GET /entries | parentId 默认根；q；scope=all/current；sort=name/size/updatedAt；direction=asc/desc；page；pageSize=1～100 | `{items,total,page,pageSize,parentId}`；搜索时返回父目录 path |
| GET /folders | parentId 默认根 | 当前目录的全部有效子文件夹，用于移动选择器 |
| POST /folders | name、parentId 可选 | 新条目 |
| GET /folders/{id}/breadcrumbs | 目录 ID | 从根到目标的 `{id,name}[]` |
| PATCH /folders/{id} | name 或 parentId | 修改后的目录 |
| PATCH /files/{id} | name 或 parentId | 修改后的文件 |
| DELETE /folders/{id} | 无 | 软删除有效子树 |
| DELETE /files/{id} | 无 | 软删除文件 |
| GET /files/{id}/download | 可选 Range: bytes=start-end/-suffix | 附件；支持 200/206/416 |
| GET /files/{id}/preview | 同上 | 白名单 MIME 内联；不支持 415，文本超限 413 |
| POST /files/upload | multipart: file、folderId 可选 | 完成的上传会话，含 fileId；单次最大 100 MiB |

搜索默认用户全部未删除条目，`scope=current` 仅当前目录。排序优先目录，再按指定字段和稳定 ID。根目录不可修改删除；禁止把目录移入自己/后代。名称冲突 409；无权限资源统一 404。

## 回收站

- GET `/trash`：删除批次根条目数组，避免同批子项重复显示。
- POST `/trash/{files|folders}/{id}/restore`：返回条目及 restoredToRoot；重名会改名。
- DELETE `/trash/{files|folders}/{id}`：彻底删除；目录包括全部已删除后代；释放逻辑空间，后台清理底层对象。

## 分片上传

1. POST `/uploads/init`：`{folderId?,name,size,sha256}`。SHA-256 为 64 位小写十六进制。
2. 返回 `{id,name,size,sha256,status,folderId,partSize,partCount,uploadedParts,fileId,expiresAt}`。status=COMPLETE 表示同用户秒传完成。
3. PUT `/uploads/{id}/parts/{number}`：application/octet-stream，编号从 0 开始；按返回的 partSize 切片，最后一片为剩余长度；空文件仍提交第 0 个空片。
4. GET `/uploads/{id}`：查询同一结构，用 uploadedParts 跳过已有片。
5. POST `/uploads/{id}/complete`：核验完整性并发布，重复完成返回原 fileId。
6. DELETE `/uploads/{id}`：取消，幂等释放预留。
7. GET `/uploads`：当前用户可继续的上传会话数组。

同一用户、目标、名称、大小、摘要匹配的进行中会话会复用。每片要求长度完全匹配；重复片相同内容成功，不同内容 409。缺片 409、摘要失败 422、超限 413、空间不足 409/QUOTA_EXCEEDED、临时盘不足 507。前端重选文件后先验证大小与摘要。取消完成后的会话不会删除已发布文件。

## 分享

- POST `/shares`：`{fileId,hours:1..720,code?:string,allowDownload:boolean}`。提取码可空，否则 4～32 字符且 UTF-8 ≤72 字节。返回 `{id,token,path,expiresAt,name}`；原始 token 仅此时返回。
- GET `/shares`：当前用户列表 `{id,fileId,name,expiresAt,allowDownload,revoked,hasCode}[]`。
- DELETE `/shares/{id}`：撤销。
- GET `/public/shares/{token}`：未验证时仅 `{locked:true,expiresAt}`；已获授权时返回 `{locked:false,file,allowDownload,expiresAt}`。
- POST `/public/shares/{token}/verify`：`{code}`，成功写入短期 HttpOnly 授权 Cookie。
- GET `/public/shares/{token}/preview` 或 `/download`：每次重新检查授权与期限；过期/撤销 410，提取码错误/未验证/禁止下载 403。

## 批量下载

- POST `/downloads`：`{fileIds:string[]}`，兼容字段名 fileIds，现在允许文件与文件夹 ID。选择 1～100 项，递归展开最多 1000 文件、2000 个文件与目录、64 层、512 MiB。返回 `{id}`。父子项同时选择自动去重，保留目录树及空目录；同名或不适合解压的名称自动处理。
- GET `/downloads/{id}`：`{id,status:QUEUED|BUILDING|READY|FAILED,error}`。
- GET `/downloads/{id}/file`：READY 状态 ZIP；重新校验全部源文件授权与有效状态。

只支持文件，目录需先展开选择；重名 ZIP 项自动加前缀。每用户并行任务 ≤2，保留任务 ≤10，过期 1 小时。
# 改进版接口补充（2026-10-16）

以下接口均沿用现有 Cookie 身份与写请求 CSRF 校验。完整可导入清单见 openapi.json；本节只补充增量语义。普通认证请求保持 CSRF Cookie 稳定，登录/注册/退出时清除旧令牌，下一次写入前重新获取。

| 接口 | 输入与成功结果 |
| --- | --- |
| POST /api/user/password | oldPassword、newPassword；新密码 8～64 字符、UTF-8 最多 72 字节；成功撤销全部会话，需重新登录 |
| GET /api/user/sessions | 返回当前用户有效会话的 id、created_at、last_seen、client_label、expires_at 和 current；id 仅作管理标识，不是登录凭证 |
| DELETE /api/user/sessions | 撤销其他会话，保留当前会话 |
| DELETE /api/user/sessions/{id} | 只可撤销自己的指定会话；他人或不存在的会话 404 |
| GET /api/user/activity?page=1 | 每页 50 条活动，成功操作与访问拒绝分开记录；时间倒序，保留 90 天；拒绝事件不保存目标路径或凭证 |
| GET /api/user/storage-breakdown | live、trash、versions、reserved、quota；前三项之和为已用逻辑容量 |
| GET/PUT /api/user/trash-policy | GET 返回 days；PUT 输入 days=0～365，0 关闭自动清理 |
| GET /api/entries | 新增 collection=all/favorites/opened/uploaded、type=all/image/document/folder、minSize/maxSize（字节）、after/before（修改时间毫秒）；排序增加 createdAt/lastOpened |
| PATCH /api/entries/{id}/favorite | favorite 布尔值；归属与有效状态都必须通过 |
| POST /api/entries/{id}/opened | 拥有者预览成功后记录时间，分享访客不调用 |
| POST /api/entries/batch | ids 最多 100；action=move/trash/restore/purge；move 需要 parentId；返回 results（id/status/message）、succeeded、failed。祖先成功才计子项 covered；逐项成功，不提供整批回滚 |
| POST /api/folders/resolve-path | parentId 和 segments 数组（最多 32）；返回最终目录 id，已存在目录复用；非法路径整笔回滚 |
| POST /api/entries/upload-name | parentId/name/policy=skip、keep或replace；返回 name、skipped；仅预检查，最终上传仍执行并发重名校验 |
| GET /api/files/{id}/versions | 返回 revision 和 items；历史 id/size/mime/created_at，不包含当前内容 |
| POST /api/files/{id}/versions | sourceId（同用户、有效独立文件）、expectedRevision；将来源内容提交为目标的新版本，原当前内容进入历史，来源独立条目消失；来源有历史或分享时拒绝；旧 revision 返回 409 |
| POST /api/files/{id}/versions/{version}/restore | expectedRevision；交换当前与所选历史内容，容量不变，revision 自增 |
| DELETE /api/files/{id}/versions/{version} | 永久清理历史并扣减对应逻辑容量；固定分享引用仍保留真实对象 |
| GET /api/files/{id}/versions/{version}/download | 拥有者鉴权下载历史内容，支持同一 Range 规则 |
| GET /api/files/{id}/thumbnail | 成功 image/png；生成中 202+Retry-After；格式/大小不支持 415；权限在读取缓存前检查 |
| PATCH /api/shares/{id} | hours=1～720、allowDownload、可选 code；不传 code 保留原提取码，空串移除；只可修改仍有效的自己的分享，同时撤销旧授权；失效分享需重建 |
| GET /api/public/shares/{token}/entries | parentId 可选，默认共享根；page 每页 50；必须为目录分享且目标位于子树 |
| GET /api/public/shares/{token}/entries/{id}/preview或download | 每次检查分享有效、提取码授权、子树成员及下载权限 |
| GET /api/downloads | 当前用户的 ZIP 任务列表，含 id/status/error/createdAt/expiresAt/fileIds，最多返回 20 项；其他用户不可见 |
| POST /api/public/shares/{token}/downloads | fileIds 最多 100，支持文件/文件夹，全部必须在共享子树；递归最多 1000 文件、2000 文件与目录、512 MiB；创建与取包均复核每个后代的共享范围 |
| GET /api/public/shares/{token}/downloads/{id} | 每次检查任务绑定的是该分享及来源仍在范围内 |
| GET /api/public/shares/{token}/downloads/{id}/file | 上述校验加上每个源文件 revision 检查；版本改变 409，分享撤销 410 |
| GET /api/admin/users?page=1 | 仅管理员，每页 50，返回账号、配额、占用、启用与管理员标记；不提供密码或文件内容 |
| PATCH /api/admin/users/{id} | quota、enabled；配额不能低于 used+reserved；禁用撤销会话；管理员账号禁用须走受控维护流程 |
| GET /api/admin/status | 管理员查看 freeBytes、users、pendingUploads、pendingDeletes |
| POST /api/admin/invitations | quota 至少 1 MiB；返回仅本次可见的 token 和 expiresAt（48 小时）；最多同时 20 个未消费有效邀请 |
| POST /api/auth/register | 新增可选 invitation。关闭公开注册时必须填写；邀请只使用一次，配额按邀请设置，数据库只保存摘要 |

历史版本和单文件固定分享会增加对象引用；普通文件删除、分享撤销、后台清理已同步适配。普通下载 ZIP 同样验证修订号。其他限制与旧接口定义继续适用。

## 系统更新接口

- `GET /api/admin/updates`：仅启用中的管理员。返回 enabled（30 秒内服务心跳）、pending、busy、phase、message、current/latest 提交摘要、available、backup 备份标识与 updatedAt。未配置宿主机服务时 enabled=false，不执行任何系统操作。
- `POST /api/admin/updates`：检查管理员和 CSRF，JSON 的 action 为 check、update 或 backup。只接受固定动作，不接受命令、仓库 URL、路径或版本参数。成功返回 accepted=true；未知动作 400、重复/执行中 409、服务不可用 503。
- 后台服务写入只读状态目录；应用只写请求队列。构建、备份、重启、健康检查与失败行为见 [更新说明](system-updates.md)。


## 文档预览

现有私有文件、固定分享与目录分享的 preview 接口增加 DOC/DOCX 解析，返回 `application/json`，结构为 `{html}`；下载接口仍返回原始文件。Word 限制为 10 MiB，OOXML 展开上限 32 MiB、4000 项，阅读 HTML 最多约 2M 字符，同时最多两次解析；损坏/加密文档 422、超限 413、繁忙 503。输出只包含转义后的文字与基本段落/表格结构，客户端另行清理 HTML。Word 权限与原预览接口一致，不要求开放下载权限。

Markdown 使用原 preview 接口返回 UTF-8 文本，最多 2 MiB，前端按扩展名或 Markdown MIME 识别并安全渲染。ZIP 接口及既有数量限制不变，前端跨目录保留最多 100 个入口，不扩大用户或分享权限范围。

## 进阶接口

所有私有写操作仍要求会话和 CSRF；管理员配置另检查管理员身份。完整参数以服务端输入校验为准。

| 接口 | 行为 |
| --- | --- |
| POST /api/entries/copy | ids≤100、parentId；事务复制≤10000节点并按逻辑配额计费，去重祖先覆盖项 |
| POST /api/uploads/init | 可选 targetId/expectedRevision，覆盖同名目标并保留历史；过期修订409 |
| GET/PUT /api/entries/{id}/tags | 当前有效拥有者；tags≤20，每项≤40字符 |
| GET /api/tags | 本人有效条目标签及数量 |
| GET /api/search | q/tag/content/page；每页50，返回items/total/pending，不搜索他人或回收站 |
| POST /api/search/reindex | 仅重建本人的正文索引 |
| GET /api/files/{id}/attachment?path= | 相对附件，拥有者和目录边界检查 |
| POST /api/downloads/estimate | fileIds；展开字节、数量、空间与canPack |
| DELETE /api/downloads/{id} | 取消本人的ZIP；状态含completedBytes/totalBytes |
| POST /api/shares/collection | fileIds/title/hours/code/allowDownload/collect；collect仅一个文件夹 |
| GET /api/shares/{id}/stats | 拥有者查看累计访问、下载请求、收集及最近事件 |
| POST /api/public/shares/{token}/collect | multipart file；只上传，最多100MiB，不提供目录读取 |
| GET /api/public/shares/{token}/entries/{id}/attachment | 原分享授权与选中范围内的相对附件 |
| DELETE /api/public/shares/{token}/downloads/{id} | 当前分享授权且任务绑定该分享，取消ZIP |
| GET /api/user/access | 本人的邮箱、MFA状态及应用密码元数据，无密钥 |
| POST /api/user/access/mfa/setup | 当前password，返回仅本次显示的验证器secret/uri |
| POST /api/user/access/mfa/confirm | code，返回10个一次性recoveryCodes |
| DELETE /api/user/access/mfa | password与otp/恢复码 |
| POST /api/user/access/dav | label/readOnly/days/password/otp，返回一次性应用密码 |
| DELETE /api/user/access/dav/{id} | 撤销本人的应用密码 |
| POST /api/user/access/email | email/password，发送15分钟邮箱验证链接 |
| POST /api/public/auth/verify-email | token，一次性绑定邮箱 |
| POST /api/public/auth/forgot | account，异步发送找回邮件，返回统一提示 |
| POST /api/public/auth/reset | token/password，撤销会话与应用密码，不移除MFA |
| /dav/ | 独立Basic应用密码认证，拒绝主密码与Cookie；Class 1方法 |
| GET/PUT /api/admin/config/{section} | section为mail/office/storage；密钥脱敏，空密钥保留原值 |
| POST /api/admin/config/{section}/test | SMTP测试收件人或Office健康检查 |
| GET /api/admin/storage | 策略、后端对象用量、磁盘容量告警、迁移任务 |
| POST/DELETE /api/admin/storage/policies[/{id}] | 新建/修改S3策略或删除未使用策略 |
| POST /api/admin/storage/test | backend；写入、读回与清理测试 |
| POST /api/admin/storage/migrations | source/target，返回持久化任务id |
| DELETE /api/admin/storage/migrations/{id} | 取消后续迁移，不回退已完成对象 |
| GET /api/office/status | 当前是否启用Office服务 |
| GET /api/office/{id}/editor?mode=view或edit | 拥有者打开带受限CSP的编辑页 |
| GET /api/public/office/file?ticket= | 有时限的签名文档读取授权，不是公开文件直链 |
| POST /api/public/office/callback?ticket= | 文档服务JWT验证后保存版本；仅此签名回调免CSRF |

Office文字阅读预览扩展到Excel/PPT，JSON仍为 `{html}`。JWT回调 URL 仅接受配置的文档服务 cache 路径且不跟随重定向。外部文件更新导致修订冲突时需重新打开编辑器。部署配置与限制见 [进阶说明](advanced-features.md)。
