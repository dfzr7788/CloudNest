# CloudNest

简体中文 · [English](README.en.md)

CloudNest 是可自行部署的多用户私人云盘，支持个人文件管理、团队协作、多节点存储与数据保护，也可接入用户自己的 AI API 辅助阅读和诊断。

当前版本为 `2.3.0`，为首个正式稳定版本。后端目前支持单实例部署；Windows 同步、WebDAV 盘符和公网直传的适用范围见[功能说明](docs/features.md#功能边界)。

## 主要功能

| 模块 | 提供的能力 |
| --- | --- |
| 文件管理 | 列表与网格、跨目录选择、复制与移动、收藏、标签、名称与正文搜索、回收站 |
| 上传下载 | 文件夹上传、分片续传、SHA-256 校验、同名处理、批量 ZIP 下载与任务进度 |
| 阅读与分享 | 图片、PDF、Markdown、Office 阅读预览、历史版本、提取码分享、集合分享和文件收集 |
| 团队空间 | 成员邀请与角色、目录权限、独立团队配额、团队回收站、审计和存储贡献 |
| 数据保护 | 目录快照、历史比较、副本恢复、定时策略、完整性巡检与损坏报告 |
| 存储节点 | 本地与 S3 兼容存储、容量预算、调度、迁移、异域双副本、故障切换与修复 |
| 账号安全 | 邀请注册、会话管理、TOTP、邮箱验证与找回、WebDAV 应用密码 |
| 可选扩展 | OnlyOffice、Windows 同步客户端、AI 阅读与知识库、只读诊断、有界 Agent 与人工审批 |

详细入口、权限、配额和限制见[功能说明](docs/features.md)；日常操作见[使用说明](docs/usage.md)。AI 默认关闭，保存配置或上传文件不会自动调用模型，使用前需确认外部 API 的数据发送与费用。

## 快速部署

Linux 服务器需要 Git、OpenSSL、Python 3、Docker Engine 和 Docker Compose 2.24.4+；宿主机无需安装 Java、Node.js 或 MySQL。

```bash
git clone --branch v2.3.0 https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
git switch -c main
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
bash scripts/create-admin.sh owner
```

最后一步交互设置管理员密码，`owner` 为示例用户名，没有默认密码。维护结果应显示 `problems: 0` 和 `action: create-admin`。放行 TCP 8088 后访问 `http://服务器公网IP:8088`。

初始化脚本生成随机密码且不覆盖已有 `.env`。默认仅公开 Web 端口，关闭公开注册，由管理员创建邀请。公网使用请配置 HTTPS，并设置 `SECURE_COOKIES=true`；普通 HTTP 使用 `false`。镜像部署、端口、配置和排障见[部署手册](docs/deployment.md)。

## 数据与更新

`mysql_data` 保存数据库，`app_data` 保存本地文件、分片和签名密钥，`.env` 保存部署配置。远程 S3 对象也需备份。数据库、对象、密钥和运行版本应成套备份与恢复。

**不要执行 `docker compose down -v`，它会删除业务数据卷。**

升级前按[备份与恢复](docs/deployment.md#备份与恢复)创建并验证一致性备份。源码部署随后执行：

```bash
git fetch origin tag v2.3.0
git merge --ff-only v2.3.0
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
```

预构建镜像与网页更新见[系统更新](docs/system-updates.md)。网页更新需安装宿主机更新服务；数据库迁移后，仅切回旧镜像不能恢复旧数据库。

## 本地开发

前端使用 Vue 3、TypeScript、Element Plus 和 Pinia，后端使用 Java 21、Spring Boot、MyBatis Plus 和 Flyway。Docker 使用 MySQL 与 Redis，本地开发可使用 H2 和本地存储。

需要 Java 21、Maven 3.9+、Node.js 22.13+ 或 24。在项目根目录的两个 PowerShell 窗口分别运行：

```powershell
.\scripts\dev.ps1 server
.\scripts\dev.ps1 web
```

访问 `http://127.0.0.1:5173`。本地默认开放注册。跨平台启动、环境变量及验证命令见[开发指南](docs/development.md)。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [功能说明](docs/features.md) / [使用说明](docs/usage.md) | 当前能力、限制与操作入口 |
| [部署手册](docs/deployment.md) / [双节点部署](docs/deployment-dual-node.md) | 安装、备份、恢复与节点部署 |
| [进阶配置](docs/advanced-features.md) / [存储节点](docs/storage.md) | Office、WebDAV、邮件、存储调度与副本 |
| [AI](docs/ai.md) / [诊断工具](docs/agent-tools.md) / [Agent 与审批](docs/agent-runtime.md) | 模型配置、诊断与受控任务 |
| [同步协议](docs/sync.md) / [Windows 客户端](clients/windows-sync/README.md) | 同步配置和客户端使用 |
| [轻量存储节点](clients/storage-node/README.md) | 贡献节点安装与目录边界 |
| [系统更新](docs/system-updates.md) / [开发指南](docs/development.md) | 维护与开发 |
| [API](docs/api.md) / [团队与数据保护 API](docs/workspace-api.md) / [OpenAPI](docs/openapi.json) | 接口与权限契约 |
| [贡献说明](CONTRIBUTING.md) / [安全反馈](SECURITY.md) | 参与开发与漏洞报告 |

项目采用 [MIT 许可证](LICENSE)。
