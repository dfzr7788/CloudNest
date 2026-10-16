# CloudNest 私人云盘

Vue 3 + TypeScript / Java 21 + Spring Boot 的多用户私人云盘。包含目录管理、文件上传下载、图片/PDF/文本预览、回收站、配额、分片续传、同用户秒传、带提取码的分享与批量 ZIP 下载。

现已按改进计划增加账号密码与会话管理、目录上传、批量整理与撤销、收藏/最近使用/筛选、文件夹分享、历史版本、缩略图、回收站保留策略及管理员邀请/配额/禁用。使用方法见 [新版使用说明](docs/improvement-usage.md)，最新实际验证见 [改进版报告](docs/improvement-report.md)。

## 本机启动

需要 Java 21、Maven 3.9+、Node.js 22.13+ 或 24+（本次验证使用 Node 24）。打开两个终端：

```powershell
# 终端 1：项目根目录
./scripts/dev.ps1 server

# 终端 2
cd cloudnest-web
npm ci
npm run dev
```

访问 <http://127.0.0.1:5173>，自行注册账号。没有默认管理员和默认账号。前端将 `/api` 代理到本机 8080。

默认使用 H2 文件数据库和本地磁盘，不依赖 MySQL、Redis 或对象存储即可开发。数据、对象、上传分片和会话签名密钥保存在 `cloudnest-server/.data/`；请连同数据库一起备份，不要删除 `jwt.key`。服务重启后已提交文件与未完成上传会话继续保留。

## 构建与验证

```powershell
./scripts/dev.ps1 test
./scripts/dev.ps1 build

# 前后端启动后执行浏览器测试，需要本机 Chrome
cd cloudnest-web
npm run test:e2e
```

后端发布包：`cloudnest-server/target/cloudnest-server-1.0.0.jar`。前端发布目录：`cloudnest-web/dist/`。

```powershell
# 独立运行后端；工作目录影响默认 .data 位置，建议明确指定
java -jar cloudnest-server/target/cloudnest-server-1.0.0.jar --cloudnest.data-dir=G:/CloudNest/cloudnest-server/.data '--spring.datasource.url=jdbc:h2:file:G:/CloudNest/cloudnest-server/.data/cloudnest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0'
```

## 容器部署

服务器安装 Docker Engine 与 Docker Compose 2.24.4+ 后，在克隆的仓库根目录运行。无需预装 Java、Node.js、MySQL 或导入数据库备份：

```sh
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
bash scripts/create-admin.sh owner
```

初始化脚本生成三组独立随机密码，已有 `.env` 不会被覆盖。创建管理员时交互输入密码，默认关闭公开注册。数据库表由 Flyway 首次启动自动创建；源码中的 SQL 迁移文件需要上传，真实数据库和用户文件不上传。

默认只发布服务器本机 `127.0.0.1:8088`，生产使用 MySQL 8.4、Redis 7.4 和本地私有对象卷。远程试用可在自己电脑执行 `ssh -L 8088:127.0.0.1:8088 用户@服务器地址`，保持连接后打开 `http://127.0.0.1:8088`。公网 HTTPS、持久卷、备份恢复和升级见 [部署手册](docs/deployment.md)。首次部署需要下载基础镜像与依赖并构建，耗时取决于服务器网络；Linux 容器部署仍需目标环境实际验收。

## 开源与发布

管理员面板提供邀请链接复制、分区用户管理和“系统更新”。首次在服务器执行 `bash scripts/install-updater.sh` 启用配套服务，此后可从网页检查 GitHub main 并一键备份、更新、查看结果；详见 [一键更新说明](docs/system-updates.md)。

项目采用 [MIT 许可证](LICENSE)，第三方依赖保留各自许可证。欢迎阅读 [贡献说明](CONTRIBUTING.md) 和 [安全反馈说明](SECURITY.md)。上传 GitHub 的具体步骤与排除范围见 [公开发布指南](docs/publishing.md)。

## 功能边界

- 默认每用户 5 GiB；按逻辑文件大小累计，回收站仍占空间。
- 普通上传最大 100 MiB；前端统一使用分片接口，默认单文件最大 2 GiB，8 MiB 分片、3 路并发。
- 暂停与刷新后续传：重新选择原文件，浏览器在 Worker 中核验 SHA-256，仅上传缺失分片。
- 秒传和去重只发生在同一用户范围，服务器重新校验首次上传内容。
- 支持 PNG/JPEG/GIF/WebP/BMP、PDF、2 MiB 内纯文本预览。其他文件仍可保存下载；HTML/SVG 不直接执行。
- 分享最长 30 天，可选提取码、仅预览/可下载、随时撤销；删除源文件会撤销分享。
- 批量 ZIP 最多 100 个文件、总大小 512 MiB；每用户同时最多两个打包任务，产物保留一小时。
- 运行一个后端实例，暂不支持多实例部署、客户端同步、Office 编辑与跨用户去重。
- 文件夹是动态分享，单文件分享固定创建时的内容；历史版本计入配额，最多保留 20 份。具体改进边界见新版使用说明。
- MinIO 适配和迁移工具已提供；2026-10-16 官方社区下载地址返回 410，真实 MinIO 验证状态和替代部署方式见测试报告及部署手册。

## 项目记录

| 文档 | 用途 |
| --- | --- |
| [plan.md](plan.md) | 原计划、实施状态及后续执行依据 |
| [改进与新增功能计划](docs/improvement-plan.md) | 下一版优先级、功能边界、阶段安排与逐项预期结果 |
| [新版使用说明](docs/improvement-usage.md) | 新增功能的入口、操作和限制 |
| [改进版验收报告](docs/improvement-report.md) | 本轮真实测试、恢复结果与未覆盖项 |
| [改进版验收预期](docs/improvement-acceptance.md) | 每个新增用例的输入、预期和数据检查 |
| [验收清单](docs/acceptance.md) | 每项预期结果、操作、实际状态和测试对应关系 |
| [测试报告](docs/test-report.md) | 实际通过项、失败修复及环境限制 |
| [执行日志](docs/execution-log.md) | 开发过程与发现的问题 |
| [设计说明](docs/design.md) | 数据模型、流程、状态机和页面结构 |
| [API 说明](docs/api.md) | 请求、响应、权限和错误语义 |
| [OpenAPI](docs/openapi.json) | 可导入 Apifox 的接口清单 |
| [部署手册](docs/deployment.md) | 启动、TLS、备份恢复、迁移与维护 |
| [实施决策](docs/decisions.md) | 与原计划的差异及原因 |

详细原始验证产物保存在开发者本机 `docs/evidence/`，不随公开仓库分发；报告中的原始证据链接在其他克隆中可能不可用，见该目录说明。待验证环境不会标为通过。

