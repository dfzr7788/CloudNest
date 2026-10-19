# 开发指南

## 环境与目录

需要 Java 21、Maven 3.9+、Node.js 22.13+ 或 24，以及 npm。浏览器测试使用本机 Google Chrome；维护脚本测试需要 Python 3，完整 Docker 集成验证还需要 Docker Engine 与 Compose。依赖版本分别由 `pom.xml` 和 `package-lock.json` 管理。

| 目录 | 用途 |
| --- | --- |
| `cloudnest-web/src` | Vue 页面、文件操作状态、上传队列、预览及设置面板 |
| `cloudnest-web/e2e` | Playwright 浏览器流程测试 |
| `cloudnest-server/src/main` | Java 服务、配置与分数据库 Flyway 迁移 |
| `cloudnest-server/src/test` | 服务与接口测试 |
| `deploy` / `docker-compose.yml` | 镜像与部署叠加配置 |
| `scripts` | 开发、维护、发布检查、备份与更新工具 |
| `docs` | 使用、运维与接口说明 |

## 启动前后端

Windows 在根目录的两个 PowerShell 窗口分别运行：

```powershell
.\scripts\dev.ps1 server
.\scripts\dev.ps1 web
```

脚本会为前端首次安装依赖，后端 Maven 缓存位于 `.tools/m2`。若系统策略不允许运行脚本，可使用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev.ps1 server`，另一个窗口将末尾参数改为 `web`。

也可以直接在两个终端执行，适用于 Linux/macOS：

```bash
# 终端 1，项目根目录
cd cloudnest-server
mvn spring-boot:run
```

```bash
# 终端 2，项目根目录
cd cloudnest-web
npm ci
npm run dev
```

前端为 <http://127.0.0.1:5173>，后端默认端口 8080；Vite 将 `/api` 和 `/dav` 代理到后端。本地默认开放注册、关闭 AI、关闭 Redis，使用 H2 和磁盘存储。从 `cloudnest-server` 启动时数据位于 `cloudnest-server/.data/`；直接从其他工作目录启动 Java 时相对数据路径随工作目录变化。

配置以 `cloudnest-server/src/main/resources/application.yml` 为准，可通过环境变量设置 `PORT`、`DATA_DIR`、`DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`、`REGISTRATION_OPEN` 和 `AI_ENABLED` 等。Docker 的变量映射以 Compose 为准；不能假定 application.yml 支持的每个变量都已由 Compose 转发。本地环境不要连接生产数据库或使用真实用户的 API Key。

## 类型检查、构建与后端测试

```bash
# 项目根目录
mvn -B -f cloudnest-server/pom.xml test
# 完整后端校验/打包
mvn -B -f cloudnest-server/pom.xml verify
```

```bash
# cloudnest-web 目录
npm ci
npx vue-tsc --noEmit
npm run build
```

`npm run build` 已包含类型检查和 Vite 生产构建。Windows 的 `scripts/dev.ps1 test` 执行后端测试与前端构建，`build` 执行后端打包、前端依赖安装与构建；它们都不会启动浏览器 E2E。

## 浏览器测试

测试不会自动启动服务。先准备独立的本地测试实例，默认开放注册；涉及 AI 的流程还需在启动后端前设置 `AI_ENABLED=true`。测试使用受控上游模拟；无需真实供应商 Key。测试会创建账号、文件、分享与任务，请勿指向生产环境。

前后端启动后，在 `cloudnest-web` 中运行：

```bash
npm run test:e2e
# 仅运行一个相关测试文件
npx playwright test e2e/preview-selection.spec.ts
```

默认地址为 `http://127.0.0.1:5173`，可通过 `CN_E2E_BASE_URL` 覆盖。使用生产产物验证时先 `npm run build`，另一个终端运行 `npm run preview -- --port 4173`，再在测试终端设置地址。PowerShell 示例：

```powershell
$env:CN_E2E_BASE_URL='http://127.0.0.1:4173'
npm run test:e2e
```

可选覆盖项：

- `CN_HTTP_PREVIEW_ORIGIN=http://cloudnest.test:4173`：验证普通 HTTP 下的预览；测试配置将该域名映射到本机，需启动对应 preview 服务。未设置时相应测试会跳过。
- `CN_PREVIEW_DOC`：指向本机用于旧版 DOC 预览验证的绝对文件路径；按测试用例要求准备合成样本。

具体浏览器、超时、报告和截图路径见 `playwright.config.ts`。当前保留失败截图和 trace，JSON 报告与运行产物位于根目录 `.tools/ui-ux-refactor/`。运行结果必须区分通过、失败、跳过和未运行，不将缺少环境导致的跳过算作通过。

## 部署工具与发布检查

从项目根目录运行：

```bash
python scripts/test-updater.py
python scripts/test-backup.py
python scripts/check-publication.py
python scripts/check-api-contract.py
```

Docker 集成与隔离恢复验证为 `python scripts/test-compose.py`，需要相应运行环境和磁盘空间。执行前阅读脚本，避免与本地正在运行的同名 Compose 项目冲突。

GitHub Actions 的 `.github/workflows/release.yml` 运行后端校验、前端构建和维护脚本测试；非 PR 运行还执行 Docker 集成验证并发布 Linux amd64 镜像。工作流没有运行 Playwright，浏览器验收需要单独执行。

提交要求、迁移规则及隐私检查见 [贡献说明](../CONTRIBUTING.md)。

## 接口契约维护

`docs/openapi.json` 是可直接导入的接口契约，修改接口时同时维护该文件和 `docs/api.md`。已移除会覆盖新增字段的旧导出脚本。`python scripts/check-api-contract.py` 只读核对控制器路由、路径参数和关键请求字段，CI 同步运行；该检查不替代请求/响应的集成测试。
