# GitHub 公开发布与服务器快速部署

## 上传内容

上传前后端源码、测试源码、SQL 迁移、Dockerfile、Compose、`.env.example`、部署脚本、文档及 MIT LICENSE。数据库迁移只描述表结构，是全新部署必需的代码，不含真实账号或文件。

不上传运行数据库、上传文件、JWT 密钥、`.env`、证书私钥、备份、构建目录、依赖目录及原始测试证据。这些由 `.gitignore` 排除，原文件仍保留在本机。忽略规则不会自动移除曾经提交的文件；发布检查也扫描已跟踪文件。

## 首次上传

1. 在 GitHub 新建 Public 仓库，例如 CloudNest，不自动生成 README 或许可证。该项目已包含 MIT LICENSE。
2. 在项目根目录检查（需要 Python 3）：

   ```sh
   python scripts/check-publication.py
   git status --short
   git add .
   git diff --cached --stat
   git diff --cached
   ```

3. 确认没有真实配置、账号和用户内容后提交：

   ```sh
   git commit -m "Initial open source release"
   git branch -M main
   git remote add origin https://github.com/duyan9030-dotcom/CloudNest.git
   git push -u origin main
   ```

以上为本项目地址；发布自己的分支项目时替换为自己的仓库。已有 origin 时先用 `git remote -v` 检查地址，不盲目覆盖。若提示 Git 身份未配置，设置自己的 `user.name` 和 GitHub 提交邮箱后重试。请在 GitHub 开启私密漏洞报告；不要通过聊天发送 GitHub 密码或令牌。

项目仓库：<https://github.com/duyan9030-dotcom/CloudNest>。首次上传需保留仓库中已有的许可证和提交历史；不要使用强制推送覆盖远程历史。

## 服务器首次部署

Linux 服务器需 Git、Bash、OpenSSL、Docker Engine 和 Docker Compose 2.24.4+。在预期部署目录运行：

```sh
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
bash scripts/create-admin.sh owner
```

最后一步输入并重复管理员密码（后端要求 8–64 字符且 UTF-8 不超过 72 字节）；创建失败会返回非零退出码，不会重置已有账号。该脚本会暂停 web/server 并在退出时恢复之前运行的服务，数据库和 Redis 保持运行。后续密码重置见部署手册。

命令需成功完成后再登录。默认注册关闭，管理员可生成邀请。数据保存在服务器的 Docker 命名卷中，首次启动自动建空库和表；不需要从开发电脑上传数据库。`.env` 仅保存到服务器及受保护的备份位置。

默认端口只对服务器本机开放。试用可通过 README 中的 SSH 隧道连接；公网访问配置域名和证书后，按部署手册使用 TLS 叠加配置。不要直接向公网开放数据库、Redis 或开发端口。

## 验收与更新

部署后检查健康状态、管理员登录、新账号邀请、上传下载内容一致、分享、重启后数据保留。`/healthz` 只检查 Nginx；同时通过 `docker compose ps` 确认 server/mysql/redis 健康，检查应用日志。当前环境无 Docker，不能将脚本检查等同于 Linux 实机验收。

更新前按部署手册备份数据库和对象；随后 `git pull --ff-only`、`docker compose up -d --build` 并重复验收。`docker compose down` 保留卷；不要使用 `down -v`，它会删除数据。代码回退不等于数据库迁移回退。
