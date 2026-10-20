# CloudNest 轻量存储贡献节点

需要 Python 3.11+；仅为获得授权的团队贡献专用目录。停止程序不会删除文件。建议先在 Tailscale 等受控私网验证，不要直接公开 HTTP 端口。

1. 选择新的空目录作为对象目录；已有个人资料目录会被拒绝。
2. 在目录之外保存节点令牌文件，只允许当前系统用户读取。首次运行可以使用 `--initialize-token` 创建随机令牌；Windows 用户应使用文件属性的安全权限限制令牌文件访问。
3. 指定字节容量上限、私网绑定地址，并启动节点：

```powershell
python clients/storage-node/node.py --directory G:\CloudNestContribution --budget 10737418240 --token-file C:\CloudNestSecrets\node-token.txt --initialize-token --bind 100.100.10.20 --port 8099
```

上述目录由成员自行指定并创建父目录。示例不自动修改防火墙或安装远程控制工具。后续启动移除 `--initialize-token`，避免覆盖已有令牌。需要公网连接时使用 `--cert` 和 `--key` 提供可信 HTTPS 证书。

4. 登录团队空间，由管理员单独授予 Contributor 身份；在「存储贡献」登记地址、节点令牌和容量。节点访问令牌使用服务端加密保存，不向其他成员显示。
5. 团队管理员审核后，系统管理员检查地址、物理故障域并执行认证读写探测。不同路径、盘符或端口不自动意味着独立故障域。
6. 将登记时展示的节点 ID 和一次性心跳令牌保存到本机文件，重启节点时追加：

```powershell
--control-center https://cloud.example.com --node-id <节点ID> --heartbeat-token-file C:\CloudNestSecrets\heartbeat-token.txt
```

控制中心心跳必须使用 HTTPS，每 30 秒发送。120 秒未收到有效心跳即停止新分配。授权撤销、停止贡献或节点健康失效也停止新分配；原有数据仍按可用副本读取，勿直接清空贡献目录。迁出并验证全部数据之前，成员应保留该目录和令牌。

节点只接受 `/objects/<UUID>`，拒绝任意文件路径、路径穿越、符号链接和 Windows 目录联接。对象写入使用临时文件、长度检查、磁盘预算、原子替换；读取支持 Range。最多 8 个连接、一次写入；对象哈希由控制中心验证。启动时会清理未提交的 `.part` 临时对象。

HTTP Bearer 认证依赖 HTTPS 或受控加密私网保护；不应将明文 HTTP 暴露在不可信网络。节点持有者控制其物理磁盘，仍可能读取、修改或关闭节点，因此重要文件必须保留独立故障域副本与备份。当前不是客户端加密保险箱。

本机协议测试：`python scripts/test-storage-node.py`。测试仅使用临时目录与回环端口。
