# 改进功能：预期结果与验收依据

日期：2026-10-16。对应 `improvement-plan.md` 中 I01～I11。以下用例已实现；实际执行状态以 `improvement-report.md` 和原始证据为准。原版本的生命周期与备份证据保留为历史结果，不自动算作新版本重新执行。

## 自动化业务用例

| 用例名称（CloudNestIntegrationTest） | 前置与输入 | 预期结果及要检查的数据 |
| --- | --- | --- |
| IMP_I01_passwordRevokesEverySessionAndChecksOldPassword | 新注册用户；先传错误旧密码，再传正确密码；B 尝试撤销 A 会话 | 错误密码 403，原会话仍 200；会话列表标记当前；B 撤销返回 404；正确改密 200，原 Cookie 随后 401，数据库会话数为 0 |
| IMP_I03_batchPartialFailureAncestorsAndReplay | 父子目录同时选择，移动目标有重名且目标也被选择；随后重复删除、重复恢复 | 移动结果逐项失败，不把未执行的子项虚报成功；祖先先处理，子项由成功祖先覆盖；两次删除仍只有 2 个软删除项；恢复可重复；其他用户批量操作不改变目录 |
| IMP_I02_resolveFolderPathAndConflictPolicies | 解析“资料/章节”两次；包含 safe/.. 的路径；上传会话占用 notes.txt | 两次返回相同目录 ID；恶意路径 400 且 safe 目录整笔回滚；skip 返回 skipped=true；keep 返回 notes.txt (1)；其他用户根目录访问 404 |
| IMP_I04_favoritesRecentFiltersAndIsolation | 收藏目录后重命名；跨用户查询；删除和恢复；大小区间倒置 | 收藏显示新名；B 查询无结果；B 记录 A 打开行为 404；倒置区间 400；软删除后收藏隐藏、恢复后重新显示；容量分解可读取 |
| IMP_I07_folderShareCannotEscapeAndMovingOutRevokesAccess | 分享目录下含子目录和文件，根目录外另有私有文件 | 匿名元信息 kind=FOLDER；列举外部目录/读取外部文件 404；内部内容准确；移出共享目录后访问 404；删除共享根后 410；未知接口正确 404 |
| IMP_I08_versionsPinnedShareRestoreCleanupAndQuota | 3 字节旧文件，11 字节新文件；旧文件先创建分享，再加入新版本 | 总逻辑占用 14；当前下载为新内容，原分享仍为旧内容；B 无法查历史；过期 revision 恢复 409；正确恢复后占用仍 14；删除 11 字节历史后占用 3；彻删最后文件后容量与 blob 数均为 0 |
| IMP_I08_pinnedBlobSurvivesHistoryDeletion | 已分享旧内容，更新当前文件后删除旧历史，再执行清理 | 旧分享仍返回旧内容；不能因历史删除而损坏有效分享持有的底层对象 |
| IMP_I10_retentionSkipsNewerDescendantAndIsIdempotent | 删除父目录与此前独立删除的子目录；父删除时间设为 40 天前，子项为当前；保留 30 天 | 子项未到期时父目录不能连带清除；全部到期后只剩根目录；重复清理不改变结果；366 天策略返回 400 |
| IMP_I11_adminPrivilegesQuotaAndDisableRevokeSessions | 普通账号、显式设置的测试管理员；被管账号有 7 字节上传预留 | 普通用户管理接口 403；管理员可查；配额降到 1 字节返回 409；设置 1024 字节并禁用成功；被禁用户原 Cookie 401、会话数 0；其他用户活动查询隔离 |
| IMP_I09_thumbnailIsPrivateBoundedAndRemovedAfterPurge | 上传 600×300 PNG，轮询异步缩略图；随后软删除与彻删 | B 请求 404；生成中为 202，成功 PNG 为 256×128；软删除后缓存入口 404；彻删后可执行对象及缩略图清理。此用例验证权限和尺寸，不覆盖全部图片格式与 EXIF 方向 |
| IMP_I07_publicZipBoundToShareAndRevision | 匿名创建共享目录内文件的 ZIP；等待 READY；之后替换该文件版本并撤销分享 | 首次 ZIP 可下载；源文件版本变化后旧 ZIP 返回 409；分享撤销后任务状态入口 410 |
| IMP_I11_invitationIsSingleUseAndCarriesQuota | 管理员生成 1 MiB 邀请码；普通用户尝试生成；两次注册使用同一邀请码 | 普通用户生成 403；首次注册 quota=1048576；再次使用 409；数据库仅一份已消费邀请；不保存原始邀请码 |
| IMP_I07_editCodeAndPermissionRevokesOldGrants | 原提取码验证成功后，拥有者修改提取码并禁用下载，再移除提取码 | 旧 grant 预览 403，旧码验证 403；新码可预览但下载 403；移除提取码并允许下载后匿名下载 200；其他用户不能修改。验证与修改通过同一拥有者行锁串行，避免并发写回旧 grant |
| IMP_I05_downloadHistoryIsOwnedAndSurvivesRequests | 用户创建 ZIP 后读取任务列表，另一个新用户读取自己的列表 | 拥有者列表包含准确任务 ID 和源文件 ID；另一用户列表为空，不暴露他人任务 |
| IMP_I11_deniedResourceAttemptIsPrivateAuditEvent | 访客尝试读取他人目录并获得 404 | 仅访客自己的活动中新增 ACCESS_DENIED；文件拥有者无该事件；事件不保存目标 ID、路径、令牌或内容 |

