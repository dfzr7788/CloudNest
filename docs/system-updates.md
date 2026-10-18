# 管理面板与一键更新

## 首次启用

系统管理页已将服务器状态、邀请和用户表分区显示，邀请链接提供复制按钮，兼容 HTTP 下的复制操作。管理员另有“系统更新”标签。

Debian 12 的 root 用户在服务器项目目录执行一次：

```bash
cd /root/CloudNest
git pull --ff-only
apt install -y python3
bash scripts/install-updater.sh
```

脚本使用已导入的预构建镜像，或构建前后端，将更新程序安装到 `/opt/cloudnest-updater/`，创建并启用 `cloudnest-updater.service`，在服务器 `.env` 的 COMPOSE_FILE 中追加更新叠加配置。既有 COMPOSE_FILE（包括公网端口或 TLS 配置）保留。按部署手册初始化的 HTTP 部署仍使用原来的 IP 和端口访问。更新正在执行时不能重装服务。

安装后浏览器强制刷新，管理员打开“账号与安全 → 系统更新”，点击“检查更新”。GitHub main 出现新提交后可点击“一键更新”，确认后由服务器执行。本机未推送的提交无法获取。当前以 Git 提交摘要显示运行版本和最新版本。

后续使用自动读取 `.env` 的 `docker compose`。手动指定 `-f` 文件时也须包含 `-f deploy/compose.updater.yml`。当前支持 root 管理、单实例、官方 HTTPS origin 和 main 分支及标准本地数据卷。远程对象在停写窗口导出并校验后纳入备份；旧版维护程序尚不支持远程导出时会中止更新，需要先人工完成桶备份与升级。

## 执行过程与预期结果

| 阶段 | 行为 |
| --- | --- |
| 检查 | 获取 GitHub main，不构建或停服务 |
| 保存回退镜像 | 根据现有容器的原始镜像引用核对实际镜像身份，再保存回退标签；兼容 containerd 平台镜像摘要 |
| 构建 | 保存旧镜像标签，快进代码，优先下载当前提交对应的 GitHub Release 镜像并验证 SHA256；旧容器继续服务 |
| 备份 | 停止 web/server 写入，导出 MySQL，导出并校验远程对象，归档 app_data（含签名密钥），保存 .env、Compose 和版本信息 |
| 切换 | 重建应用容器并等待健康；网页会短暂断开，恢复后继续显示状态 |
| 完成 | 记录新版提交，提示刷新页面；采用该版的更新程序 |
| 失败 | 展示原因，命令原始输出只写服务器私有日志 |

构建失败时原容器继续运行；备份失败时启动原容器。新版开始执行迁移后若启动失败，需人工从备份恢复数据库与对象，不自动回退数据库。不会执行 `down -v`、删除数据卷或强制覆盖本地修改。

原教程中修改的 HTTP 监听地址可保留，仅当新版本未修改同一个 docker-compose.yml 时允许快进；冲突或其他本地源码修改会拒绝更新。代码已快进但构建失败时，运行版本仍为旧版，可处理错误后重试。

如果旧更新器在 `docker tag` 时报 `No such image: sha256:...`，日志中的摘要可能是 containerd 的平台/配置摘要，不能直接作为打标签的镜像引用。新版根据现有容器的原始镜像引用，核对普通或平台特定身份后创建回退标签；镜像确实缺失、标签已指向不同内容或保存过程中被改动时，仍会停止更新，不停止原容器，不跳过回退检查。不要通过删除容器、清理镜像或删除数据卷处理此错误。

旧更新器无法完成自身更新时，先确认网页任务已失败且 `/var/lib/cloudnest-updater/state/active.json` 不存在，再在项目目录执行 `git pull --ff-only`，将受信源码中的 worker 安装到宿主机并重启：

```bash
install -m 0755 scripts/updater.py /opt/cloudnest-updater/updater.py
systemctl restart cloudnest-updater
```

然后回网页重试“一键更新”。这一步只更新宿主机更新程序，不改变 `.env`、数据库、文件卷或当前应用容器。若提示预构建镜像尚未就绪，等待该提交的 GitHub Actions 完成后重试。

## 备份与排障

```bash
systemctl status cloudnest-updater --no-pager
journalctl -u cloudnest-updater -n 80 --no-pager
tail -n 100 /var/lib/cloudnest-updater/worker.log
docker compose ps
```

备份目录为 `/var/lib/cloudnest-updater/backups/<时间>/`，仅 root 可访问，不自动清理，应预留足够磁盘并转移到其他机器。包含私人文件、.env 密码，不上传 GitHub。成功备份包含 SHA256SUMS，在目录内执行 `sha256sum -c SHA256SUMS` 可核对。

旧镜像为 `cloudnest-rollback-server:<时间>` 和 `cloudnest-rollback-web:<时间>`。已迁移数据库时，单独切旧镜像不能完成回退，应在独立环境恢复配套 MySQL 与 app_data 后再切入口，见部署手册。

关闭网页不影响后台任务。服务重启发现未完成请求时报告中断，不自动重放。应用容器只写固定动作请求，不获得 Docker socket、宿主机仓库或任意命令接口；宿主机程序信任官方 main 源码。

## 预构建镜像与定时备份

GitHub Actions 对 main 提交运行测试与构建，发布 `build-完整提交号` 预发布版本，包含 Linux amd64 镜像归档与 SHA256SUMS。下载、校验、导入均在停服务之前。没有预构建版本时，源码部署可以回退到本机编译；已经选择镜像叠加配置的部署会提示等待发布就绪后重试，避免把源码构建错误写进旧版本标签。

首次选择预构建方式可运行 `python3 scripts/use-prebuilt.py`，成功后再 `docker compose up -d --no-build --wait`。`.env` 增加 DEPLOY_REVISION 和 compose.images.yml，之后手动操作也应使用 --no-build。需要至少 3 GiB 镜像导入空间，另外预留完整备份空间。

“立即备份”与每天定时、restic 异地副本、独立实例恢复操作见 [部署手册](deployment.md#备份与恢复)。定时备份与更新共用操作锁，不并发停写；网页只显示受控状态，不提供备份秘密文件的公开下载。
