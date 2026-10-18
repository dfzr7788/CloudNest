# API 契约

本文描述接口行为与权限边界，配合 [OpenAPI](openapi.json) 和服务端控制器/校验使用；使用入口见 [使用说明](usage.md)，本地联调见 [开发指南](development.md)。

除单独标注的 `/dav/` 外，API 路径以 `/api` 开头；下文省略前缀的路径须补上 `/api`，AI 表格另标注其子前缀。请求通常使用 camelCase，部分响应直接采用 snake_case 字段，以具体接口为准；条目 ID 为 UUID 字符串。时间为 Unix 毫秒；容量/长度为字节；分页从 1 开始。成功返回 JSON 或空响应；文件返回二进制流。错误：`{"code":"CONFLICT","message":"目标目录已存在同名文件"}`。

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

支持文件和文件夹递归打包，客户端无需预先展开目录。每用户并行任务 ≤2，保留任务 ≤10，过期 1 小时。
## 账号、批量操作与版本接口

以下接口均沿用现有 Cookie 身份与写请求 CSRF 校验。接口清单见 [OpenAPI](openapi.json)；下表说明各流程的参数和权限。普通认证请求保持 CSRF Cookie 稳定，登录/注册/退出时清除旧令牌，下一次写入前重新获取。

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

历史版本和单文件固定分享会增加对象引用；普通文件删除、分享撤销、后台清理已同步适配。普通下载 ZIP 同样验证修订号。其他限制沿用本文各功能章节的约定。

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
| /dav/ | 独立Basic应用密码认证，拒绝主密码与Cookie；基础WebDAV方法（不提供LOCK/PROPPATCH） |
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
## AI API

供应商兼容：固定短测试的最大输出为 `min(256, 模型maxOutput)`。官方 `api.deepseek.com` 的 `deepseek-flash`、`deepseek-v4-pro` 及受支持 Flash 旧别名显式发送 `thinking:{type:"disabled"}`，此参数不发送给其他主机或未知模型。响应 `finish_reason=length` 返回 `AI_OUTPUT_LIMIT`，即使包含部分正文也不发布为成功；空最终文本仍为 `AI_INVALID_RESPONSE`，不将 `reasoning_content` 当作答案。上述失败响应已提供的 token 用量会记录到本次调用。

前缀 `/api/ai`，复用 Cookie 登录和写操作 CSRF。只访问本人连接、模型、文件和任务；返回的配置不含密钥明文或密文。资源不存在或属于其他用户统一 404。

| 方法与路径 | 请求/说明 |
| --- | --- |
| GET `/capabilities` | `enabled,features,protocol,localEndpoints,dailyCalls,maxTaskCalls,noticeVersion` |
| GET/POST `/connections` | 创建 `{name,baseUrl,apiKey,enabled}`；保存不外发 |
| PATCH `/connections/{id}` | `configVersion` 必填；其他字段可省略；省略 apiKey 保留，空值拒绝，地址变化须新 Key |
| DELETE `/connections/{id}` | 撤销密钥、停用并删除连接/模型，停止任务；用量保留 |
| DELETE `/connections/{id}/credential` | 清除密钥并停用，保留连接 |
| GET/POST `/models` | 创建 `{connectionId,name,modelId,contextLimit,maxOutput,outputParamMode,enabled}`；purpose 默认为 CHAT，也可显式 EMBEDDING（见下文） |
| PATCH/DELETE `/models/{id}` | 更新须 `configVersion`；更新/删除使旧任务停止 |
| POST `/models/{id}/test` | 固定短聊天测试，202，计费计数，须 `Idempotency-Key` |
| GET/PUT `/preferences` | GET `default_chat_model_id`；PUT `{defaultChatModelId: id或null}` |
| POST `/consents` | `{connectionId,destinationFingerprint}`，指纹取连接返回值；告知版本 1 |
| GET `/files/{id}/analysis` | query `modelId,rangeChars=120000`，仅提取不调用模型；返回修订/状态/来源位置/范围/预计调用数 |
| POST `/files/{id}/summaries` | `{modelId,expectedRevision,rangeChars?,allowPartial?,language?,forceRefresh?}`，202 |
| POST `/files/{id}/tag-suggestions` | 同摘要，生成最多 8 个候选标签，不修改真实标签 |
| GET `/tasks/{id}` | 200，终态失败仍返回 200，通过 status/error_code/error_message 表达 |
| GET `/tasks` | query `fileId?,page=1,analysisOnly=false`（true 排除问答，摘要/标签单独分页），每页最多 20；跳过已不可访问的文件任务 |
| POST `/tasks/{id}/cancel` | 202，幂等；运行中先标记 cancel_requested，线程退出后终态 |
| DELETE `/artifacts/{id}` | id 为 taskId，清除终态任务结果，不删除源文件或用量 |
| GET `/usage` | query `days=30`（1–90）,`page=1`；每页 50，含 summary/dailyLimit/resetTimezone |

