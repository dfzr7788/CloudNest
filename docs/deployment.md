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
# AI 部署与恢复

`.env` 中 `AI_ENABLED=false` 为默认值。启用时设为 `true` 并执行 `docker compose up -d --build --wait server web`。用户 Key 保存在数据库中，不能放进共享环境文件。关闭时恢复 `false` 并重启 server：新请求被拒绝，未完成任务取消，已有结果仍按文件权限可读，用户仍可撤销或删除凭据。云盘文件操作不依赖模型服务。

AI 首版运行在**单个 server 实例**，全局最多 4 个执行任务、50 个等待任务，每用户 1 个执行、5 个等待；每日每用户最多 100 个上游调用。仅出站 HTTPS 公网 443，DNS 全部结果须通过地址策略，每次请求独立连接池并固定已验证的 IP，不使用系统代理、不跟随重定向、不自动重试。内网目标与管理员端点别名尚未开放。

AI 数据随现有数据库备份保存。必须同时备份并恢复 `DATA_DIR/jwt.key`：AI 从此根密钥按独立用途派生 AES-GCM 密钥，密文以 `ai:v1:` 标记版本；现有其他设置的加密行为不变。根密钥不匹配时调用会明确提示解密失败，原密文不会被覆盖。先恢复正确根密钥，或让用户重新填写 API Key，不要生成新根密钥覆盖旧备份。

当前版本只读写 v1 密文，尚未提供在线多 keyId 轮换工具；更换用户 API Key 可直接在原连接编辑。将来更换根密钥必须先实现兼容读取旧版本、逐批重加密及验证流程，不能直接替换 `jwt.key`。撤销本机凭据不会即时擦除旧备份；请按既有备份保留期限清理，并在供应商处撤销原 Key。

摘要/标签任务结果及缓存 7 天，问答会话及其任务从最近活动起 30 天，用量 90 天，幂等窗口 24 小时，后台定时清理；每用户派生结果上限 100 MiB。服务重启将原 RUNNING 任务标记 `AI_INTERRUPTED`，不重复收费调用；排队任务重新鉴权后继续。AI 基础迁移为 H2 V11/MySQL V12，问答追加 H2 V12/MySQL V13，新增会话、消息、引用表和任务 conversation_id，不修改旧迁移。问答无需新增容器或向量服务，随数据库一起备份/恢复；恢复后继续按源文件权限检查，删除源文件会同步清除其派生正文。回退到问答之前的程序后不要继续操作文件永久删除，应恢复配套版本或完整备份，以免旧程序遗漏新表清理。回退时先关闭 AI，保留新增表与备份。


向量准备阶段追加 H2 V13/MySQL V14，现有模型自动标记为 CHAT，新模型可选择 EMBEDDING；增加预期维度、批次限制、是否请求降维、独立测试状态和调用用途。无需新增环境变量、容器或数据卷。聊天与向量使用相同加密根密钥、目的地同意、网络策略和每日限额；向量测试响应上限 4 MiB，公网 HTTPS 443，禁止代理、重定向和自动重试。测试向量不落库；用户确认的知识库向量随后保存在同一数据库，见下节。

迁移不将已有模型改成向量模型。回退到不认识 EMBEDDING 的程序前，先删除向量模型配置或恢复配套完整数据库备份，避免旧程序将其当作聊天模型。不能删除 Flyway 记录或修改旧迁移来回退。向量存储基准使用临时合成数据，结束后自动清除，与生产数据库无关。


### 知识库与增强功能的部署

追加 H2 V14 / MySQL V15：知识库、索引代次/文本块/批次、知识库会话、任务来源映射和整理应用去重记录。H2 V15 / MySQL V16 为知识库会话增加聊天模型及连接版本绑定；旧版无绑定的会话只读，需要新建会话继续。存储为同一数据库内的 float32 二进制向量，按用户/知识库/代次过滤后精确余弦排序；每库最多 1000 块，不引入外部向量服务、额外端口或数据卷。无需新的环境变量，仍由 AI_ENABLED 开关控制。

数据库完整备份自动包含块文本、向量、批次与会话。恢复必须使用同批次的源文件、数据库与 AI 根密钥；不要单独覆盖向量表。恢复或重启把运行任务置为失败、发送中的索引批次置为 UNKNOWN，禁止自动重发可能计费批次。用户预览并确认继续后，已落库的块可复用，未知批次需重新发送并提示可能再次收费。

关闭 AI 后基础云盘仍可用，索引不会自动更新；删除凭据、清除知识库与会话仍可执行。删除源文件立即排除检索，永久删除在同一事务中清除相关块和任务正文；后台补偿清理失效块。完整索引不会仅因闲置自动删除，未完成代次 7 天清理。派生额度为任务、会话、块文本及向量（未生成向量按维度预留）的合计 100 MiB。

回退到本次版本之前的程序时先关闭 AI，并恢复匹配的完整备份或使用匹配的新程序处理永久删除；旧程序不了解知识库清理，不能仅保留新表而继续删除文件。当前自动化覆盖 H2/MySQL 迁移与业务事务；目标 Docker 新装/升级、真实供应商检索质量及完整生产恢复演练仍需实际环境验收，不能将本机合成基准视为服务器容量承诺。
