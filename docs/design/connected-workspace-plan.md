# CloudNest 2.1 升级实施方案

> 规划基线：GitHub `duyan9030-dotcom/CloudNest`，`main` 提交 `45b14a05e0b18192607e2bf45861e032764b8ead`（2026-10-20 核查）。2.0 当前自报版本 `2.0.0-rc.1`；本文件是 **2.1 规划方案**，不是现有实现清单。目标是面向真实用户长期使用。保留 2.0 智能调度、对象迁移、副本及数据修复已有行为。

## 0. 启动门槛：先完成 2.0 稳定验收

### 已定位发布缺陷（必须修）

- `cloudnest-server/pom.xml` 的 Maven 版本已是 `2.0.0-rc.1`，但 `cloudnest-server/Dockerfile`、`deploy/Dockerfile.server-runtime`、`.dockerignore` 仍使用 `cloudnest-server-1.0.0.jar`。修复构建产物的命名约定或引用，避免下次修改版本再次失效。建议 Maven 构建时输出固定部署产物 `cloudnest-server.jar`（例如 `<finalName>cloudnest-server</finalName>`），统一源码与预构建 Dockerfile 路径，并相应调整 `.dockerignore`；也可以短期一致改为 `2.0.0-rc.1`，但不要留下版本硬编码。
- 最新 Actions：`https://github.com/duyan9030-dotcom/CloudNest/actions/runs/39343968581`。后端 verify、前端 build、运维脚本通过；`Package Linux amd64 images` 失败，后续 Docker 集成、隔离恢复、镜像发布均未执行。CI 日志直接提示旧 JAR 文件不存在。

### 2.0 发布阻断清单

- [ ] 修复以上三个构建文件，`docker compose up -d --build --wait` 在**隔离测试环境**成功。
- [ ] 最新提交的完整 CI 变绿，预构建镜像能正确导入、健康检查通过。
- [ ] 用 Garage 实际完成双机独立读回与 SHA-256 校验，验证节点下线、恢复、文件级迁移与异域双副本读取。
- [ ] 验证所有副本不可用、对象损坏、迁移重启/取消，不得伪成功或错误清理唯一有效对象。
- [ ] 在隔离 Docker 目标环境恢复 MySQL、本地文件、S3 对象、签名密钥、版本和分享，验证实际可读。
- [ ] 在真实 1GB / 25GB VPS 上测量内存峰值、OOM、临时磁盘峰值、Range 下载延迟与并发。
- [ ] 为当前 2.0 RC 建立回退基线；涉及 Flyway 不采用仅回滚 JAR 的方式恢复旧版。

注意：`docs/horizon.md` 中本地 131 项等结果属于仓库记录，不能代替本次独立的真实服务器验收。以上门槛完成前不应宣称 2.0 稳定版。**允许规划 2.1，不应在尚未验收的数据模型上仓促堆叠破坏性改动。**

---

## 1. 2.1 定位与边界

**版本代号：CloudNest 2.1 — Connected Workspace / 可控 Agent 与跨端协同。**

**用户目标：** 让单人/小团队低门槛自托管，通过中文或英文界面管理多节点云盘；Windows 用户能可靠地双向同步文件；管理员能使用可信的 AI Agent 解释故障并批准受控操作。

**工程目标：** 将逻辑上已经存在的节点管理、对象调度、副本、元数据和权限接口拆清楚，默认仍部署为一个 Spring Boot 进程；为未来独立 Controller 做准备，但不要求一台 1GB VPS 同时承载多个 JVM 实例。

**保留现有行为：** 存储调度 `MANUAL/CAPACITY/STABILITY/HIGH_AVAILABILITY`、版本与分享、WebDAV、带签名的授权下载、用户自带 AI API、同一文件对象引用和已有数据迁移逻辑。

**2.1 不包括：** 零知识加密保险箱、文件夹时间机器、团队成员贡献硬盘池、Windows 虚拟磁盘、底层分块去重、第三方插件商店。这些留给 2.2/2.3，避免范围失控。真正数据库级高可用和三副本 Raft 共识也不作为 2.1 发布承诺。

## 2. 建议架构

```text
  Web / Windows Sync Client (zh-CN / en-US)
                 |
           HTTPS Gateway
                 |
      CloudNest App + Controller  <-- 默认仍为单进程模块化部署
       |       |         |
  Auth/ACL   Metadata  Task/Placement/Replica
       |       |         |
       +---- MySQL ------+
                 |
         Storage Adapters
       /         |           \
    Local      Garage       Other S3

 Admin AI Agent --[受控工具接口]--> Controller (只读优先；敏感写入需审核)
 Windows Client --[官方同步 API]--> Drive/Change Feed (不接触 S3 密钥)
```

### 2.1 模块边界

