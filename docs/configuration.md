# 配置参考

[文档中心](README.md) · [部署手册](deployment.md) · [排障](troubleshooting.md)

本页按当前仓库配置说明生效方式。表中默认值不是容量或性能保证。

## 配置写在哪里

| 位置 | 作用 | 生效方式 |
| --- | --- | --- |
| 项目根目录 `.env` | Compose 插值、密码、端口及已转发的服务变量 | 校验后重新创建受影响容器 |
| Compose 的 `server.environment` | 将变量传入后端进程 | 重新创建 server |
| 本地开发进程环境变量 | 覆盖 application.yml 中对应默认值 | 重启开发后端 |
| 管理员网页设置 | 邮件、Office、存储连接与持久化站点开关 | 按界面保存/测试；站点开关保存后立即生效 |
| 用户 AI 设置 | 当前用户的连接、密钥和模型 | 用户保存后使用；保存本身不调用模型 |

**仅向 `.env` 添加变量，不代表容器会收到它。** 必须确认 Compose 已在 `environment` 中转发。例如 `USER_QUOTA`、`MAX_FILE_SIZE`、`PART_SIZE` 在 application.yml 中可配置，但基础 Compose 没有转发；容器部署需通过自己的叠加配置显式传入。

网页保存的站点 AI/诊断/Agent 开关优先于环境启动默认值。环境改成 false 后，已有网页设置仍可能为 true；要关闭功能应在对应管理员页面保存关闭。

## 入口与账号

| 变量 | 默认/初始化值 | 说明 |
| --- | --- | --- |
| `COMPOSE_FILE` | 初始化为基础配置加 `deploy/compose.public.yml` | Linux 以冒号连接叠加文件；保留已有更新器或自定义叠加文件 |
| `WEB_PORT` | `8088` | Web 宿主机端口；基础配置绑定回环，public 叠加绑定所有接口 |
| `SECURE_COOKIES` | `false` | HTTP 使用 false；HTTPS 入口使用 true |
| `REGISTRATION_OPEN` | Docker false，本地开发 true | Docker 默认通过管理员邀请注册 |
| `DATABASE_PASSWORD` | 必填，初始化生成随机值 | 应用数据库密码，不是网站登录密码 |
| `MYSQL_ROOT_PASSWORD` | 必填，独立随机值 | MySQL 管理凭据 |
| `REDIS_PASSWORD` | 必填，独立随机值 | Redis 访问凭据 |

`scripts/init-env.sh` 只在 `.env` 不存在时创建它。已有数据库卷的密码不会因为修改 `.env` 自动同步；修改数据库凭据需同时完成数据库侧变更，不能用重新生成 `.env` 修复登录问题。

## 存储与容量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STORAGE_BACKEND` | `local` | 默认本地存储；外部存储设置见[存储手册](storage.md) |
| `MINIO_ENDPOINT` | Compose 为 `http://minio:9000` | 示例地址；基础 Compose 没有部署名为 minio 的服务 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 空 | 由自行部署的兼容存储提供 |
| `MINIO_BUCKET` | `cloudnest` | 对应已准备的桶 |
| `ZIP_MAX_BYTES` | `536870912`（512 MiB） | 一次 ZIP 的文件总大小限制 |
| `ZIP_MAX_FILES` / `ZIP_MAX_ENTRIES` | `1000` / `2000` | 文件数 / 文件与目录总数 |
| `ZIP_RETENTION_HOURS` | `1` | ZIP 任务产物保留时间 |
| `ZIP_RESERVE_BYTES` | `268435456`（256 MiB） | ZIP 磁盘保留空间 |
| `ZIP_WORKERS` | `1` | ZIP 工作线程数；提高前先确认磁盘和 CPU 余量 |
| `ZIP_COMPRESSION_LEVEL` | `1` | 0 不压缩，1 快速压缩，9 更耗 CPU |

个人逻辑配额默认 5 GiB，单文件默认 2 GiB，分片默认 8 MiB。调整单文件上限不能自动解决 WebDAV 整文件请求、外层反向代理和磁盘容量限制。用户逻辑空间、节点预算、物理磁盘和供应商桶配额是不同指标。

## AI 与后台任务

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AI_ENABLED` | false | 站点 AI 的启动默认值；网页 AI 设置可持久化覆盖 |
| `AGENT_TOOLS_ENABLED` | false | 只读诊断默认开关；手动诊断无需模型 |
| `AGENT_RUNTIME_ENABLED` | false | 实验 Agent 默认开关；还依赖 AI、诊断及模型配置 |
| `WORKER_ENABLED` | true | 后台调度及启动时全局中断任务恢复 |

关闭 worker **不是只读模式，也不提供多实例支持**，请求触发的工作仍可能执行。生产基础部署保持单个后端实例。用户 API Key 不放入共享 `.env`；AI 网络限制与密钥恢复见[AI 部署与恢复](deployment.md#ai-部署与恢复)。

## 应用修改并确认结果

在项目根目录保存配置后执行：

```bash
docker compose config --quiet
docker compose up -d --no-deps server web
docker compose ps
```

纯端口修改只需重建 web；涉及新代码则按原源码或预构建镜像流程更新。`restart` 不会重新读取容器创建时的环境。不要把完整 `docker compose config` 输出公开，它可能包含展开后的密码。

修改后验证相应行为：端口检查访问地址，注册检查邀请流程，ZIP 用小文件打包，集成配置使用网页连接测试。配置成功不等于历史文件已迁移或备份已完成。
