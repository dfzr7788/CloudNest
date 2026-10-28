# CloudNest 文档中心

适用于 `1.0.0`。首次部署、日常使用和开发维护按下面的路径阅读。稳定能力和实验能力以[功能说明](features.md#功能边界)为准；本地测试通过不等于目标服务器已经完成验收。

## 按任务开始

| 我想做什么 | 阅读顺序 |
| --- | --- |
| 判断是否适合自己 | [项目介绍](../README.md) → [功能与限制](features.md) |
| 部署私人云盘 | [部署手册](deployment.md) → [配置参考](configuration.md) → [上线检查](operations.md) |
| 第一次上传、分享和恢复文件 | [使用说明](usage.md) |
| 管理用户、团队和空间 | [使用说明](usage.md#账号与安全) → [角色与配额](features.md#团队空间) |
| 配置邮件、Office 或 WebDAV | [进阶配置](advanced-features.md) |
| 添加存储或双节点 | [存储节点](storage.md) → [双节点部署与恢复](deployment-dual-node.md) |
| 使用 AI | [AI 阅读助手与知识库](ai.md) → [只读诊断](agent-tools.md) → [Agent 与审批](agent-runtime.md) |
| 更新、备份或处理故障 | [运维检查](operations.md) → [系统更新](system-updates.md) / [排障](troubleshooting.md) |
| 使用 Windows 客户端 | [客户端安装与使用](../clients/windows-sync/README.md) → [同步协议与验证边界](sync.md) |
| 接入团队贡献节点 | [轻量存储节点](../clients/storage-node/README.md) |
| 开发或调用接口 | [开发指南](development.md) → [API](api.md) / [团队与数据保护 API](workspace-api.md) / [OpenAPI](openapi.json) |

## 文档分工

- **使用说明**回答用户在哪里操作、结果是什么、失败后如何继续。
- **配置参考**回答配置写在哪里、何时生效、哪些值不能仅写进 `.env`。
- **部署与运维**回答服务如何启动、如何确认成功、如何备份和恢复。
- **功能说明与 API**记录权限、配额、限制及接口契约，不把设计设想写成现有功能。

配置的实现依据为 [`application.yml`](../cloudnest-server/src/main/resources/application.yml)、[`docker-compose.yml`](../docker-compose.yml) 和 [`.env.example`](../.env.example)。使用其他提交时，也应阅读该提交对应的文档。

## 版本、验证与设计

- [1.0.0 发布说明](releases/1.0.0.md)：稳定范围、版本编号更正和升级边界。
- [2026-10-28 本地验收](releases/1.0.0-acceptance-20261028.md)：实际执行结果及未验证事项。
- [存储实现契约](design/v1-contract.md)、[控制平面边界](design/control-plane.md)、[界面规范](design/ui-system.md)：供开发维护参考。
- [贡献约定](../CONTRIBUTING.md)与[安全反馈](../SECURITY.md)。

英文入口见 [English README](../README.en.md)，已有英文手册包括 [Usage](en/usage.md)、[Deployment](en/deployment.md)、[Troubleshooting](en/troubleshooting.md)、[Diagnostics](en/agent-tools.md) 和 [Agent](en/agent-runtime.md)。英文为精简说明，并非全部中文章节的完整翻译。
