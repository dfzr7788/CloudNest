# CloudNest

简体中文 · [English](README.en.md) · [文档中心](docs/README.md)

CloudNest 是可自行部署的多用户私人云盘，用于管理个人文件、与团队共享资料，并通过历史版本、目录快照和独立备份保护数据。文件可存放在本地磁盘或 S3 兼容存储；AI 为可选能力，使用用户自己的 API 连接。

当前版本为 **1.0.0**，后端支持**单实例部署**。适合需要自主控制账号、文件和存储位置的个人或小团队。多存储节点不等于多个业务后端高可用；具体容量需在目标机器上验证。

## 能做什么

| 场景 | 主要能力 |
| --- | --- |
| 整理文件 | 目录、收藏、标签、名称与正文搜索、跨目录选择、复制移动、回收站 |
| 上传和下载 | 文件夹上传、分片续传、SHA-256 校验、同名策略、后台 ZIP 下载任务 |
| 阅读与分享 | 图片/PDF/Markdown/Office 阅读预览、历史版本、提取码分享、集合分享、只上传收集链接 |
| 团队协作 | 成员邀请、角色与目录权限、独立团队配额、审计和团队回收站 |
| 数据保护 | 目录快照、差异比较、副本恢复、完整性巡检、存储迁移与异域副本 |
| 账号与集成 | 会话管理、TOTP、邮件找回、WebDAV 应用密码、可选 OnlyOffice |
| AI 辅助 | 手动摘要、文档问答、知识库检索，以及受控诊断和人工审批 |

稳定范围与数量限制见[功能说明](docs/features.md)。Windows 同步、公网直传和有界 AI Agent 仍按实验能力提供；没有零知识加密、原生虚拟盘或多业务实例支持。快照和双副本不能替代独立备份。

AI 默认关闭。上传文件、保存配置不会自动调用模型；每次使用前确认目的地、发送范围和费用。

## 开始使用

| 你的任务 | 从这里开始 |
| --- | --- |
| 第一次安装 | [部署手册](docs/deployment.md) → [上线验收](docs/operations.md) |
| 已有账号，开始管理文件 | [使用说明](docs/usage.md) |
| 修改配置或启用邮件、Office、WebDAV | [配置参考](docs/configuration.md) → [进阶配置](docs/advanced-features.md) |
| 更新、备份或恢复 | [系统更新](docs/system-updates.md) / [备份与恢复](docs/deployment.md#备份与恢复) |
| 遇到问题 | [故障排查](docs/troubleshooting.md) |
| 查找全部专题、客户端与 API | [文档中心](docs/README.md) |

## Linux 快速部署

需要 Git、OpenSSL、Python 3、Docker Engine 和 Docker Compose 2.24.4+。宿主机无需安装 Java、Node.js 或 MySQL。以下是源码构建路线；Linux amd64 可按[部署手册](docs/deployment.md)选择预构建镜像。

```bash
git clone --branch v1.0.0 https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
git switch -c main
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
bash scripts/create-admin.sh owner
```

最后一步交互设置管理员密码，`owner` 是示例用户名，没有默认密码。确认维护结果为 `problems: 0`、`action: create-admin`。放行 Web 端口后访问 `http://服务器公网IP:8088`。

初始化脚本生成随机密码，不覆盖已有 `.env`。默认仅公开 Web 端口、关闭公开注册，由管理员邀请用户。HTTP 用于初始连通性检查；正式公网使用配置 HTTPS，并设置 `SECURE_COOKIES=true`。普通 HTTP 使用 false。现有代理部署先核对监听方案，不直接照搬公网叠加配置。

登录后先用测试文件完成上传、下载校验、分享撤销和删除恢复，再按[上线检查](docs/operations.md)验证备份与隔离恢复。

## 数据保存与升级

| 数据 | 位置与用途 |
| --- | --- |
| 账号、目录、权限等元数据 | `mysql_data` 数据卷 |
| 本地对象、上传临时数据与签名密钥 | `app_data` 数据卷 |
| 部署配置与数据库凭据 | 仓库根目录 `.env` |
| 远程对象 | 自行配置的 S3 兼容桶，也需纳入备份 |

数据库、文件对象、密钥、配置和程序版本应成套备份与恢复。**不要执行 `docker compose down -v`，它会删除业务数据卷。**

升级前阅读目标版本说明并完成一致性备份。源码与镜像部署的操作不同，按[部署手册](docs/deployment.md#更新项目)或[系统更新](docs/system-updates.md)执行。数据库迁移后，仅切回旧镜像不能恢复旧数据库。

## 本地开发

前端使用 Vue 3、TypeScript、Element Plus 和 Pinia；后端使用 Java 21、Spring Boot、MyBatis Plus 和 Flyway。Docker 使用 MySQL 与 Redis，本地开发可使用 H2 和本地存储。

准备 Java 21、Maven 3.9+、Node.js 22.13+ 或 24。在项目根目录的两个 PowerShell 窗口分别执行：

```powershell
.\scripts\dev.ps1 server
.\scripts\dev.ps1 web
```

访问 `http://127.0.0.1:5173`。本地默认开放注册，数据与生产环境隔离。跨平台启动、测试环境和发布检查见[开发指南](docs/development.md)。

## 发布与参与

[1.0.0 发布说明](docs/releases/1.0.0.md)记录稳定范围及升级边界；[本地验收记录](docs/releases/1.0.0-acceptance-20261028.md)列明已执行和未验证事项，不代表生产 SLA。

提交问题或改动前阅读[贡献约定](CONTRIBUTING.md)。可利用漏洞按[安全反馈](SECURITY.md)私下报告。项目采用 [MIT 许可证](LICENSE)。