- `Gateway`：HTTPS、统一认证入口、限流、路由；保持现有安全契约。
- `Identity & Authorization`：身份、团队/文件权限、分享授权；工具每次独立鉴权，模型输出不能替代。
- `Metadata`：目录、文件、修订、逻辑对象与物理后端映射；以 MySQL 为权威事实源。
- `Placement & Storage`：继续由已验证的确定性算法负责准入、容量预留、迁移、校验、副本状态。
- `Task Coordinator`：持久化队列、任务状态、幂等操作及终态；可逐步复用 2.0 的 `storage_job_item` 思想。
- `Agent Runtime`：目标解析、工具选择、结果观察、最多 N 次调用、用量上限、审批等待和可审计结果；不拥有操作系统管理员权限。
- `Sync Service`：文件变更事件流、冲突判定、客户端同步光标与幂等提交；不直接把服务器临时块目录暴露给客户端。

### 独立部署策略

- 单机默认：现有 `cloudnest-server` + MySQL + Redis，不新增常驻模型进程；Agent 按需调用外部 API。
- 可选高级部署：Controller 与 Web/API 分进程，必须先证明状态、会话、上传暂存和后台任务不会依赖进程本地假设。
- 多实例实验：任务领取须使用数据库事务/租约/fencing token；变更与锁需幂等；单 MySQL 仍是单点，不能声明控制平面全面 HA。
- 维持对 Windows Garage B 的私网访问，现有公网 signed GET 规则不因控制平面拆分而弱化。

## 3. 开发模块与任务清单

### M0：国际化与公开英文资料（P0/P1）

- [ ] 新增 `README.en.md`、`docs/en/deployment.md`、`docs/en/usage.md`、`docs/en/troubleshooting.md`，中文入口加入语言切换链接。
- [ ] 中英文部署步骤都在干净测试服务器上复现；代码/命令/密钥安全提醒不能只靠机器翻译。
- [ ] Vue 采用 `vue-i18n`，`src/i18n/locales/zh-CN.*`、`en-US.*`，建立键名规范、缺失键检测及资源复用规则。
- [ ] Element Plus 根据语言切换区域设置；语言偏好至少浏览器持久化，必要时扩展账户偏好。
- [ ] 将登录、文件列表、上传下载、分享、节点中心、AI 设置等重要文案外置。
- [ ] 优先将后端错误转换为稳定 `error.code` 加参数，前端本地化展示；服务日志仍使用技术错误码。
- [ ] 日期、数量、文件大小、邮件/通知、空状态、ARIA 无障碍标签国际化；主要视图以 390px 和桌面端测试英文换行。

### M1：控制平面模块化（P0）

- [ ] 画清客户端→Gateway→Controller→Storage 的上传、下载、分享、迁移与故障切换时序。
- [ ] 为身份/权限、元数据、节点注册与策略、任务编排定义明确的 Java 接口，不一开始拆成微服务。
- [ ] 控制器全部接口保留当前授权语义；迁移老 URL 时提供兼容层。
- [ ] 从存储业务中抽出只读指标接口供管理 UI/Agent 使用，限制返回字段，不能泄露凭据。
- [ ] 保证所有定时任务仅在符合角色的实例运行；必要时添加轻量工作器开关。
- [ ] 设计多实例协调实验（相同对象并发操作、任务重领、数据库重启），为后续真正独立部署准备。

### M2：AI Agent Runtime（P0，先只读）

- [ ] 在现有 `ModelGateway` 上新增带工具描述的调用接口；校验上游是否真实支持 tool calling，缺少支持则明确降级为普通只读问答。
- [ ] 实现 `AgentRunService` + `AgentToolRegistry` + `AgentExecutor` + `AgentApprovalService`，工具参数使用严格 schema，并校验输出。
- [ ] 首批只读工具：`get_node_status`、`get_storage_capacity`、`get_node_events`、`get_active_storage_jobs`、`explain_storage_policy`。
- [ ] 单轮限时和最大步数；全任务限制工具调用次数、Token/费用预算，取消或网络超时不隐式重复计费。
- [ ] 存储 `agent_run`、`agent_step`、`agent_tool_invocation`、`agent_approval` 状态；展示真实工具结果与引用，不能制造节点事件。
- [ ] 实现 Agent Loop：选择工具→校验权限→执行→记录→观察结果→继续/停止；阻断工具结果、文档和错误日志中的提示词注入。
- [ ] 支持文件权限的最小授权，只读运维工具仅管理员可用，普通用户的文件 Agent 仅访问其授权文件。

### M3：受控操作型 Agent（P1）

