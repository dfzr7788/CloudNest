# 系统更新与宿主机服务

## 首次启用

管理员在“账号与安全 → 系统更新”检查版本、发起更新或立即备份。此能力依赖宿主机服务；仅启动应用容器不会自动获得更新权限。用户和邀请管理位于独立的“系统管理”面板。

Debian 12 的 root 用户在服务器项目目录执行一次：

```bash
cd /root/CloudNest
git fetch origin tag v1.0.0
git merge --ff-only v1.0.0
apt install -y python3
bash scripts/install-updater.sh
```

脚本使用已导入的预构建镜像，或构建前后端，将更新程序安装到 `/opt/cloudnest-updater/`，创建并启用 `cloudnest-updater.service`，在服务器 `.env` 的 COMPOSE_FILE 中追加更新叠加配置。既有 COMPOSE_FILE（包括公网端口或 TLS 配置）保留。按部署手册初始化的 HTTP 部署仍使用原来的 IP 和端口访问。更新正在执行时不能重装服务。

安装后浏览器强制刷新，管理员打开“账号与安全 → 系统更新”，点击“检查更新”。默认 stable 通道发现新正式 Release 后可点击“一键更新”，确认后由服务器执行。本机未推送的提交无法获取。当前以 Git 提交摘要显示运行版本和最新版本。

后续使用自动读取 `.env` 的 `docker compose`。手动指定 `-f` 文件时也须包含 `-f deploy/compose.updater.yml`。当前支持 root 管理、单实例、官方 HTTPS origin 和 main 分支及标准本地数据卷。远程对象在停写窗口导出并校验后纳入备份；旧版维护程序尚不支持远程导出时会中止更新，需要先人工完成桶备份与升级。

## 执行过程与预期结果

| 阶段 | 行为 |
| --- | --- |
| 检查 | 获取正式 Release 标签并验证升级路径；preview 通道获取 main，不构建或停服务 |
| 保存回退镜像 | 根据现有容器的原始镜像引用核对实际镜像身份，再保存回退标签；兼容 containerd 平台镜像摘要 |
| 构建 | 保存旧镜像标签，快进代码，优先下载当前提交对应的 GitHub Release 镜像并验证 SHA256；旧容器继续服务 |
| 备份 | 停止 web/server 写入，导出 MySQL，导出并校验远程对象，归档 app_data（含签名密钥），保存 .env、Compose 和版本信息 |
| 切换 | 重建应用容器并等待健康；网页会短暂断开，恢复后继续显示状态 |
| 完成 | 记录新版提交，提示刷新页面；采用该版的更新程序 |
| 失败 | 展示原因，命令原始输出只写服务器私有日志 |

构建失败时原容器继续运行；备份失败时启动原容器。新版开始执行迁移后若启动失败，需人工从备份恢复数据库与对象，不自动回退数据库。不会执行 `down -v`、删除数据卷或强制覆盖本地修改。

原教程中修改的 HTTP 监听地址可保留，仅当新版本未修改同一个 docker-compose.yml 时允许快进；冲突或其他本地源码修改会拒绝更新。代码已快进但构建失败时，运行版本仍为旧版，可处理错误后重试。

如果旧更新器在 `docker tag` 时报 `No such image: sha256:...`，日志中的摘要可能是 containerd 的平台/配置摘要，不能直接作为打标签的镜像引用。新版根据现有容器的原始镜像引用，核对普通或平台特定身份后创建回退标签；镜像确实缺失、标签已指向不同内容或保存过程中被改动时，仍会停止更新，不停止原容器，不跳过回退检查。不要通过删除容器、清理镜像或删除数据卷处理此错误。

旧更新器无法完成自身更新时，先确认网页任务已失败且 `/var/lib/cloudnest-updater/state/active.json` 不存在，再在项目目录执行 `git fetch origin tag v1.0.0` 和 `git merge --ff-only v1.0.0`，将受信源码中的 worker 安装到宿主机并重启：

```bash
install -m 0755 scripts/updater.py /opt/cloudnest-updater/updater.py
install -m 0644 scripts/split_backup.py /opt/cloudnest-updater/split_backup.py
systemctl restart cloudnest-updater
```

然后回网页重试“一键更新”。这一步只更新宿主机更新程序，不改变 `.env`、数据库、文件卷或当前应用容器。若提示预构建镜像尚未就绪，等待该提交的 GitHub Actions 完成后重试。

