# 部署、备份与运维

## 1. 开发运行

见 README 的两个终端启动步骤。默认 `.data` 路径相对后端工作目录；开发默认文件型 H2 使用 WRITE_DELAY=0，使成功提交同步写入。自定义 DATABASE_URL 时保留此设置，避免进程被强制结束后丢失最近提交。

端口：后端 8080，Vite 5173。默认容量 5 GiB，文件上限 2 GiB，分片 8 MiB。可通过 USER_QUOTA、MAX_FILE_SIZE、PART_SIZE、DATA_DIR、PORT 调整。生产仅运行一个后端实例；多实例需要重新设计会话锁、目录并发和共享分片存储。

Windows 不要在 Java 正在运行的 target/*.jar 上重新打包。停止进程后替换发布包，或复制到独立的运行目录。

## 2. Linux Docker Compose

首次部署可使用 `bash scripts/init-env.sh` 自动生成独立随机密码（保留已有 `.env`），再按下方启动。服务健康后执行 `bash scripts/create-admin.sh owner` 交互创建管理员。完整克隆、启动、访问步骤见 [公开发布指南](publishing.md)；不需要上传开发数据库，应用会自动执行建表迁移。

前置条件：Linux 主机、Docker Engine、Compose 2.24.4+（TLS 叠加配置使用 !override）、足够磁盘和可用域名/证书。当前工作环境没有 Docker，配置尚未在 Linux 实际部署，不能把本手册当作已部署证明。

```sh
cp .env.example .env
# 将三组密码替换为不同的随机值；不提交 .env
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 server
curl -f http://127.0.0.1:8088/healthz
```

默认仅 web 监听宿主机回环端口，MySQL、Redis、后端均不发布宿主机端口。服务端健康检查路径为 `/actuator/health`，通过容器内网络访问。MySQL/Redis 使用健康依赖；应用启动执行 Flyway，版本与校验不一致会停止启动。

持久数据：mysql_data 保存数据库，app_data 保存对象、分片、ZIP 临时产物与 jwt.key。Redis 仅作限流，不保存关键业务状态。容器重建不能删除卷；`docker compose down -v` 会销毁业务数据，不用于日常升级。

推荐基础资源：2 核、2 GiB 内存起步，按实际并发测量调整。预留空间需覆盖数据库、逻辑文件、上传分片、合并副本及 ZIP；1 GiB 文件上传过程中可能同时存在分片、合并临时文件和最终对象。已对分片剩余可用空间做检查，但这不能代替磁盘监控。磁盘用量超过 80% 时安排清理或扩容。

## 3. HTTPS

将域名对应的完整证书链和私钥放到 `deploy/certs/fullchain.pem`、`privkey.pem`，该目录已忽略。DNS 指向目标服务器后：

```sh
docker compose -f docker-compose.yml -f deploy/compose.tls.yml config --quiet
docker compose -f docker-compose.yml -f deploy/compose.tls.yml up -d --build
```

该配置绑定 80/443，HTTP 跳转 HTTPS，启用 Secure Cookie 与 HSTS。生产前验证证书链、域名、分享链接、PDF Range、分片上传、代理超时与 Cookie；确认后再开放访问。不要在普通 HTTP 环境设置 Secure Cookie，否则浏览器不会发送登录凭证。

前端 CSP 限制外部脚本与对象，后端预览禁止脚本执行。Nginx 将 X-Forwarded-For 覆盖为真实连接地址，后端 Compose 启用 Tomcat native 可信内部代理解析，且不暴露后端端口。直接运行后端时默认不信任转发头。生产前仍应在实际容器网络中验证代理地址和按用户来源的限流，不能盲目信任任意 X-Forwarded-For。

## 4. 本地备份恢复

H2 文件备份必须停止写入进程。整个数据目录与 jwt.key 一起备份，不只复制数据库文件。

```powershell
# 停止后端后
./scripts/backup-local.ps1 -DataDir cloudnest-server/.data -Destination backups
# 恢复到全新目录，不覆盖原目录
./scripts/restore-local.ps1 -Archive backups/cloudnest-时间.zip -Target G:/CloudNest/restored-data
```

恢复后将 DATA_DIR 和 DATABASE_URL 同时指向新目录。先执行核对工具，再启动正常服务，验证账号、目录、文件摘要、配额与回收站。归档包含私人文件和签名密钥，应保存在受保护的位置。

## 5. MySQL + 本地对象一致性备份

本轮采用维护窗口，停止应用写入，再同时备份 MySQL 和 app_data。下面命令在项目根目录运行，备份目录与数据库实例属于当前 Compose 项目：

```sh
mkdir -p backups
docker compose stop web server
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -ucloudnest --single-transaction --no-tablespaces --routines --triggers cloudnest' > backups/cloudnest.sql
docker compose run --rm --no-deps --user 0 --entrypoint tar -v "$PWD/backups:/backup" server -czf /backup/app-data.tar.gz -C /app/data .
sha256sum backups/cloudnest.sql backups/app-data.tar.gz > backups/SHA256SUMS
docker compose start server web
```

备份元信息另记代码版本、数据库迁移版本、时间、对象后端及摘要清单。MinIO 后端需要同一维护窗口的对象桶备份；本地 app_data 不能替代远程桶备份。

恢复在独立的 Compose 项目/主机进行，先验证备份摘要；创建空库和空卷后导入 SQL、解压对象目录并恢复 UID 10001 所有权，再启动 server/web。不要向正在运行或含有新数据的实例直接覆盖。执行完整核对、登录和下载后才切换入口。Linux 的这套命令尚待真实环境演练；本次本地 H2 备份恢复有独立实际报告。

## 6. 核对与迁移工具

停止普通后端后，用同一 DATABASE_URL/DATA_DIR 和不同临时端口运行维护命令，避免两个实例并发操作同一文件数据库：

```sh
java -jar cloudnest-server/target/cloudnest-server-1.0.0.jar --server.port=18089 --cloudnest.maintenance=true --cloudnest.action=audit
# 校正逻辑配额（先检查 audit 输出）
java -jar cloudnest-server/target/cloudnest-server-1.0.0.jar --server.port=18089 --cloudnest.maintenance=true --cloudnest.action=recount
```

输出以 `CLOUDNEST_MAINTENANCE_RESULT=` 开头的 JSON，包含预期/实际容量和每个对象的校验结果。无问题退出 0，有问题退出 2。recount 更新配额；audit 只读检查。

如已有独立维护的私有 MinIO/S3 兼容服务，设置 MINIO_ENDPOINT、MINIO_ACCESS_KEY、MINIO_SECRET_KEY、MINIO_BUCKET 后：

```sh
java -jar cloudnest-server/target/cloudnest-server-1.0.0.jar --server.port=18089 --cloudnest.maintenance=true --cloudnest.action=migrate-minio
```

迁移逐对象读取本地文件、验证摘要、写入目标、再次读取验证，最后更新 blob.backend。失败时保留原引用与源文件；已成功部分可重复运行跳过。迁移后设 STORAGE_BACKEND=minio，使新上传使用目标存储；旧本地副本仅在人工确认及备份后清理。

2026-10-16 官方社区 MinIO 二进制地址返回 410（归档且停止分发），因此默认 Compose 不依赖不可获取的社区镜像，也不下载不明来源程序。适配代码已编译，但真实 MinIO 和迁移尚未验收。

## 7. 升级、回退与排障

管理员网页“一键更新”须先安装宿主机配套服务，步骤与备份/失败处理见 [系统更新](system-updates.md)。原有手动升级方式仍然可用。

新版增加历史版本、收藏、会话和邀请等迁移（H2 到 V6，MySQL 到 V7）。升级前停写并备份数据库、对象与签名密钥；请勿直接用旧程序连接已迁移的新库。升级会将旧的临时 ZIP 任务标记失败，用户需重新打包。已有文件、账号和配额保留。

### 初始管理员、邀请和密码重置

生产 Compose 默认 `REGISTRATION_OPEN=false`，通过维护命令创建初始管理员，之后管理员可在“账号与安全 → 系统管理”生成一次有效、48 小时过期的邀请链接。开发配置默认开放注册；普通注册绝不会自动获得管理员权限。

维护命令支持 `create-admin`、`create-user`、`reset-password`；用户名用 `--cloudnest.operator-username`，密码仅通过当前进程的 `CN_OPERATOR_PASSWORD` 环境变量输入。重置不会启用一个已禁用的账号，会撤销该账号的全部会话。维护期间停止正常后端，避免与 H2 文件或后台任务竞争。

本机 PowerShell 示例（在项目根目录，先停止后端，确认使用的是现有数据目录）：

```powershell
$operatorSecret = Read-Host '输入新密码' -AsSecureString
$env:CN_OPERATOR_PASSWORD = [Net.NetworkCredential]::new('', $operatorSecret).Password
try {
  java -jar cloudnest-server/target/cloudnest-server-1.0.0.jar --server.port=0 --cloudnest.maintenance=true --cloudnest.action=create-admin --cloudnest.operator-username=owner --cloudnest.data-dir=G:/CloudNest/cloudnest-server/.data '--spring.datasource.url=jdbc:h2:file:G:/CloudNest/cloudnest-server/.data/cloudnest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0'
} finally { Remove-Item Env:CN_OPERATOR_PASSWORD }
```

已存在账号需要重置时，把 action 改成 `reset-password` 并填写该账号名。命令成功输出 `CLOUDNEST_MAINTENANCE_RESULT` 且退出码为 0；请检查输出，失败不得当成已创建。

Linux Compose 示例（数据库与 Redis 保持运行；下列是待目标服务器实测的部署步骤）：

```sh
docker compose stop web server
read -rs -p 'New password: ' CN_OPERATOR_PASSWORD
export CN_OPERATOR_PASSWORD
docker compose run --rm --no-deps -e CN_OPERATOR_PASSWORD server --server.port=0 --cloudnest.maintenance=true --cloudnest.action=create-admin --cloudnest.operator-username=owner
unset CN_OPERATOR_PASSWORD
docker compose up -d server web
```

密码不要写进 `.env`、Git、执行日志或命令参数；系统管理员可通过本机维护权限创建账号，普通用户只能持邀请码注册。当前用户选择后续自行填写服务器连接、域名、证书等配置，本轮不修改任何远程服务器。

1. 记录版本并备份数据库与对象，检查磁盘容量。
2. 构建并测试新版本；暂停写入，替换代码/镜像并启动。
3. 检查 Flyway、健康、注册登录、上传/下载/预览和回收站。
4. 回退须考虑迁移兼容性；无法直接回退时恢复同一快照的数据库和对象，不能只回退一个部分。

| 现象 | 检查与处理 |
| --- | --- |
| 登录后写请求 403 | 重新取得 /api/auth/csrf，检查同源代理及 Secure Cookie；不要关闭 CSRF |
| 上传 409 | 检查同名、配额、会话过期和目标目录是否仍有效 |
| 上传 422 | 内容摘要或分片被修改；取消任务并重选原文件 |
| 上传 507 | 检查临时盘可用空间；清理已过期任务或扩容 |
| 反向代理 413/504 | 核对 Nginx 101m、普通上传 100 MiB、分片大小和 600s 合并超时 |
| 连接 Redis 失败 | 启用 Redis 时限流失败返回 503；恢复 Redis，不静默放开限制 |
| 已用空间与预期不符 | 回收站仍计费；查看预留会话；停止服务运行 audit/recount |
| Flyway 校验失败 | 不修改已执行迁移；新增迁移或从一致性备份恢复 |
| 旧登录凭证失效 | 检查 jwt.key 是否随数据恢复，数据库会话是否仍未过期 |