- [ ] `simulate_file_migration` 调用**确定性**程序预估容量、来源、目标、副本与故障域约束；禁止模型自报“校验通过”。
- [ ] `propose_storage_action` 保存包含操作类型、有限参数、文件 ID、预期变更、影响范围和风险的待审批方案。
- [ ] `approve_storage_action` 为人工审批接口，不允许模型自己调用；审批前重新鉴权并检查数据修订、节点健康、空间与目标。
- [ ] 受控迁移调用已有文件任务系统，不直接对 S3 或数据库执行模型生成命令。
- [ ] 迁移后通过真实 SHA-256、文件访问和副本状态验证结果；失败状态留痕，可人工恢复。
- [ ] Agent 报告区分“观察到的事实”“推测原因”“建议操作”和“已经执行并校验的动作”。

### M4：Windows 双向同步（P0/P1，用户留存核心）

- [ ] 定义同步语义：云端为权威元数据；客户端每次对比 revision / ETag / SHA-256 / change token。
- [ ] 新增有序增量变化 API（游标 + 快照版本 + 重放与失效处理），包括改名、移动、删除 tombstone、恢复和修订。
- [ ] 本地 MVP：手动选择同步目录；初次扫描→按变更单向拉取→单向上传→双向冲突检测；延迟实现实时推送。
- [ ] 采用现有分片上传和断点续传 API；下载按临时签名或鉴权通道，不向桌面应用下发长期 S3 密钥。
- [ ] 本地操作队列持久化、崩溃恢复、指数退避（仅幂等安全步骤）、网络断开后继续；本地文件监听必须定期进行完整扫描纠偏。
- [ ] 规则：同一路径双端同时修改→生成明确的冲突副本并通知；删除先入回收站且不静默覆盖未同步本地更改；改名与移动保留身份映射。
- [ ] 客户端登录采用受限设备凭证且安全存放（如 Windows Credential Manager）；提供设备退出、撤销和活动记录。
- [ ] 先交付 Windows 命令行/后台服务原型，再提供简单桌面安装包、状态页、选择性同步与冲突中心；勿第一阶段实现虚拟磁盘。
- [ ] 用户真实数据测试必须保留独立备份；测试使用临时目录，两台客户端断线、修改、删除冲突场景必须可重复。

### M5：资源拓扑与可视化运维（P1）

- [ ] 节点列表/拓扑、故障域、副本保护状况、容量占用、逻辑/物理/预留三种口径区分。
- [ ] 节点故障时间线和任务状态可查；不依赖模型才能看见原因码。
- [ ] 管理仪表盘可打开 Agent 报告和操作审批页；单设备无需显示复杂企业功能。
- [ ] 以低频采样与短保留周期控制 MySQL、1GB VPS 负载，监控数据与用户文件元数据最小化展示。

## 4. 数据结构与 API（草案，不直接用于生产）

### 新增数据对象建议

| 表/对象          | 核心字段                                                     | 目的                     |
| ---------------- | ------------------------------------------------------------ | ------------------------ |
| `agent_run`      | `id,user_id,kind,objective,status,model_id,created_at,updated_at,deadline_at` | Agent 任务主记录         |
| `agent_step`     | `id,run_id,seq,step_type,status,request_hash,result_ref,cost` | 完整步骤与计费追踪       |
| `agent_approval` | `id,run_id,proposal_hash,approver_id,status,expires_at`      | 管理员审批和防篡改       |
| `sync_device`    | `id,user_id,name,revoked_at,last_seen_at`                    | 可撤销的客户端设备       |
| `sync_cursor`    | `device_id,user_id,library_id,cursor,updated_at`             | 增量同步位置             |
| `drive_change`   | `seq,user_id,entry_id,revision,operation,created_at`         | 增量变更日志与 tombstone |
| `sync_operation` | `id,user_id,device_id,client_op_id,entry_id,status,result_revision` | 客户端重试幂等           |

数据迁移必须创建新的 Flyway V 版本文件（H2/MySQL 双实现）；使用数据库索引与保留策略防止无界增长。对事件删除保留期和过期游标进行协议设计，避免陈旧客户端漏变化。

### 建议新增 API（路径仅供设计评审）

```
GET  /api/admin/agent/tools/available
POST /api/admin/agent/runs
GET  /api/admin/agent/runs/{id}
POST /api/admin/agent/runs/{id}/cancel
GET  /api/admin/agent/runs/{id}/steps
POST /api/admin/agent/approvals/{id}/approve
POST /api/admin/agent/approvals/{id}/reject

POST /api/sync/devices
DELETE /api/sync/devices/{id}
GET  /api/sync/bootstrap
GET  /api/sync/changes?cursor=...
POST /api/sync/operations
GET  /api/sync/status
```

优先复用已经存在的认证、文件上传、下载与权限服务；不要为 Agent 暴露任意 SQL、Shell 或公网存储凭据。新增接口必须同步 `docs/openapi.json`、`docs/api.md` 和契约测试。

## 5. 分阶段交付与验收