这些用例与原有 29 项业务用例一起在 H2 和真实 MySQL/Redis 上运行；5 项存储契约用例仍使用模拟 S3 服务，不冒充真实 MinIO。

## 浏览器预期

| 用例 | 操作与可观察结果 |
| --- | --- |
| IMPUI01 | 上传 13 字节文本 → 收藏 → 键盘选择“我的收藏” → 勾选文件批量删除 → 点击 10 秒撤销 → 文件重新出现；账号安全页展示当前会话、有效文件 13 B、默认回收站保留 0 天；保存截图 |
| IMPUI02 | 选择真实三层文件夹 → 逐层进入目录 → 预览笔记；路径完整、正文为 folder import，复用真实上传接口 |
| IMPUI03 | 上传 version-one → 保存 version-two 为新版本 → 恢复旧版本 → 预览正文为 version-one；创建共享目录并上传 guest.txt → 匿名新浏览器能列举并预览 guest data；保存截图 |
| IMPUI04 | 真实登录后取得一次 CSRF token；三轮并行读取资料和统计后 Cookie 仍保持该 token，携带原 token 创建目录均 200；不携带 token 写入仍 403 |
| IMPUI05 | 上传并创建 ZIP → 下载任务显示可下载 → 刷新后仍可见 → 实际浏览器下载文件且头部为 PK；创建分享后修改提取码，分享列表显示有提取码 |
| UI01～UI04 | 回归已有注册、文件夹、文本预览、分享、删除恢复、网格、移动端布局、错误登录、20 MiB 暂停/刷新续传/秒传、图片解码和 PDF 两页画布渲染 |

## 独立进程预期

`scripts/operator-test.py` 使用独立目录及临时口令，不更改现有用户账号，也不向报告输出口令/Cookie。

- OP01：真正运行 create-admin；登录后 administrator=true；关闭公开注册时普通注册 403；邀请码可注册并继承 1 MiB 配额，重用 409。
- OP02：停止独立服务后真正运行 reset-password；重启后旧 Cookie 和旧密码均 401，新密码登录 200。
- OP03：包含新旧版本、固定分享的数据离线复制到新目录，重启后当前内容、历史内容和分享字节完全一致；逻辑容量仍为所有保留内容之和，原登录 Cookie 继续有效。

## 尚需单独验收

- 真实 Linux/HTTPS、MinIO、生产 MySQL 与对象卷成对备份恢复、升级回退；现有本机数据已经在升级前备份，但这不代替目标生产恢复演练。
- 音视频在不同浏览器/编码组合的播放和拖动、JPEG EXIF 自动方向、大量缩略图资源使用，以及超大目录压力测试。
- 多浏览器会话管理、目录拖放兼容性、更多冲突和中断场景的端到端覆盖；已有后端用例与有限 Chrome 交互不能代表所有客户端组合。