同步 worker 时必须同时同步 `split_backup.py`，不能只复制主程序。不要通过重跑完整安装脚本排查备份故障，完整安装流程还可能构建或重建应用容器。
成功升级后的 worker 接纳流程也会检查并同步辅助脚本，即使主程序内容未改变也会补齐缺失模块。

如果已释放磁盘空间后失败阶段变为“备份数据”，说明失败点已经改变；不应继续仅按磁盘不足处理。
新版 worker 会记录 `backupStep`，区分停写、远端对象检查/检查点、MySQL 导出、文件归档、配置保存、校验清单及异地元数据上传。
Python 异常的堆栈写入 root 私有 `worker.log` 和 systemd journal，网页只显示步骤、异常类型与受控提示；日志写盘失败仍尝试恢复原容器。
旧 worker 的泛化错误可能完全没有堆栈，需要先同步宿主机脚本，再重试以获得具体原因。
备份失败恢复原容器不代表备份成功，不能将失败目录作为已验证的恢复基线。

## 备份与排障

```bash
systemctl status cloudnest-updater --no-pager
journalctl -u cloudnest-updater -n 80 --no-pager
tail -n 100 /var/lib/cloudnest-updater/worker.log
docker compose ps
```

备份目录为 `/var/lib/cloudnest-updater/backups/<时间>/`，仅 root 可访问，不自动清理，应预留足够磁盘并转移到其他机器。包含私人文件、.env 密码，不上传 GitHub。成功备份包含 SHA256SUMS，在目录内执行 `sha256sum -c SHA256SUMS` 可核对。

旧镜像为 `cloudnest-rollback-server:<时间>` 和 `cloudnest-rollback-web:<时间>`。已迁移数据库时，单独切旧镜像不能完成回退，应在独立环境恢复配套 MySQL 与 app_data 后再切入口，见部署手册。

关闭网页不影响后台任务。服务重启发现未完成请求时报告中断，不自动重放。应用容器只写固定动作请求，不获得 Docker socket、宿主机仓库或任意命令接口；宿主机程序信任官方仓库源码，stable 默认选择正式发布标签。

## 预构建镜像与定时备份

GitHub Actions 对 main 提交运行测试与构建，发布 `build-完整提交号` 预发布版本，包含 Linux amd64 镜像归档与 SHA256SUMS。下载、校验、导入均在停服务之前。没有预构建版本时，源码部署可以回退到本机编译；已经选择镜像叠加配置的部署会提示等待发布就绪后重试，避免把源码构建错误写进旧版本标签。

首次选择预构建方式可运行 `python3 scripts/use-prebuilt.py`，成功后再 `docker compose up -d --no-build --wait`。`.env` 增加 DEPLOY_REVISION 和 compose.images.yml，之后手动操作也应使用 --no-build。需要至少 3 GiB 镜像导入空间，另外预留完整备份空间。

“立即备份”与每天定时、restic 异地副本、独立实例恢复操作见 [部署手册](deployment.md#备份与恢复)。定时备份与更新共用操作锁，不并发停写；网页只显示受控状态，不提供备份秘密文件的公开下载。

双节点发现远端对象时，备份默认要求 split 模式和成套 A/B 检查点；未配置会中止更新并恢复原服务。请重新安装包含 split_backup.py 的宿主机 worker，并按 [双节点说明](deployment-dual-node.md#独立备份与共同批次) 配置独立仓库，不能只更新应用镜像。

## 稳定与预览通道

1.0.0 起，新更新器默认 `stable`：只读取官方仓库 latest 正式 Release，验证 `v数字.数字.数字` 标签并确认该提交属于 main。网络失败、没有正式版本或标签无效时停止，不自动改用开发提交。当前代码领先或偏离正式版本时拒绝降级。

需要继续测试 main 时，执行 `systemctl edit cloudnest-updater` 添加：

```ini
[Service]
Environment=CLOUDNEST_UPDATE_CHANNEL=preview
```

随后执行 `systemctl daemon-reload` 和 `systemctl restart cloudnest-updater`。改为 `stable` 即恢复正式通道。此配置属于宿主机 systemd 服务，不能只写入应用 `.env`。覆盖配置在重新安装服务后仍保留。

旧更新器仍跟随 main；首次升级到 1.0.0 应在停写备份后按上文固定标签同步源码与 worker。仅发布一个 Git 标签不会改变已安装的旧更新器。
