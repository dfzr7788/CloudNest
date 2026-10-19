# V1 实现契约

节点以 backend 为主键，策略是凭据唯一来源。local/minio 和全部策略（包括停用策略）回填；未知 backend 在节点列表单独报告，绝不改写对象位置。

所有配置修改、删除、上传和迁移准入在同一数据库 guard 行锁下串行化，适用于单应用实例。锁顺序为用户行 → guard；后台配置只取 guard。容量是管理员预算，0 表示不设硬上限。物理已用包含 file_blob 和迁移保留副本；候选对象有持久预留，删除成功后才释放。来源副本保留，不能以迁移后逻辑用量推断磁盘释放。

| 操作 | ACTIVE + 新鲜 ONLINE | WRITE_PAUSED / MAINTENANCE | UNKNOWN / DEGRADED / OFFLINE / 过期 |
|---|---|---|---|
| 新上传、迁入 | 启用且能力允许、预算充足时允许 | 拒绝 | 拒绝 |
| 已有会话、迁移 | 固定 backend 排空 | 固定 backend 排空 | 实际连接失败则重试/失败；不换 backend |
| 读取、迁出 | 沿用对象位置 | 允许 | 尝试原 backend，可能失败 |
| 节点删除/地址修改 | 有任何引用、预留、保留副本或迁移记录时拒绝 | 同左 | 同左 |

完整检测执行写、读、SHA/字节验证和删除；轻探测检查桶存在/本地目录。3 次失败 OFFLINE，恢复需连续 2 次成功；单次失败 DEGRADED。检测不覆盖管理状态。未通过完整检测的远端永不接受写入。启动检测 local；远端需要管理员完整检测。健康 120 秒后过期。

授权 API 在原 download 路径后追加 `-authorization`，返回 mode、url、expiresAt。代理路径保留。所有响应 no-store，直连约 60 秒；分享过期会缩短期限。鉴权先于签名；单文件分享使用 pinned_blob，目录/集合用原 member 边界检查。直连授权单独记录 DOWNLOAD_AUTHORIZED，不声称完成下载。

上传临时预算保守预留 3 倍文件大小（分片、合并、重复分片/本地候选峰值），并与 ZIP 共用全局锁和临时预算。运行余量默认 256 MiB。磁盘可用量检查是保守保护；系统其他进程占盘仍可能造成 I/O 失败。

部署、真实 MySQL 约束、Garage 公网签名、浏览器跨域、全量恢复与并发性能必须分别实测，不能用 H2 或 S3 fixture 代替。

## 数据字段与唯一来源

| 表 | 主要字段 | 约束/用途 |
|---|---|---|
| storage_policy | id、endpoint、bucket、region、加密 access_key/secret_key | 连接与凭据唯一来源；节点不复制凭据 |
| storage_node | backend PK、name、enabled、management、allow_upload、allow_migration、budget_bytes、public_endpoint、direct_download | 固定节点配置；s3:策略ID关联策略；0预算为不设硬上限 |
| storage_node_health | backend PK、state、validated、config_version、failures、successes、checked_at、latency_ms | 探测结果不覆盖管理状态；配置版本变化时丢弃旧结果 |
| storage_node_event | id PK、backend、state、created_at | 仅记录脱敏健康变化；30天保留 |
| storage_reservation | id PK、backend、bytes、temp_bytes | 上传/迁移/ZIP/导入临时预算；backend索引；删除成功再释放 |
| storage_copy | (backend,object_key) PK、size、created_at | 来源副本、失败候选、未清理探测对象占用；与当前blob同位置时不重复计算 |
| storage_guard | id PK，固定1行 | 单实例所有新引用与配置变更的串行化点 |
| file_share | download_authorizations | 与既有代理/ZIP请求计数分开；授权不等同下载完成 |

## API 与兼容入口

| API | 权限与行为 |
|---|---|
| GET /api/admin/nodes | 管理员；节点、已用、预留、检测过期标记、未知后端，不含凭据 |
| POST /api/admin/storage/policies | 原有管理员创建/编辑策略；同步创建节点并使旧健康验证失效 |
| PUT /api/admin/nodes/{backend} | 管理员；完整提交name、enabled、management、allowUpload、allowMigration、budgetBytes、publicEndpoint、directDownload |
| POST /api/admin/nodes/{backend}/test | 管理员完整检测；旧storage/test调用同一服务 |
| GET /api/admin/nodes/{backend}/events | 管理员；最近100条健康变化 |
| DELETE /api/admin/nodes/{backend} | 管理员；引用保护；内置local/minio不能删除；旧策略删除共用同一约束 |
| GET/DELETE /api/admin/nodes/{backend}/copies | 管理员查询/显式清理保留副本；重新检查引用和120秒等待期 |
| GET /api/files/{id}/download-authorization | 文件所属用户；按当前blob位置返回直连或原代理 |
| GET /api/public/shares/{token}/download-authorization | 原提取码/过期/撤销/下载权限；单文件使用pinned_blob |
| GET /api/public/shares/{token}/entries/{id}/download-authorization | 原member校验通过后才查blob并签名 |

错误码包括 NODE_WRITE_PAUSED、NODE_UNAVAILABLE、NODE_CAPACITY、TEMP_CAPACITY、COPY_DELETE_FAILED 和既有权限错误。UNKNOWN/OFFLINE 不做自动落盘降级；复制失败不切引用。新表是增量升级，旧 backend 不变；旧程序不能提供新准入保护，回退必须先停写或恢复同批数据，不能直接回滚镜像后继续写入。

上传数据流：选择默认节点 → 用户与guard锁下检查配额、节点和磁盘并预留 → 固定backend写分片 → A合并校验 → 写原目标 → 用户与guard锁下发布blob → 清理候选与分片后释放。迁移数据流：guard锁下固定候选集和目标预算 → 复制 → SHA-256校验 → 用户与guard锁下切引用 → 保留来源并记录占用；重启任务中断后由管理员重试。
