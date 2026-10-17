# CloudNest 私人云盘

基于 Vue 3、TypeScript、Java 21 和 Spring Boot 的多用户私人云盘。支持独立存储空间、文件管理、目录上传、分片续传、在线预览、文件分享、回收站、历史版本及文件夹 ZIP 下载。

## 服务器快速部署

需要 Linux 服务器、Git、OpenSSL、Docker Engine 和 Docker Compose 2.24.4+。无需预装 Java、Node.js、MySQL，也无需上传数据库。没有 Docker 时，先按 [完整部署手册](docs/deployment.md) 安装。

在服务器终端执行：

```bash
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
bash scripts/create-admin.sh owner
```

最后一条命令交互设置管理员密码，输入时不会显示字符。成功结果应包含 `"problems":0` 和 `"action":"create-admin"`。没有默认密码，管理员用户名为 `owner`。

在云服务器防火墙放行 **TCP 8088** 后，用浏览器打开：

```text
http://你的服务器公网IP:8088
```

使用 `owner` 和刚设置的密码登录。普通用户由管理员在“账号与安全 → 系统管理”生成邀请链接注册，默认关闭自由注册。

首次构建会下载镜像与依赖。初始化脚本生成独立随机密码，并通过 `.env` 选择公网端口配置，仅开放云盘 Web 端口；数据库、Redis 和后端端口不向宿主机发布。已有 `.env` 不会被覆盖。HTTP 不加密传输，公网长期使用可另行配置 HTTPS；基础部署不需要域名、证书或 Cloudflare。

完整步骤包括 Docker 安装、放行端口、修改端口、密码重置、备份和排障，见 [部署手册](docs/deployment.md)。

## 功能

- 文件与目录：多级目录、搜索、筛选、收藏、最近使用、列表/网格、批量移动和删除。
- 上传：目录上传、SHA-256 校验、同用户秒传、分片并发、暂停与断点续传。
- 下载：单文件下载、整文件夹和多文件夹 ZIP、跨目录挑选部分文件打包，分享页支持相同操作。
- 预览：图片、PDF、文本、Markdown 渲染、DOC/DOCX 阅读预览；部分音视频可由浏览器原生播放。
- 分享：文件/文件夹链接、有效期、提取码、下载权限与撤销。
- 数据管理：回收站恢复、保留策略、历史版本、容量配额。
- 账号管理：密码与登录会话、管理员邀请、用户配额和禁用。
- 系统更新：可选安装宿主机更新服务，从管理员页面检查 GitHub 并备份更新。

详细入口及限制见 [使用说明](docs/usage.md)。默认每用户 5 GiB、单文件最大 2 GiB；回收站及历史版本占用配额。ZIP 展开最多 1000 个文件、2000 个文件与目录、总大小 512 MiB，产物保留 1 小时。当前只支持一个后端实例。

## 更新与数据

服务器数据保存在 Docker 的 `mysql_data`、`app_data` 命名卷中；`.env` 保存部署密码。重建容器保留数据，**不要执行 `docker compose down -v`**。

手动升级前按 [部署手册](docs/deployment.md#备份与恢复) 备份，再执行：

```bash
git pull --ff-only
docker compose up -d --build --wait --wait-timeout 180
```

浏览器一键更新需要在服务器安装一次配套服务，详见 [系统更新说明](docs/system-updates.md)。已有部署拉取代码不会重写 `.env`；旧部署若仍仅监听回环地址，按部署手册的“已有部署启用 IP 访问”配置。

## 本机开发

需要 Java 21、Maven 3.9+、Node.js 22.13+ 或 24+。Windows 打开两个 PowerShell 窗口，在项目根目录分别执行：

```powershell
# 窗口 1
.\scripts\dev.ps1 server

# 窗口 2
.\scripts\dev.ps1 web
```

如果使用 CMD，执行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev.ps1 server`，另一个窗口将最后的 `server` 换成 `web`。

打开 <http://127.0.0.1:5173>，开发环境默认开放注册，使用 H2 与本地磁盘；数据保存在 `cloudnest-server/.data/`。Linux/macOS 开发可在 `cloudnest-server` 中执行 `mvn spring-boot:run`，另一个终端在 `cloudnest-web` 中执行 `npm ci`、`npm run dev`。

```bash
# 后端测试
mvn -f cloudnest-server/pom.xml test
# 前端类型检查及生产构建
cd cloudnest-web
npm ci
npm run build
# 前后端启动后执行浏览器测试，需要本机 Chrome
npm run test:e2e
```

## 文档与开源

| 文档 | 内容 |
| --- | --- |
| [部署手册](docs/deployment.md) | 从安装 Docker 到 IP＋端口访问、维护和备份 |
| [使用说明](docs/usage.md) | 上传、下载、分享、邀请和账号管理 |
| [系统更新](docs/system-updates.md) | 一键更新的安装、流程和故障处理 |
| [API](docs/api.md) / [OpenAPI](docs/openapi.json) | 接口和权限约定 |
| [贡献说明](CONTRIBUTING.md) / [安全反馈](SECURITY.md) | 开发贡献与问题报告 |

项目采用 [MIT 许可证](LICENSE)。仓库只包含源代码、部署配置、测试及必要说明；数据库、私人文件、密码、备份、构建产物和开发过程日志不上传。
