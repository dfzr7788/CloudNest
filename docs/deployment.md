# Linux 服务器部署

本流程将云盘部署为 `http://服务器公网IP:8088`，不需要域名、证书或 Cloudflare。只发布 Web 端口，MySQL、Redis 和后端不对公网开放。以下命令在 Debian 12/13 的 root 终端执行；普通用户先运行 `sudo -i`。

## 安装 Docker

已有 Docker 和 Compose 2.24.4 以上版本可跳过安装，先检查：

```bash
docker --version
docker compose version
```

未安装时，按 [Docker 官方 Debian 安装说明](https://docs.docker.com/engine/install/debian/) 使用官方软件源：

```bash
apt update
apt install -y ca-certificates curl git openssl python3
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: $(. /etc/os-release && echo "$VERSION_CODENAME")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker run --rm hello-world
```

若已有其他来源的 Docker，先按官方说明处理冲突的软件包，不要直接删除现有业务数据。建议至少 2 核、2 GiB 内存，并为构建、文件、上传临时数据和备份留出磁盘空间。

## 下载并启动

```bash
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
```

初始化脚本生成三组独立随机密码，并保留已有 `.env`。新配置的 `COMPOSE_FILE` 自动加载 `deploy/compose.public.yml`，让 Web 监听 `0.0.0.0:8088`。Compose 必须支持 `!override`（2.24.4+）。基础配置本身仍只监听回环地址，便于其他入口方案使用。

Linux amd64 服务器可先执行 `python3 scripts/use-prebuilt.py` 下载当前提交对应的校验镜像，再使用 `docker compose up -d --no-build --wait --wait-timeout 180` 启动，避免在服务器编译。若发布尚未完成则等待 GitHub Actions，或保持源码构建流程。其他架构目前使用源码构建。

首次构建会下载 Java、Node、MySQL 镜像及 Maven/npm 依赖，可能需要几分钟；后续构建会复用缓存。成功后 MySQL、Redis、server 应显示 healthy，web 显示运行中。

## 创建管理员

在同一个项目目录执行：

```bash
bash scripts/create-admin.sh owner
```

按提示输入两次自定义密码，输入时不显示字符。密码为 8～64 个字符，UTF-8 编码不超过 72 字节。脚本临时停止应用写入，创建账号后恢复原先运行的服务。确认输出 `CLOUDNEST_MAINTENANCE_RESULT` 中 `problems` 为 `0`、`action` 为 `create-admin`。

登录账号是 `owner`，密码是刚才输入的内容，没有内置的 admin 默认密码。不要把数据库密码当作登录密码。账号已存在时不要重复创建；忘记密码见下方重置步骤。

## 用 IP 和端口访问

在云服务器控制台的防火墙/安全组放行入站 TCP **8088**。如果主机已经启用 UFW，也添加对应规则：

```bash
ufw status
# 仅在 UFW 已启用时添加；无需为本流程额外启用它
ufw allow 8088/tcp
```

保留 SSH 访问规则，不要关闭整个防火墙。浏览器打开：

```text
http://你的服务器公网IP:8088
```

这里使用 `http`，不是 `https`。普通 HTTP 的 `SECURE_COOKIES` 必须保持 `false`，否则浏览器不会发送登录凭证。HTTP 不加密账号与文件传输；需要加密时可另行配置 HTTPS。

本机检查：

```bash
curl --noproxy '*' -I http://127.0.0.1:8088/
curl --noproxy '*' -f http://127.0.0.1:8088/api/auth/csrf
```

首页 200 表示 Web 可用，CSRF 接口成功表示 API 路由可用；后端状态还应以 `docker compose ps` 的健康状态为准。IP 可以打开而域名不行时，不必改动云盘或数据库配置。

## 邀请用户

默认 `REGISTRATION_OPEN=false`，公众不能自行注册。管理员登录后打开“账号与安全 → 系统管理”，点击“邀请新用户”，复制完整邀请链接给对方。链接只在创建时显示，一次有效，48 小时过期。通过 IP 访问创建的链接会包含当前 IP 和端口。

## 修改端口

修改 `.env` 中 `WEB_PORT=8088`，例如改成 `WEB_PORT=8090`，然后执行：

```bash
docker compose config --quiet
docker compose up -d --no-deps web
```

同时放行新端口，浏览器改用新地址。不要开放 MySQL 的 3306 或 Redis 的 6379。

## 已有部署启用 IP 访问

已经可以用 IP 和端口访问的部署无需修改。已有 HTTPS 或 Tunnel 的部署也无需套用此步骤。

旧版 `.env` 不会被初始化脚本覆盖。仅对于仍绑定回环地址的普通 HTTP 部署，在 `.env` 中设置：

```dotenv
COMPOSE_FILE=docker-compose.yml:deploy/compose.public.yml
SECURE_COOKIES=false
```

已经安装更新服务时保留其叠加文件：

```dotenv
COMPOSE_FILE=docker-compose.yml:deploy/compose.public.yml:deploy/compose.updater.yml
```

然后运行 `docker compose config --quiet` 和 `docker compose up -d --no-deps web`。不要直接覆盖包含其他部署配置的 COMPOSE_FILE；先核对其用途。显式使用 `-f` 会覆盖环境变量选择，日常直接使用 `docker compose`。

## 更新项目

先完成下一节的一致性备份，再执行：

```bash
git pull --ff-only
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
```

本地修改导致 Git 拒绝更新时，先检查差异，不要强制覆盖。配置放在 `.env`，私有资料不要放进源码目录。更新后检查登录、上传、下载和分享。

网页“一键更新”需要首次安装宿主机更新服务，见 [系统更新](system-updates.md)。它会在切换前备份数据库和对象。更新不会自动覆盖已有 `.env`。

## 备份与恢复

`mysql_data` 保存数据库，`app_data` 保存文件对象、分片和签名密钥。数据库、对象和密钥必须成套备份。**不要执行 `docker compose down -v`，它会删除业务数据卷。** Redis 只用于限流。

本流程使用一致性备份脚本：暂停 web/server，导出 MySQL，并将远程对象校验导出到 app_data，然后归档数据、签名密钥、配置和代码版本，生成 SHA256 清单，最后恢复服务。

```bash
python3 scripts/backup.py create --repo "$PWD"
python3 scripts/backup.py list
# 用实际时间目录替换下方路径
python3 scripts/backup.py verify --snapshot /var/lib/cloudnest-updater/backups/时间目录
```

备份期间会短暂停止访问。命令失败不能视为成功，检查服务状态与日志。管理员安装更新服务后，可在“系统更新”点击“立即备份”并查看最近备份状态；更新前备份同样保存在受保护目录，但不一定列入定时备份列表。备份不会自动删除。

定时备份（服务器本地时间每天 03:00，最多随机延后十分钟）：

```bash
bash scripts/install-backups.sh
systemctl status cloudnest-backup.timer --no-pager
# 可手动触发，读取相同的异地备份凭据
systemctl start cloudnest-backup.service
journalctl -u cloudnest-backup -n 80 --no-pager
```

异地备份使用 [restic 加密仓库](https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html)。先安装 restic 并在服务器初始化自己的 SFTP/S3 等仓库，再将 `RESTIC_REPOSITORY`、`RESTIC_PASSWORD_FILE` 和所需凭据写入仅 root 可读的 `/etc/cloudnest/backup.env`，密码文件也设为 0600。不要将这些值提交到 Git。定时服务自动运行 `restic backup`；未配置显示 NOT_CONFIGURED，异地上传失败显示 PARTIAL/FAILED，本地快照仍保留。修改凭据后重启 cloudnest-updater，使网页手动备份读取新配置。远程保留策略由管理员配置，脚本不会擅自执行 forget/prune。

恢复先检查计划，默认不执行写入：

```bash
python3 scripts/backup.py restore \
  --snapshot /var/lib/cloudnest-updater/backups/时间目录 \
  --target /root/CloudNest-restore-check \
  --project cloudnest-restore-check --port 18088
# 确认计划后，在同一命令末尾加 --execute 执行
```

执行只允许尚不存在的目标目录和独立 `cloudnest-restore-*` Compose 项目；先验证清单及归档路径，再使用快照记录的代码版本创建空卷、导入数据库与对象。远程对象恢复到独立实例的本地存储，经内容校验和离线审计后启动；入口只监听 `127.0.0.1:18088`。可用 SSH 端口转发检查登录、下载、分享、历史版本与配额，再由维护人员切换正式入口。恢复不会覆盖原生产卷。旧版远程存储快照若未包含 portable 导出不能按此恢复，需先备份并恢复原桶。新版数据库迁移后不能仅切旧镜像回退。

备份包含私人文件及密码，应保护并保留至少一份异机副本。磁盘需要容纳本地对象、远程导出和完整归档。未完成真实恢复演练前，不要删除原始数据与旧备份。

开发 H2 数据使用停止后端后的完整目录备份，可使用 `scripts/backup-local.ps1` 与 `scripts/restore-local.ps1`，不混用 MySQL 的 SQL 恢复流程。

## 重置管理员密码

在项目根目录执行，确认当前 server/web 正常运行。此操作撤销该账号全部登录会话，不会自动启用已禁用账号：

```bash
(
  read -r -s -p '输入新密码: ' CN_OPERATOR_PASSWORD
  printf '\n'
  read -r -s -p '再次输入: ' confirmation
  printf '\n'
  if [ "$CN_OPERATOR_PASSWORD" != "$confirmation" ]; then
    echo '两次输入不同，请重新执行。'
    exit 1
  fi
  export CN_OPERATOR_PASSWORD
  trap 'unset CN_OPERATOR_PASSWORD; docker compose start server web' EXIT
  docker compose stop web server &&
  docker compose run --rm --no-deps -e CN_OPERATOR_PASSWORD server \
    --server.port=0 --cloudnest.maintenance=true \
    --cloudnest.action=reset-password --cloudnest.operator-username=owner
)
```

等待维护结果 `problems: 0` 后再登录。维护命令的正常退出不是服务故障；检查随后 server/web 已恢复运行。密码不要放入命令参数、`.env` 或截图。

## 常见问题

| 现象 | 排查 |
| --- | --- |
| 外部打不开，本机 curl 成功 | 安全组、主机防火墙、WEB_PORT、实际端口映射；客户端代理可先关闭测试 |
| 首页能打开，API 失败 | `docker compose ps` 与 `docker compose logs --tail=100 server web` |
| 登录失败 | 核对登录账号，检查维护结果；需要时重置密码；HTTP 使用 SECURE_COOKIES=false |
| 构建很慢 | 首次依赖下载较多；查看正在执行的阶段及磁盘/内存，保留构建缓存 |
| 等待健康超时 | 查看 MySQL/server 日志和磁盘内存；修复后重新执行 up，不删除卷 |
| 上传或 ZIP 失败 | 查看界面具体错误、剩余配额与磁盘；文件和目录打包限制见使用说明 |
| 数据库迁移校验失败 | 不修改已执行的迁移；从配套备份恢复或新增迁移处理 |

排障日志仅保存在本机，分享前去除密码、Cookie、邀请链接和私人文件信息。
