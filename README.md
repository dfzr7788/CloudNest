# CloudNest

可自行部署的多用户私人云盘。以文件管理为核心，提供分片续传、跨目录选择、在线预览、公开分享、历史版本与回收站；可选接入用户自己的 AI API，辅助阅读和检索资料。

前端使用 Vue 3、TypeScript、Element Plus 和 Pinia，后端使用 Java 21、Spring Boot、MyBatis Plus 和 Flyway。开发环境可直接使用 H2；Docker 部署使用 MySQL、Redis 和独立文件数据卷。当前支持单个后端实例。

## 能做什么

| 场景 | 能力 |
| --- | --- |
| 日常文件管理 | 列表/网格、名称搜索与筛选、收藏和最近使用、标签与正文搜索、复制/剪切/粘贴、跨目录批量操作 |
| 上传与下载 | 文件夹上传、SHA-256 校验、分片并发和暂停恢复、同名处理、文件夹及多项 ZIP 打包、上传与下载任务进度 |
| 阅读与版本 | 图片、PDF、文本、Markdown、Office 阅读预览、部分音视频；历史版本下载与恢复；可选 OnlyOffice 编辑 |
| 对外分享 | 文件、目录和集合分享，提取码、有效期、下载权限、撤销与统计；只上传的文件收集链接 |
| 账号与管理 | 配额、回收站、登录会话、TOTP、邮箱验证与找回、WebDAV 应用密码、管理员邀请和用户管理 |
| 可选扩展 | 多 S3 兼容存储策略与校验迁移、宿主机更新与备份服务、用户自带 API 的 AI 阅读助手和知识库 |

AI 默认关闭。开启后可手动生成摘要、标签建议，进行文档问答、知识库索引与检索、版本变化解释和逐项确认的整理。保存 API 配置和上传文件不会自动调用模型；收费请求由用户显式确认。支持范围、数据去向与费用限制见 [AI 使用说明](docs/ai.md)。

## 快速部署

Linux 服务器需要 Git、OpenSSL、Python 3、Docker Engine 和 Docker Compose 2.24.4+，无需在宿主机安装 Java、Node.js 或 MySQL。

```bash
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
bash scripts/create-admin.sh owner
```

最后一步交互设置管理员密码；`owner` 是此示例选择的用户名，没有默认登录密码。确认维护结果中 `problems` 为 `0`、`action` 为 `create-admin`。在防火墙放行 TCP 8088 后，打开 `http://服务器公网IP:8088` 登录。

初始化脚本生成独立随机密码，不覆盖已有 `.env`；新配置只公开 Web 端口，数据库、Redis 和后端不向宿主机发布。默认关闭公开注册，管理员在“账号与安全 → 系统管理”创建邀请。公网长期使用请配置 HTTPS，并相应启用 `SECURE_COOKIES`；普通 HTTP 保持 `false`。

Docker 安装、预构建镜像、端口配置、管理员维护和排障见 [部署手册](docs/deployment.md)。Linux amd64 可使用当前提交对应的校验镜像，其他架构使用源码构建。

## 数据与更新

Docker 的 `mysql_data` 和 `app_data` 保存数据库、文件、分片和签名密钥，`.env` 保存部署配置。**不要执行 `docker compose down -v`，它会删除业务数据卷。** 远程 S3 对象也必须纳入备份；数据库、对象、密钥和版本应成套恢复。

升级前按 [备份与恢复](docs/deployment.md#备份与恢复) 创建并验证一致性备份，然后在源码部署中执行：

```bash
git pull --ff-only
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
```

已选择预构建镜像的部署按 [系统更新说明](docs/system-updates.md) 操作。网页一键更新需要先安装宿主机更新服务。数据库迁移后，单独切回旧镜像不能完成数据回退。

## 本机开发

需要 Java 21、Maven 3.9+ 和 Node.js 22.13+ 或 24。Windows 在项目根目录的两个 PowerShell 窗口分别运行：

```powershell
.\scripts\dev.ps1 server
.\scripts\dev.ps1 web
```

打开 <http://127.0.0.1:5173>。本地默认开放注册，使用 H2 和本地存储。跨平台启动、环境变量、类型检查、后端与浏览器测试见 [开发指南](docs/development.md)。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [使用说明](docs/usage.md) | 界面入口、文件操作、上传恢复、预览、分享与账号设置 |
| [AI 使用说明](docs/ai.md) | API 配置、阅读助手、知识库、计费与数据保留 |
| [部署手册](docs/deployment.md) | 安装、配置、升级、备份、恢复与维护 |
| [进阶配置](docs/advanced-features.md) | Office、WebDAV、邮件、双重验证、存储与正文检索 |
| [系统更新](docs/system-updates.md) | 宿主机更新服务、预构建镜像与故障处理 |
| [开发指南](docs/development.md) | 目录结构、开发启动与验证命令 |
| [API 契约](docs/api.md) / [OpenAPI](docs/openapi.json) | 接口、认证、权限与限制 |
| [贡献说明](CONTRIBUTING.md) / [安全反馈](SECURITY.md) | 贡献流程与漏洞报告 |

项目采用 [MIT 许可证](LICENSE)。仓库保留源代码、部署配置、测试和必要文档；密码、私人文件、数据库、备份和开发运行产物不提交。
