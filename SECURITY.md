# 安全反馈

请勿在公开 Issue、Pull Request 或截图中披露可利用的漏洞细节、账号凭据、API Key、Cookie、分享令牌或私人文件。

若仓库已启用 GitHub Private vulnerability reporting，请通过仓库 **Security → Advisories → Report a vulnerability** 私下报告。若没有此入口，可创建仅请求私下联系方式的 Issue，不附漏洞细节。

报告请说明受影响版本或提交号、部署方式、复现条件、影响范围及最小复现步骤，使用合成数据替代真实用户资料。

## 部署中的安全边界

- 当前支持单个后端实例；公网访问使用 HTTPS，按部署方式正确设置 `SECURE_COOKIES`。
- 不公开 MySQL、Redis、后端管理端口、备份目录或宿主机更新队列；只发布所需 Web 入口。
- 数据库、文件对象和 `jwt.key` 必须成套备份。密钥用于签名及服务配置/AI 凭据保护，不可任意替换；详见 [部署手册](docs/deployment.md#备份与恢复)。
- AI 为用户自带 API，调用时正文会发送到用户确认的目的地。服务器能够解密调用凭据，运维者具备技术访问能力，不属于端到端加密。
- 备份可能含已删除资料与旧凭据。保护异机副本、验证恢复流程，并在供应商处撤销泄露的 Key。

账号、TOTP、WebDAV 和邮件配置见 [进阶配置](docs/advanced-features.md)；及时审查依赖更新并按文档验证升级。