| 阶段 | 优先级 | 工作                                           | 可验收成果                                          |
| ---- | ------ | ---------------------------------------------- | --------------------------------------------------- |
| G0   | 阻塞项 | 完成 2.0 稳定版验收及发布修复                  | CI 绿、真实双机/恢复/1GB 验收报告                   |
| G1   | P0     | 英文部署文档、Vue i18n 基础、核心文案          | 英文用户独立安装、切换语言、不丢页面状态            |
| G2   | P0     | 逻辑控制平面边界及任务安全梳理                 | 原 API/权限/迁移功能回归全过                        |
| G3   | P0     | 只读 Agent Runtime 与工具                      | 不超过规定步数能完成可解释节点诊断，工具结果可审计  |
| G4   | P0     | Windows 双向同步 MVP                           | 两台 PC 离线→上线、冲突、删除、恢复、重启均不丢数据 |
| G5   | P1     | 审批式 Agent、资源地图、同步 UI 完善           | 有审批才能迁移；可校验实际结果；可查看节点拓扑      |
| G6   | 发布   | 中英文全流程、真实用户试用、性能/安全/备份验证 | 发布 `v2.1.0` 或先 `v2.1.0-rc.1`                    |

**粗估周期：** 16–24 周个人业余开发（由同步复杂度、真实测试环境和是否独立拆部署决定），不是承诺。允许 G1、G3、G4 并行做独立原型，但所有生产数据模型变更需要基于经过稳定验收的 2.0。

### 强制验收矩阵

- CI：Java 21 `mvn verify`、Vue build/typecheck、Python 发布脚本、Docker 新装与隔离恢复、API 契约检查全部通过。
- E2E：Playwright `zh-CN` 和 `en-US` 下的登录、上传、下载、分享、节点中心、Agent 审批流程；语言变更不导致授权回退。
- Agent：模型不存在工具、上游 `tool_calls` 格式错误、注入指令、工具返回超大内容、成本上限、管理员审批过期、重复提交、任务进程重启。
- 同步：10k 小文件、含中文/特殊符号文件名、大小写冲突、长路径、网络中断、修改冲突、删除、移动、重启、失效游标、账号撤销、冲突回退。
- 安全：跨用户工具请求必须失败；管理员审批不能授权本来无权访问的文件；客户端不持有长期对象存储密钥。
- 单机：1GB VPS 运行既有网站时，关闭 AI/同步新特性仍能正常工作；测量同步扫库和节点指标保留开销。
- 真实使用：至少 5 名自托管测试用户，安装成功率、30 天主动使用、致命故障数量和数据恢复演练记录。

## 6. 开发顺序与分支策略

- 主线 2.0 的修复用独立 hotfix 提交，CI、部署验收绿后打稳定标签。
- 使用 `feature/i18n-foundation`、`feature/control-plane`、`feature/agent-runtime`、`feature/sync-client` 短生命周期分支；每个合并请求要求单独回归，不长期积累不可合并的大分支。
- 数据迁移采用前向兼容：先加表/列，再切读写，最后清理；修改已执行 Flyway 迁移文件属于禁止事项。
- Agent 自主写操作、独立控制进程、多实例协调等使用默认关闭的功能开关。发布时保证旧功能退化路径明确，且升级中断后能够按一致性备份恢复。
- `2.1.0-rc.1` 面向早期测试者，数据有独立备份，明确不建议存放唯一重要资料；真实验收通过后发 `2.1.0`。

## 7. 需要坚持的产品原则

1. **稳定优先于功能数量。** 先保证用户文件完整，再考虑 Agent 是否炫酷。
2. **单服务器能部署。** 先进控制平面是逻辑结构，不要求 1GB VPS 跑三个 JVM。
3. **用户不必理解分布式系统。** 默认手动/简化策略，专业功能收在高级设置。
4. **AI 的语言输出不是证据。** 事实来自工具/元数据，写操作来自确定性后台及人工批准。
5. **同步冲突不能静默覆盖。** 宁愿生成冲突文件与明确提示，也不要自动丢弃唯一修改。
6. **真实用户驱动优先级。** 英文文档和桌面同步通常比多控制节点实验更能提升留存。

---

参考：

- 2.0 提交：https://github.com/duyan9030-dotcom/CloudNest/commit/45b14a05e0b18192607e2bf45861e032764b8ead
- Horizon 2.0：https://github.com/duyan9030-dotcom/CloudNest/blob/main/docs/horizon.md
- 最新失败 CI：https://github.com/duyan9030-dotcom/CloudNest/actions/runs/39343968581
- 当前 Dockerfile：https://github.com/duyan9030-dotcom/CloudNest/blob/main/cloudnest-server/Dockerfile
- 预构建 Dockerfile：https://github.com/duyan9030-dotcom/CloudNest/blob/main/deploy/Dockerfile.server-runtime