连接/模型返回数据库风格字段（`base_url,config_version,connection_id,model_id,context_limit,max_output,output_param_mode`）；连接额外包含 `keyConfigured,destinationFingerprint,consented,protocol,last_test_status`。当前 `last_test_status` 为连接最近一次指定模型测试结果；不代表其他模型可用。

任务创建必须携带 `Idempotency-Key`（1–100 字符，24 小时窗口，不得以 `expired:` 开头）；同用户同键同输入返回原 taskId，不同输入 409，包括缓存命中；不同用户独立。返回 `{taskId,id,status,stage,pollAfterMs,...}`，缓存命中额外 `cached:true`。`result` 为 `{text}` 或 `{tags}`；`coverage` 包含修订、内容哈希、提取器版本、状态、已处理字符/页数、总页数、selectedCharacters、partial、reason、来源标识与位置。来源是范围元数据，不宣称摘要每一句都验证了引用。

`rangeChars` 为 100–262144，从开头选择字符（含来源标识）；`language` 为 `zh`/`original`。部分范围必须 `allowPartial:true`；无文本、损坏、加密和不支持的类型不调用模型。前端可 2 秒轮询，隐藏页面降至 8 秒，离开停止轮询。

`PUT /api/entries/{id}/tags` 新增可选 `expectedTags` 数组：后端在用户事务内对比当前标签，发生变化返回 409。AI 标签 UI 先读取最新标签、展示合并结果，确认后携带此前标签，避免覆盖并发变更。

主要错误码：`AI_DISABLED`、`AI_ENDPOINT_BLOCKED`、`AI_NOT_CONFIGURED`、`AI_CONSENT_REQUIRED`、`AI_AUTH_FAILED`、`AI_MODEL_UNSUPPORTED`、`AI_UPSTREAM_RATE_LIMITED`、`AI_TIMEOUT`、`AI_CONTEXT_TOO_LARGE`、`AI_NO_TEXT`、`AI_PARTIAL_CONTENT`、`AI_REVISION_CHANGED`、`AI_QUOTA_EXCEEDED`、`AI_CANCELLED`、`AI_INTERRUPTED`、`AI_CREDENTIAL_UNREADABLE`。实际用量缺失为 null，不写作零；估计输入按 UTF-8 字节数加固定开销计算。超时可能已计费，不自动重试。


## 单文档会话 API（v0.2）

同样使用 `/api/ai` 前缀、本人 Cookie 与写操作 CSRF。能力列表新增 `DOCUMENT_QA`。

| 方法与路径 | 请求/说明 |
| --- | --- |
| POST `/conversations` | `{fileId,modelId,expectedRevision}`，200。创建快照不调用模型；返回 id、revision、model_name、destination、readOnly、currentRevision、messages、activeTaskId |
| GET `/conversations` | `fileId?、page=1`，每页 20，跳过回收/失效文件和过期会话 |
| GET `/conversations/{id}` | 会话与按提问顺序排列的 messages；每条含 task_id、role、content、status、error_message、scope?、citations。activeTaskId 用于恢复轮询 |
| POST `/conversations/{id}/messages` | `{question,allowPartial?}`，question 1–2000 字符；必须有 Idempotency-Key，202 返回现有任务契约，kind=QA。每会话只允许一个排队/生成任务 |
| GET `/conversations/{id}/citations/{citationId}` | 再次鉴权，返回 fileId、revision、readOnly、source_id、locator、excerpt。只返回该会话实际上下文中的引用摘录，不支持模型自定义 URL |
| DELETE `/conversations/{id}` | 停止并清除本会话问题、答案、引用和任务正文，200 空响应；源文件、调用统计保留 |

