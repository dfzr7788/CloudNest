# 单进程控制平面边界

已建立可独立鉴权的只读存储诊断边界和首批手动诊断工具；下面时序展示当前逻辑分工，
不是独立 Controller 或多实例部署已经可用的承诺。

## 现有调用流

```mermaid
sequenceDiagram
    participant C as Web / 客户端
    participant G as Gateway / Spring Security
    participant M as 文件与上传服务
    participant P as Placement / Node
    participant D as MySQL 元数据
    participant S as Storage Adapter
    C->>G: 初始化上传、提交分片
    G->>M: 已认证的请求
    M->>D: 文件权限、配额与目标修订校验
    M->>P: 确定性选择、容量准入与预留
    M->>S: 校验并写入完整对象
    M->>D: 提交元数据与对象引用
    M-->>C: 真实完成状态 / 可重试失败
```

```mermaid
sequenceDiagram
    participant C as 下载者
    participant A as 认证 / 分享授权
    participant R as 文件与副本读取服务
    participant S as Local / S3 Adapter
    C->>A: 下载或分享访问
    A->>A: 独立校验权限、分享版本与有效期
    A->>R: 授权后的指定文件 / 修订
    R->>S: 选择可用且满足校验要求的来源
    alt 允许授权直连
      R-->>C: 短期签名下载地址
    else 经应用读取
      R->>S: 读取；故障时按既有规则尝试有效副本
      R-->>C: 文件流 / 明确失败
    end
```

```mermaid
sequenceDiagram
    participant C as 用户 / 管理员
    participant H as 存储任务 API
    participant J as Migration / Replica Worker
    participant D as MySQL 任务与对象引用
    participant S as 源 / 目标存储
    C->>H: 文件迁移 / 副本请求
    H->>H: 校验文件权限、目标与约束
    H->>D: 在既有锁与事务中登记任务、预留容量
    J->>D: 读取排队任务
    J->>S: 复制并执行真实校验
    J->>D: 更新任务、引用与副本状态
    C->>H: 查询 / 取消 / 显式重试
    H-->>C: 已记录的真实状态
```

## 已落地接口

新增三个进程内接口，并由数据库适配器实现：

- `IdentityAuthorization` 每次从数据库重新校验启用账户/管理员；既有 `RuntimeSettings.admin` 委托给它，原错误码不变。
- `FileMetadata.ownedFile` 仅返回 ID、父目录、名称、修订与逻辑 blob ID。使用既有 `DriveService.file` 的所有者与存活祖先校验，不接收模型声称的权限；分享授权仍由原分享服务管理。
- `StorageTaskCoordinator.submit(actor, Command)` 只接受文件 ID、预期修订、目标和有限任务类型。它在用户/存储锁内再次验证管理员、所有权和修订，然后交给既有迁移队列；不会接受调用方构造的 blob 行或报告复制完成。

审批模块已使用这些元数据/提交接口。批准事务仍先重算完整提案摘要，再提交同一事务中的队列记录；重复审批继续返回原任务 ID。接口可以独立拒绝越权调用，但没有绕过人工审批的新增 HTTP 入口。原文件、分享和存储 URL 保持兼容。上述是已接入的首批边界，尚未将全部历史模块拆分为统一接口。


`StorageDiagnostics.overview(actorId)` 只读取数据库指标，不检测节点、不预留容量，
也不调用迁移。`DatabaseStorageDiagnostics` 每次调用执行管理员校验；
现有控制器继续先做管理员校验，保留原 URL、字段与返回条数限制。
现有集成测试和服务直接调用测试覆盖非管理员拒绝。

`overview` 继续供原管理 UI 使用。Agent 工具使用另外五类受限查询：节点状态、容量、事件、活动任务和策略。
每个方法再次鉴权，查询字段映射为明确 DTO，返回条数受限。`AgentToolRegistry` 负责工具白名单、严格参数、
限流与结果大小校验；手动工具 API 另限制请求体大小并拒绝重复 JSON 键。
完整管理视图不会直接传给工具或模型；有界模型循环和持久化审计见 [Agent Runtime](../agent-runtime.md)，审批只通过独立人工接口提交现有迁移任务。
见 [只读工具说明](../agent-tools.md)。

`WORKER_ENABLED=false` 关闭此进程的 Spring 定时任务，也阻止全局任务启动恢复。
统一的 `WorkerLifecycle` 同时检查 Worker 开关与维护模式：两者任意禁用恢复权限时，
上传 ASSEMBLING、下载队列、文件迁移、整节点迁移、AI/知识任务与未决计费状态保持原样，
不删除 ZIP、迁移或摄取任务的预留空间。默认单进程保持 `true`，保留原有中断恢复行为。

该开关不关闭 Flyway、节点注册与本地健康初始化、目录创建、HTTP 写接口或请求触发的 ZIP/整节点迁移，
也不是只读模式。维护命令仍可执行其显式操作。关闭唯一进程的 Worker 会使排队任务停留。
`WorkerLifecycle` 仅是单进程角色边界，没有分布式租约，不能防止两个 Worker 同时恢复任务。
多个应用进程仍存在上传暂存和任务状态假设，尚不支持多实例部署。
未来需要逐项验证数据库租约、fencing token、任务重领、对象并发、会话和共享临时文件。
