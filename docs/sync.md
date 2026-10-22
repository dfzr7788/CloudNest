# Windows 同步开发原型

管理员现可在「账号与安全 → 集成服务 → 站点功能开关」保存运行时开关，无需重启。网页保存值优先于下文部署默认值。Agent 运行还需开启只读诊断及站点 AI 并配置模型；预览能力仍需按本文验收。

完整客户端使用与恢复说明见 [客户端 README](../clients/windows-sync/README.md)。
当前未发布 2.1 稳定版，不建议存放唯一资料；真实双 PC、MySQL、双节点和 VPS 门槛继续保留。

## 隔离服务端

同步和审批的新表只在显式 `connected-dev` 配置中加载：

```powershell
# 新的、独立的数据目录和数据库；不要复用生产 DATA_DIR 或 DATABASE_URL。
java -jar cloudnest-server/target/cloudnest-server.jar --spring.profiles.active=connected-dev --server.address=127.0.0.1 --server.port=18080 --cloudnest.storage=local --cloudnest.redis-enabled=false --cloudnest.data-dir=./.data-connected-test --spring.datasource.url="jdbc:h2:file:./.data-connected-test/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
```

开发迁移位于 `db/connected/{h2,mysql}`，V19 为同步模型，V20 为人工审批模型。
原 `db/migration/{vendor}` 文件和默认生产 Flyway 位置未修改。桌面客户端使用独立 HTTPS 测试域名反向代理至该端口；CLI 本机 fixture 可显式使用 `--allow-local-http`。
回到旧配置不会删除开发表；不能用回退 JAR 代替数据库恢复。
本地 H2 已验证，MySQL 开发迁移仍需真实数据库验收。

## 设备与协议

普通登录用户在 **账号与安全 → 同步设备** 创建凭证，凭证仅显示一次，90 天到期，最多 20 个有效设备。
凭证只允许 `/api/sync/client/*` 和读取 CSRF；不能使用管理员接口、普通文件 API、WebDAV、分享或设备管理接口。
所有写请求仍需 CSRF Cookie/请求头；每次请求检查账号、设备撤销/到期，上传最终发布前再次检查。
只存凭证 SHA-256，不向客户端下发对象存储密钥。Windows 客户端凭证存入当前用户的凭据管理器。

`bootstrap` 在用户锁内取得一致快照，返回根 ID、文件身份、revision、SHA-256、
包含改名/移动元数据的 changeToken、快照版本及游标。最多 10000 个条目，不做不稳定的分页快照。
`changes` 按同一用户锁串行协调当前元数据，返回最多 200 条有序 UPSERT/DELETE tombstone，支持重放。
它合并两次协调之间的中间操作，承诺最终状态同步，不是每个动作的审计事件流。
游标保留最近 30 天、最多 100000 条；越界、其他账号或旧快照代际返回 `410 SYNC_CURSOR_EXPIRED`。
客户端重取快照时保留本地基线，不能把离线编辑当作可丢弃缓存。

`operations` 使用设备内 operationId、请求摘要和 30 天有效创建时间幂等提交；每账号最多保留 100000 条操作。
支持 MKDIR、条件 EDIT/TRASH、RESTORE、INIT_UPLOAD；不支持永久删除。
同一操作 ID 不同输入返回冲突，过期操作停止复核，不盲目换 ID 重做。
TRASH 仅允许已对齐的文件或空目录；非空目录拒绝，避免删除新上传的子内容。
覆盖上传使用 expectedToken、revision、路径及 SHA-256，复用既有分片与完成流程。
上传会话绑定设备；其他设备即使属于同一账号也不能直接提交它的分片。

接口与字段定义见 [OpenAPI](openapi.json)；客户端以独立 SQLite WAL 保存远端缓存、本地基线和待提交操作，
先记日志再发请求；下载先暂存校验再原子替换。冲突副本、本地回收目录和上传阶段副本不自动清理。
选择性同步只限制传输，不缩小设备对账号自有同步元数据的授权。

## 已验证与仍需验证

本机测试使用临时真实 HTTP 服务和独立客户端目录，验证双端内容、改名/目录移动、选择性传输、
删除/恢复冲突、旧游标、丢失上传初始化响应后的重启、设备撤销和 Windows 路径规则。
另有一万本地小文件扫描、Windows 凭据往返、桌面窗口构造和语言状态检查。
不代表已经通过一万文件网络压力、真实两台 PC、长路径、网络映射目录、完整桌面安装/卸载或 VPS 验收。
原型明确拒绝超过 240 字符的 Windows 路径、链接、junction、硬链接文件和 UNC 根；遇到不支持的名字停止，
不静默重命名云端资料。桌面安装包为需要 Python/Tk 的源码安装包，未提供签名 EXE 或系统服务。