问答不使用跨问题结果缓存，但相同幂等键可取回原任务。成功任务 `result={text,sources,invalidCitations,scope}`；scope/coverage 含 revision、partial、selectedCharacters、selectedBlocks、extractedCharacters、reason。来源未命中时成功返回范围内无依据的提示、total_calls=0；格式错误返回 `AI_INVALID_ANSWER`，不自动再次调用。模型输出必须是 `{answer:字符串,citations:[sourceId]}` JSON；只有实际选中来源 ID 可生成引用，无有效引用时改用范围内无依据提示。

最多 6 轮成功历史，保守预算下可减少；本地关键词检索发送完整片段，不提供 embeddings。每会话最多 100 个问题，每用户 200 会话。30 天无活动清除；任务随会话活动续期。文件/配置变化时只读，继续提问返回 `AI_REVISION_CHANGED`；回收后所有会话/任务/引用不可读，恢复同修订可恢复，永久删除在同一事务清除正文。QA 不使用 `/artifacts/{id}` 单独删除，须删除会话。


## 向量模型准备接口

能力列表新增 `EMBEDDING_TEST`、`KNOWLEDGE_BASE`、`SEMANTIC_SEARCH`、`VERSION_DIFF`、`ORGANIZE`。现有模型自动迁移为 `purpose=CHAT`，旧客户端不传 purpose 的创建行为不变。用途创建后不可修改；聊天默认值、摘要、问答和聊天测试拒绝 EMBEDDING，向量测试拒绝 CHAT，错误码为 `AI_MODEL_PURPOSE_MISMATCH`。

`POST /api/ai/models` 向量示例：

```json
{"connectionId":"自己的连接ID","name":"向量模型","modelId":"供应商向量模型ID","purpose":"EMBEDDING","contextLimit":8192,"embeddingDimensions":1536,"maxBatchSize":16,"requestDimensions":false,"enabled":true}
```

向量输入预算 contextLimit 为 512–32768，embeddingDimensions 为 1–4096，maxBatchSize 为 1–32，requestDimensions 默认为 false，且不接受 maxOutput/outputParamMode 聊天参数。PATCH 与现有模型相同，需要 configVersion。返回 `purpose,embedding_dimensions,max_batch_size,request_dimensions,last_test_status,last_test_at`；CHAT 的向量维度/批次为 null。修改连接/模型或撤销密钥会清除当前配置的测试状态。

`POST /api/ai/models/{id}/embedding-test` 必须携带 CSRF 和 Idempotency-Key，202 返回 kind=EMBEDDING_TEST 的异步任务，复用已有轮询、取消和配额。只发固定英文样例，不接受文件或自定义输入，不使用聊天测试结果替代验证。

向量适配器请求根地址下 `/embeddings`，只发送 model、input 字符串数组、encoding_format=float，以及用户明确启用时的 dimensions。按 index 重排，验证返回数量/唯一索引、配置维度、数值有限且 float32 可表示、非零范数。失败返回 `AI_INVALID_EMBEDDING` 或 `AI_EMBEDDING_DIMENSION_MISMATCH`；已返回的实际用量保留，不自动调用修复。成功 `result={text,dimensions,vectorCount,notice}`，不返回或保存原始测试向量。调用记录新增 purpose，向量 output_tokens=null（不适用），实际输入/总用量缺失仍为 null。模型记录只表示指定配置的最近一次接口测试，不代表检索质量；连接上的旧 last_test_status 仅保留聊天测试兼容语义。

