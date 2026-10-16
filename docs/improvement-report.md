# 改进版实际验收报告

生成时间：2026-10-16T20:12:02+08:00。由本轮真实 XML/JSON 生成，当前 105 次运行：105 通过、0 失败。相同业务在 H2/MySQL 的两次运行分开记录；存储契约的重复运行仅保留一次，不与原版 74 次历史记录相加。

功能与逐项预期见 [改进验收清单](improvement-acceptance.md)，使用方法见 [新版使用说明](improvement-usage.md)。

| 环境 | 本次结果数量 |
| --- | --- |
| Chrome | 9 |
| H2 + local | 44 |
| Isolated real Java process | 3 |
| MySQL 8.4 + Redis 3.0 + local | 44 |
| Simulated S3 contract | 5 |

## 逐项实际结果

| 用例 | 环境 | 实际结果 | 耗时（秒） | 证据 |
| --- | --- | --- | --- | --- |
| IMP_I01_passwordRevokesEverySessionAndChecksOldPassword | H2 + local | PASS | 1.004 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U02_clientHashIsNotTrusted | H2 + local | PASS | 0.09 | [原始记录](evidence/improvement-h2-test-results.xml) |
| S01_protectedSharePermissionsAndRevocation | H2 + local | PASS | 0.95 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I09_thumbnailIsPrivateBoundedAndRemovedAfterPurge | H2 + local | PASS | 0.607 | [原始记录](evidence/improvement-h2-test-results.xml) |
| C02_concurrentDirectoryMovesCannotCreateCycle | H2 + local | PASS | 0.052 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I11_adminPrivilegesQuotaAndDisableRevokeSessions | H2 + local | PASS | 0.539 | [原始记录](evidence/improvement-h2-test-results.xml) |
| F02_previewRangeAndUnsafeTypes | H2 + local | PASS | 0.178 | [原始记录](evidence/improvement-h2-test-results.xml) |
| A03_csrfAndUnauthenticatedAccess | H2 + local | PASS | 0.022 | [原始记录](evidence/improvement-h2-test-results.xml) |
| D02_namesConflictsAndNormalization | H2 + local | PASS | 0.069 | [原始记录](evidence/improvement-h2-test-results.xml) |
| B01_zipAndOwnership | H2 + local | PASS | 0.22 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I11_invitationIsSingleUseAndCarriesQuota | H2 + local | PASS | 0.683 | [原始记录](evidence/improvement-h2-test-results.xml) |
| F01_roundTripUnicodeAndZeroBytes | H2 + local | PASS | 0.11 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I07_folderShareCannotEscapeAndMovingOutRevokesAccess | H2 + local | PASS | 0.35 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I07_publicZipBoundToShareAndRevision | H2 + local | PASS | 0.433 | [原始记录](evidence/improvement-h2-test-results.xml) |
| S02_deletedSourceAndExpiryInvalidateShare | H2 + local | PASS | 0.062 | [原始记录](evidence/improvement-h2-test-results.xml) |
| T01_deleteBatchRestoresOnlyOwnItems | H2 + local | PASS | 0.128 | [原始记录](evidence/improvement-h2-test-results.xml) |
| T03_purgeReleasesLogicalQuotaButNotSharedBlob | H2 + local | PASS | 0.328 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I08_pinnedBlobSurvivesHistoryDeletion | H2 + local | PASS | 0.319 | [原始记录](evidence/improvement-h2-test-results.xml) |
| T02_restoreConflictAndMissingParent | H2 + local | PASS | 0.089 | [原始记录](evidence/improvement-h2-test-results.xml) |
| D03_crossUserIsolation | H2 + local | PASS | 0.067 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I02_resolveFolderPathAndConflictPolicies | H2 + local | PASS | 0.282 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I03_batchPartialFailureAncestorsAndReplay | H2 + local | PASS | 0.296 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U08_databaseWriteFailureRollsBackAndRetries | H2 + local | PASS | 0.28 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I11_deniedResourceAttemptIsPrivateAuditEvent | H2 + local | PASS | 0.449 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I07_editCodeAndPermissionRevokesOldGrants | H2 + local | PASS | 1.374 | [原始记录](evidence/improvement-h2-test-results.xml) |
| T04_purgeDirectoryIncludesHistoricTrash | H2 + local | PASS | 0.299 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U07_invalidLengthAndExpiredSessionRejected | H2 + local | PASS | 0.028 | [原始记录](evidence/improvement-h2-test-results.xml) |
| A01_registrationRootAndProfile | H2 + local | PASS | 0.018 | [原始记录](evidence/improvement-h2-test-results.xml) |
| C01_concurrentQuotaCannotOversubscribe | H2 + local | PASS | 0.243 | [原始记录](evidence/improvement-h2-test-results.xml) |
| D01_nestedBreadcrumbsAndCycleProtection | H2 + local | PASS | 0.029 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U05_storageFailureKeepsRetryableState | H2 + local | PASS | 0.047 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I08_versionsPinnedShareRestoreCleanupAndQuota | H2 + local | PASS | 0.324 | [原始记录](evidence/improvement-h2-test-results.xml) |
| F03_ordinaryUploadAndSearchPagination | H2 + local | PASS | 0.036 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U03_quotaReservationCancellationExpiry | H2 + local | PASS | 0.263 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I10_retentionSkipsNewerDescendantAndIsIdempotent | H2 + local | PASS | 0.26 | [原始记录](evidence/improvement-h2-test-results.xml) |
| F04_imageAndPdfDetectedFromContent | H2 + local | PASS | 0.075 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I04_favoritesRecentFiltersAndIsolation | H2 + local | PASS | 0.289 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U04_crossUserCannotInstantUpload | H2 + local | PASS | 0.039 | [原始记录](evidence/improvement-h2-test-results.xml) |
| C03_concurrentCompletePublishesOnce | H2 + local | PASS | 0.263 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U01_partsResumeIdempotencyAndCompleteness | H2 + local | PASS | 0.093 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I05_downloadHistoryIsOwnedAndSurvivesRequests | H2 + local | PASS | 0.606 | [原始记录](evidence/improvement-h2-test-results.xml) |
| A02_loginAndLogoutRevokeOldCookie | H2 + local | PASS | 0.995 | [原始记录](evidence/improvement-h2-test-results.xml) |
| D04_moveRenameKeepsPhysicalObject | H2 + local | PASS | 0.098 | [原始记录](evidence/improvement-h2-test-results.xml) |
| U06_metadataConflictAfterStorageWriteIsCompensated | H2 + local | PASS | 0.312 | [原始记录](evidence/improvement-h2-test-results.xml) |
| IMP_I01_passwordRevokesEverySessionAndChecksOldPassword | MySQL 8.4 + Redis 3.0 + local | PASS | 1.131 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U02_clientHashIsNotTrusted | MySQL 8.4 + Redis 3.0 + local | PASS | 0.155 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| S01_protectedSharePermissionsAndRevocation | MySQL 8.4 + Redis 3.0 + local | PASS | 1.294 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I09_thumbnailIsPrivateBoundedAndRemovedAfterPurge | MySQL 8.4 + Redis 3.0 + local | PASS | 1.049 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| C02_concurrentDirectoryMovesCannotCreateCycle | MySQL 8.4 + Redis 3.0 + local | PASS | 0.146 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I11_adminPrivilegesQuotaAndDisableRevokeSessions | MySQL 8.4 + Redis 3.0 + local | PASS | 0.935 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| F02_previewRangeAndUnsafeTypes | MySQL 8.4 + Redis 3.0 + local | PASS | 0.318 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| A03_csrfAndUnauthenticatedAccess | MySQL 8.4 + Redis 3.0 + local | PASS | 0.02 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| D02_namesConflictsAndNormalization | MySQL 8.4 + Redis 3.0 + local | PASS | 0.174 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| B01_zipAndOwnership | MySQL 8.4 + Redis 3.0 + local | PASS | 0.468 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I11_invitationIsSingleUseAndCarriesQuota | MySQL 8.4 + Redis 3.0 + local | PASS | 0.898 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| F01_roundTripUnicodeAndZeroBytes | MySQL 8.4 + Redis 3.0 + local | PASS | 0.305 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I07_folderShareCannotEscapeAndMovingOutRevokesAccess | MySQL 8.4 + Redis 3.0 + local | PASS | 0.746 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I07_publicZipBoundToShareAndRevision | MySQL 8.4 + Redis 3.0 + local | PASS | 0.488 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| S02_deletedSourceAndExpiryInvalidateShare | MySQL 8.4 + Redis 3.0 + local | PASS | 0.105 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| T01_deleteBatchRestoresOnlyOwnItems | MySQL 8.4 + Redis 3.0 + local | PASS | 0.533 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| T03_purgeReleasesLogicalQuotaButNotSharedBlob | MySQL 8.4 + Redis 3.0 + local | PASS | 1.326 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I08_pinnedBlobSurvivesHistoryDeletion | MySQL 8.4 + Redis 3.0 + local | PASS | 0.925 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| T02_restoreConflictAndMissingParent | MySQL 8.4 + Redis 3.0 + local | PASS | 0.464 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| D03_crossUserIsolation | MySQL 8.4 + Redis 3.0 + local | PASS | 0.183 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I02_resolveFolderPathAndConflictPolicies | MySQL 8.4 + Redis 3.0 + local | PASS | 0.659 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I03_batchPartialFailureAncestorsAndReplay | MySQL 8.4 + Redis 3.0 + local | PASS | 0.698 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U08_databaseWriteFailureRollsBackAndRetries | MySQL 8.4 + Redis 3.0 + local | PASS | 0.505 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I11_deniedResourceAttemptIsPrivateAuditEvent | MySQL 8.4 + Redis 3.0 + local | PASS | 0.707 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I07_editCodeAndPermissionRevokesOldGrants | MySQL 8.4 + Redis 3.0 + local | PASS | 1.965 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| T04_purgeDirectoryIncludesHistoricTrash | MySQL 8.4 + Redis 3.0 + local | PASS | 0.524 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U07_invalidLengthAndExpiredSessionRejected | MySQL 8.4 + Redis 3.0 + local | PASS | 0.27 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| A01_registrationRootAndProfile | MySQL 8.4 + Redis 3.0 + local | PASS | 0.026 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| C01_concurrentQuotaCannotOversubscribe | MySQL 8.4 + Redis 3.0 + local | PASS | 0.26 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| D01_nestedBreadcrumbsAndCycleProtection | MySQL 8.4 + Redis 3.0 + local | PASS | 0.064 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U05_storageFailureKeepsRetryableState | MySQL 8.4 + Redis 3.0 + local | PASS | 0.12 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I08_versionsPinnedShareRestoreCleanupAndQuota | MySQL 8.4 + Redis 3.0 + local | PASS | 0.721 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| F03_ordinaryUploadAndSearchPagination | MySQL 8.4 + Redis 3.0 + local | PASS | 0.054 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U03_quotaReservationCancellationExpiry | MySQL 8.4 + Redis 3.0 + local | PASS | 0.41 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I10_retentionSkipsNewerDescendantAndIsIdempotent | MySQL 8.4 + Redis 3.0 + local | PASS | 0.355 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| F04_imageAndPdfDetectedFromContent | MySQL 8.4 + Redis 3.0 + local | PASS | 0.195 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I04_favoritesRecentFiltersAndIsolation | MySQL 8.4 + Redis 3.0 + local | PASS | 0.38 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U04_crossUserCannotInstantUpload | MySQL 8.4 + Redis 3.0 + local | PASS | 0.087 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| C03_concurrentCompletePublishesOnce | MySQL 8.4 + Redis 3.0 + local | PASS | 0.329 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U01_partsResumeIdempotencyAndCompleteness | MySQL 8.4 + Redis 3.0 + local | PASS | 0.194 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| IMP_I05_downloadHistoryIsOwnedAndSurvivesRequests | MySQL 8.4 + Redis 3.0 + local | PASS | 0.516 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| A02_loginAndLogoutRevokeOldCookie | MySQL 8.4 + Redis 3.0 + local | PASS | 0.765 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| D04_moveRenameKeepsPhysicalObject | MySQL 8.4 + Redis 3.0 + local | PASS | 0.121 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| U06_metadataConflictAfterStorageWriteIsCompensated | MySQL 8.4 + Redis 3.0 + local | PASS | 0.596 | [原始记录](evidence/improvement-mysql-test-results.xml) |
| M01_s3AdapterRoundTripRangeAndDelete | Simulated S3 contract | PASS | 0.622 | [原始记录](evidence/improvement-storage-test-results.xml) |
| M03_failedVerificationKeepsLocalReference | Simulated S3 contract | PASS | 0.047 | [原始记录](evidence/improvement-storage-test-results.xml) |
| M02_migrationCopiesVerifiesSwitchesAndKeepsSource | Simulated S3 contract | PASS | 0.062 | [原始记录](evidence/improvement-storage-test-results.xml) |
| M05_crashedLocalWriteCandidateCanBeCleaned | Simulated S3 contract | PASS | 0.007 | [原始记录](evidence/improvement-storage-test-results.xml) |
| M04_recountRepairsLogicalCounters | Simulated S3 contract | PASS | 0.054 | [原始记录](evidence/improvement-storage-test-results.xml) |
| UI01 完整浏览器流程：注册、目录、上传预览、分享、恢复、网格与退出 | Chrome | PASS | 9.895 | [原始记录](evidence/improvement-browser-results.json) |
| UI02 错误登录显示明确反馈 | Chrome | PASS | 1.197 | [原始记录](evidence/improvement-browser-results.json) |
| UI03 上传暂停、刷新重选原文件续传与秒传 | Chrome | PASS | 12.695 | [原始记录](evidence/improvement-browser-results.json) |
| UI04 图片与 PDF 双页实际渲染 | Chrome | PASS | 3.834 | [原始记录](evidence/improvement-browser-results.json) |
| IMPUI04 并行读取不清除安全令牌且无令牌写入仍被拒绝 | Chrome | PASS | 1.324 | [原始记录](evidence/improvement-browser-results.json) |
| IMPUI01 收藏、批量删除撤销与账号会话设置 | Chrome | PASS | 3.664 | [原始记录](evidence/improvement-browser-results.json) |
| IMPUI02 文件夹导入保留三层结构 | Chrome | PASS | 2.087 | [原始记录](evidence/improvement-browser-results.json) |
| IMPUI03 版本上传恢复和文件夹匿名分享 | Chrome | PASS | 6.695 | [原始记录](evidence/improvement-browser-results.json) |
| IMPUI05 下载任务刷新保留、真实 ZIP 下载与分享设置修改 | Chrome | PASS | 4.476 | [原始记录](evidence/improvement-browser-results.json) |
| OP01 | Isolated real Java process | PASS | — | [原始记录](evidence/improvement-operator-results.json) |
| OP02 | Isolated real Java process | PASS | — | [原始记录](evidence/improvement-operator-results.json) |
| OP03 | Isolated real Java process | PASS | — | [原始记录](evidence/improvement-operator-results.json) |

