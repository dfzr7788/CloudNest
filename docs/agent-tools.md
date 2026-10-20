# 2.1 只读 Agent 工具层

[English](en/agent-tools.md) · [实施状态](connected-workspace.md)

这一阶段提供五个管理员诊断工具及手动查询界面，为后续 Agent Runtime 准备工具边界。
**当前不调用模型、不执行自主规划、不提供持久化 Agent run，也不执行迁移或节点修改。**
功能默认关闭；不需要开启 `AI_ENABLED` 或配置供应商密钥。

## 启用与使用

在部署 `.env` 中加入：

```dotenv
AGENT_TOOLS_ENABLED=true
```

按原部署方式重新创建应用容器后生效。源码部署先完成构建，预构建部署使用相同提交的镜像；
不要为了启用功能直接导入未经验证的其他版本。开发时也可设置相同环境变量后启动服务。

管理员打开“节点中心 → 只读诊断”，选择工具和返回条数，事件查询另填节点标识，
例如 `local`、`minio` 或节点列表中的 `s3:策略ID`。点击“执行只读查询”读取结果。
切换语言不清空当前结果。请求失败时清除此前的成功证据，防止旧结果被误认为新查询成功。
关闭开关并重启服务后工具不可调用，普通节点中心仍可使用。

| 工具 | 参数 | 返回内容 |
| --- | --- | --- |
| `get_node_status` | 可选 `limit` | 已记录的节点状态、启用标志、故障域、检测时间和过期标志；不触发网络检测 |
| `get_storage_capacity` | 可选 `limit` | 逻辑占用、每节点物理占用/预留/预算；未设预算的余量为 `null` |
| `get_node_events` | 必填 `backend`，可选 `limit` | 指定已注册节点的历史健康事件；无事件返回空列表 |
| `get_active_storage_jobs` | 可选 `limit` | `QUEUED` / `RUNNING` 任务；排除文件名、对象路径、用户标识和原始供应商错误 |
| `explain_storage_policy` | `{}` | 实际策略、最低可靠性、默认节点和确定性规则标识；不执行调度或模拟校验 |

`limit` 默认为 20，允许 1–100；`hasMore=true` 明确表示仍有未返回的记录。
该接口是有界诊断视图，不是完整事件导出或同步 change feed，没有分页游标。

## API 与权限

```text
GET  /api/admin/agent/tools/available
POST /api/admin/agent/tools/{name}/invoke
```

接口使用现有会话认证，POST 还需当前 CSRF token。请求体直接使用工具参数对象，
例如 `{"backend":"local","limit":20}`；不接受 `userId`、任意 SQL、Shell 或额外参数。
`available` 返回每个工具的严格参数 schema，关闭功能时返回 `enabled=false` 和空工具列表。
控制器、注册表和只读查询服务各自保留管理员校验；权限撤销后下一次调用失败。

- 请求体最多 4 KiB，必须为 `application/json`；拒绝重复键、尾随 JSON、数组和空值。
- 每名管理员每分钟最多 30 次注册表调用尝试；使用项目现有 Redis / 本地限流器。
- 完整成功响应最多 32 KiB。超限返回 422，调用者可明确减小 `limit` 后重试；服务不隐式重试。
- 未知工具/节点返回 404，参数错误 400，关闭功能 409，权限不足 403，限流 429。
- 成功响应禁止缓存。新接口和类型已同步到 [OpenAPI](openapi.json)。

## 结果如何解释

响应含 `invocationId`、`tool`、`observedAt`、`source=DATABASE_METADATA`、
`consistency=NON_ATOMIC_METADATA_READ`、`trustedAsInstructions=false` 和结构化 `data`。
`observedAt` 是本次查询时间；节点实际检测时间另见 `checkedAt`。
多项指标可能来自不同事务快照。管理员预算不是 S3 主机的实时剩余磁盘空间，
`ONLINE` 记录也不能替代当下的实际文件读回与 SHA-256 校验。

工具输出采用明确 DTO 字段白名单，不把完整管理视图传给未来模型。
节点名称等文本始终是数据，在界面中转义显示，不执行 HTML、Markdown 指令或模型指令。
有界模型循环另外保存这些观察结果，并使用固定的白名单、授权和人工审批边界；见 [Agent Runtime](agent-runtime.md)。

成功调用仅在现有服务日志中记录调用者、工具名、调用标识与响应字节数；
不记录参数、结果正文、文件或凭据。日志遵循部署现有保留策略，不等同于持久化 Agent 步骤审计。
刷新页面会丢弃当前手动查询结果；Agent 运行的持久化步骤、费用与审批记录另见 [运行说明](agent-runtime.md)。

## 验证与下一步

后端 `AgentToolsTest` 覆盖真实 H2 元数据读取、只读行为、独立授权、功能关闭、
严格参数、请求/结果大小限制、限流和 HTTP 认证/CSRF。浏览器测试覆盖中英文、
390px/1440px、恶意文本转义、失败清空旧证据和关闭状态。
测试不调用付费供应商，也不替代 MySQL、真实双机或 VPS 发布验收。

模型网关、有界执行器和步骤审计已在后续开发中实现，复用既有 AI 任务表。审批表仅在独立开发配置中加载；生产模型与稳定发布继续遵守 G0 门槛。