协议依据：[OpenAI 官方 Embeddings 接口](https://developers.openai.com/api/reference/resources/embeddings/methods/create)。这里实现兼容协议，不预设所有供应商支持相同模型、输入限额或降维参数。


## 知识库、语义搜索与增强接口

所有写请求有 CSRF；下表标为任务的 POST 还必须带用户作用域 Idempotency-Key，返回 202、taskId，沿用查询/取消/用量。所有资源与关联文件均验证本用户归属。新用途不缓存付费查询；重发同键同输入只读原任务，同键不同输入 409。

| 方法与路径（前缀 /api/ai） | 请求 / 结果 |
| --- | --- |
| GET /knowledge-bases | 本人全部知识库（最多 10）；含 id/name/model_id/scopeIds/fileIds/ready/needsUpdate/chunks/active_generation/pending_generation |
| POST /knowledge-bases；PATCH /knowledge-bases/{id} | name、embeddingModelId、scopeIds 数组，1–100 个本人的文件或目录；保存时快照最多 100 文件，不调用模型。PATCH 重新快照当前目录内容，运行中拒绝修改 |
| DELETE /knowledge-bases/{id} | 运行中先停止；清除索引、批次、会话与关联任务，不删除源文件 |
| GET /knowledge-bases/{id}/index-preview | digest、files[{fileId,name,revision,partial,reason}]、chunks、maxCalls（按一块一次的保守上界）、maxCallsPerJob=8、destination、notice。不调用模型 |
| POST /knowledge-bases/{id}/index-jobs（任务） | modelId=本库向量模型、expectedDigest、allowPartial；每任务最多 8 批。result={text,remainingChunks,generationId,sources:[]}；剩余非零需预览后新任务继续 |
| POST /knowledge-bases/{id}/semantic-search（任务） | modelId=本库向量模型、question（1–2000 字符）；一次问题向量调用，最多 6 个有效块；result={text,question,generationId,sources} |
| GET/POST /knowledge-bases/{id}/sessions | GET 返回本人本库会话，含 readOnly；POST {modelId:聊天模型}，返回 id/model_id/generation_id/updated_at，绑定聊天模型及连接版本，不调用模型。配置变化、索引换代或旧会话缺少绑定时只读，继续提问需新建会话 |
| GET/DELETE /knowledge-sessions/{id} | GET 按顺序返回最多 100 个问答任务（question 与 result），失效引用任务不返回；DELETE 需先停止活动任务 |
| POST /knowledge-bases/{id}/answers（任务） | modelId、question、searchTaskId、sessionId；问题必须与有效成功检索一致，同本库/代次、本人会话，最多 6 轮有效历史。result={text,sources,invalidCitations,partial,generationId,question} |
| GET /files/{id}/change-preview?fromVersion=...&toVersion=... | version 为本文件历史 ID 或 current，任一侧无可提取正文返回 AI_NO_TEXT；返回 digest/revision/from/to/changes[{type:ADDED或REMOVED,line,text}]/notice；不调用模型 |
| POST /files/{id}/change-summaries（任务） | modelId、expectedRevision、fromVersion、toVersion、expectedDigest；确定性差异送聊天模型解释；result={text,changes,fromVersion,toVersion,notice}；无差异不调用 |
| POST /organization-preview | fileIds（最多 8）、targetFolderIds（最多 20）；返回 digest、sources（修订/原名/原目录/原标签/前1000字符/partial）、folders、notice；不调用模型 |
| POST /organization-jobs（任务） | modelId、fileIds、targetFolderIds、expectedDigest、allowPartial=true；一次聊天调用；result={text,suggestions:[{fileId,originalName,name,tags,targetFolderId,reason}],notice}；不修改文件 |
| POST /tasks/{id}/apply-organization | {selections:[{fileId,applyName,applyTags,applyMove}]}；布尔勾选，缺省不应用；至多 8 项；返回逐项 fileId/status(APPLIED/ALREADY_APPLIED/SKIPPED/FAILED)/errorCode/message，无模型调用 |

source 结构为 `{sourceId,file_id,revision,name,locator,text,score?}`；标识由已入库授权块生成，模型不能添加路径。再次 GET 任务会重新检查全部来源的当前修订/目录范围/回收状态；调用前与发布前再次鉴权。模型未知引用会过滤，引用全无则回答无依据。配置、维度、提取器或切块身份不一致返回 AI_INDEX_STALE，不能跨代次比较；同库只允许一个问答生成任务，索引修改前必须停止活动任务。

索引身份为 embedding 模型 ID/配置版本/连接版本/维度/structured-v1/chunk-v1；块按最多 900 字符（小输入模型进一步缩小），保留原提取定位及片段字符位置。受 UTF-8 字节保守预算、每批 1–32 条及每日 100 次约束。增量按同身份/文件/修订/块序号/内容哈希复用；更换维度或模型重建新代次。全部块有有效向量后原子切换，清理旧代次；执行失败保留成功块、未知批次不自动重试。

新表随数据库备份；索引、查询及问答不新增外部服务。普通任务 7 天，知识库会话任务与其引用的搜索任务从最近活动后 30 天；未完成索引代次 7 天清理，已完成索引由用户/源文件生命周期控制。所有新正文/向量均计入 100 MiB 派生额度（待向量块按实际维度预留）；永久删除源文件同步清除块和通过 ai_task_source 关联的任务正文。AI 关闭阻止新任务，不阻止本人的删除清理操作。