## 构建与真实恢复

- 后端 Maven package 成功，前端类型检查及生产构建成功；原始输出分别见 evidence/improvement-backend-tests.txt 和 improvement-frontend-build.txt。
- 数据从原 H2 V1 增量升级到 V6；真实 MySQL 独立测试库升级到 V7。升级前备份：backups/pre-improvement/cloudnest-20261016-152757.zip，SHA-256 为 39DB234C61AA50EA081EB8B2D59267B8886D97C1F7157B2260A0821B81073CF2。
- OP03 将新版数据离线复制并从新目录启动，验证当前内容、历史内容、固定分享、逻辑配额和原登录会话均保留。没有将其写成生产 MySQL/对象存储恢复演练。
- 截图：evidence/improvement-account.png、improvement-folder-share.png；历史流程也重新保存桌面、移动端和 PDF 截图。

## 发现与修复

1. 未知接口被通用异常处理成 500，增加资源不存在处理，实际为 404。
2. 批量父级操作失败时子项曾被计为已覆盖；改为先处理祖先，父级失败时子项明确失败。
3. 传输浮层遮挡文件操作，改成页面内传输区域。
4. 浏览器测试产物中的 HTML 触发 Vite 整页刷新，配置忽略测试产物目录。
5. 无状态认证导致每次请求都触发 CSRF 身份变更策略并清除 Cookie，并行读取偶尔使写入 403。改为显式在登录/注册/退出清除令牌，普通认证不轮换；IMPUI04 验证并行读取稳定、合法写入 200、无令牌写入仍 403。
6. ZIP 增加创建时的文件修订快照，版本变化后旧包返回 409，避免下载过时内容。

## 仍然保留的范围与限制

- 用户选择后续自行配置服务器，本轮交付本机版和部署配置。真实 Linux/HTTPS、MinIO 与生产成对恢复仍待验收。
- S3 适配仍是模拟契约验证；Redis 实测版本为本机 3.0.504，Compose 中的 7.4 尚未运行验收。
- 图片缩略图只覆盖受支持且受大小/像素限制的文件，JPEG EXIF 自动方向未验收；音视频格式/编码的完整播放兼容矩阵未验收。
- 邮箱自助找回尚未实现；WebDAV、全文检索、Office 转换与 AI 功能继续按计划暂缓。
- 前端主 UI 包仍有较大体积构建提示；没有把构建成功当成性能优化完成。

记录中每个 PASS 均来自实际运行；外部环境与上述未覆盖项不记为通过。
