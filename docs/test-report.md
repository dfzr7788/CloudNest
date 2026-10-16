# 实际验证报告

此文档为原版历史验收快照；本轮新增功能和最新结果见 [改进版验收报告](improvement-report.md)，请勿把下面 74 次历史结果当作新增功能验收。

生成时间：2026-10-16T13:51:35+08:00。由真实 XML/JSON 结果生成；预期细节见 [验收清单](acceptance.md)，问题与修复见 [执行日志](execution-log.md)。

已导入 74 项实际运行结果：74 通过、0 失败。相同后端用例在两种环境分别计为一次运行，不是不同功能数量。

| 编号 | 用例 | 环境 | 实际结果 | 耗时 | 证据 |
| --- | --- | --- | --- | --- | --- |
| U02 | U02_clientHashIsNotTrusted | H2 + local | PASS | 0.180s | [原始结果](evidence/h2-test-results.xml) |
| S01 | S01_protectedSharePermissionsAndRevocation | H2 + local | PASS | 1.047s | [原始结果](evidence/h2-test-results.xml) |
| C02 | C02_concurrentDirectoryMovesCannotCreateCycle | H2 + local | PASS | 0.085s | [原始结果](evidence/h2-test-results.xml) |
| F02 | F02_previewRangeAndUnsafeTypes | H2 + local | PASS | 0.258s | [原始结果](evidence/h2-test-results.xml) |
| A03 | A03_csrfAndUnauthenticatedAccess | H2 + local | PASS | 0.027s | [原始结果](evidence/h2-test-results.xml) |
| D02 | D02_namesConflictsAndNormalization | H2 + local | PASS | 0.078s | [原始结果](evidence/h2-test-results.xml) |
| B01 | B01_zipAndOwnership | H2 + local | PASS | 0.217s | [原始结果](evidence/h2-test-results.xml) |
| F01 | F01_roundTripUnicodeAndZeroBytes | H2 + local | PASS | 0.107s | [原始结果](evidence/h2-test-results.xml) |
| S02 | S02_deletedSourceAndExpiryInvalidateShare | H2 + local | PASS | 0.067s | [原始结果](evidence/h2-test-results.xml) |
| T01 | T01_deleteBatchRestoresOnlyOwnItems | H2 + local | PASS | 0.128s | [原始结果](evidence/h2-test-results.xml) |
| T03 | T03_purgeReleasesLogicalQuotaButNotSharedBlob | H2 + local | PASS | 0.302s | [原始结果](evidence/h2-test-results.xml) |
| T02 | T02_restoreConflictAndMissingParent | H2 + local | PASS | 0.099s | [原始结果](evidence/h2-test-results.xml) |
| D03 | D03_crossUserIsolation | H2 + local | PASS | 0.063s | [原始结果](evidence/h2-test-results.xml) |
| U08 | U08_databaseWriteFailureRollsBackAndRetries | H2 + local | PASS | 0.311s | [原始结果](evidence/h2-test-results.xml) |
| T04 | T04_purgeDirectoryIncludesHistoricTrash | H2 + local | PASS | 0.339s | [原始结果](evidence/h2-test-results.xml) |
| U07 | U07_invalidLengthAndExpiredSessionRejected | H2 + local | PASS | 0.037s | [原始结果](evidence/h2-test-results.xml) |
| A01 | A01_registrationRootAndProfile | H2 + local | PASS | 0.014s | [原始结果](evidence/h2-test-results.xml) |
| C01 | C01_concurrentQuotaCannotOversubscribe | H2 + local | PASS | 0.241s | [原始结果](evidence/h2-test-results.xml) |
| D01 | D01_nestedBreadcrumbsAndCycleProtection | H2 + local | PASS | 0.024s | [原始结果](evidence/h2-test-results.xml) |
| U05 | U05_storageFailureKeepsRetryableState | H2 + local | PASS | 0.052s | [原始结果](evidence/h2-test-results.xml) |
| F03 | F03_ordinaryUploadAndSearchPagination | H2 + local | PASS | 0.036s | [原始结果](evidence/h2-test-results.xml) |
| U03 | U03_quotaReservationCancellationExpiry | H2 + local | PASS | 0.252s | [原始结果](evidence/h2-test-results.xml) |
| F04 | F04_imageAndPdfDetectedFromContent | H2 + local | PASS | 0.104s | [原始结果](evidence/h2-test-results.xml) |
| U04 | U04_crossUserCannotInstantUpload | H2 + local | PASS | 0.063s | [原始结果](evidence/h2-test-results.xml) |
| C03 | C03_concurrentCompletePublishesOnce | H2 + local | PASS | 0.285s | [原始结果](evidence/h2-test-results.xml) |
| U01 | U01_partsResumeIdempotencyAndCompleteness | H2 + local | PASS | 0.086s | [原始结果](evidence/h2-test-results.xml) |
| A02 | A02_loginAndLogoutRevokeOldCookie | H2 + local | PASS | 0.748s | [原始结果](evidence/h2-test-results.xml) |
| D04 | D04_moveRenameKeepsPhysicalObject | H2 + local | PASS | 0.063s | [原始结果](evidence/h2-test-results.xml) |
| U06 | U06_metadataConflictAfterStorageWriteIsCompensated | H2 + local | PASS | 0.265s | [原始结果](evidence/h2-test-results.xml) |
| U02 | U02_clientHashIsNotTrusted | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.154s | [原始结果](evidence/mysql-test-results.xml) |
| S01 | S01_protectedSharePermissionsAndRevocation | MySQL 8.4.8 + Redis 3.0.504 | PASS | 1.044s | [原始结果](evidence/mysql-test-results.xml) |
| C02 | C02_concurrentDirectoryMovesCannotCreateCycle | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.099s | [原始结果](evidence/mysql-test-results.xml) |
| F02 | F02_previewRangeAndUnsafeTypes | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.265s | [原始结果](evidence/mysql-test-results.xml) |
| A03 | A03_csrfAndUnauthenticatedAccess | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.013s | [原始结果](evidence/mysql-test-results.xml) |
| D02 | D02_namesConflictsAndNormalization | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.062s | [原始结果](evidence/mysql-test-results.xml) |
| B01 | B01_zipAndOwnership | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.243s | [原始结果](evidence/mysql-test-results.xml) |
| F01 | F01_roundTripUnicodeAndZeroBytes | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.134s | [原始结果](evidence/mysql-test-results.xml) |
| S02 | S02_deletedSourceAndExpiryInvalidateShare | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.132s | [原始结果](evidence/mysql-test-results.xml) |
| T01 | T01_deleteBatchRestoresOnlyOwnItems | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.201s | [原始结果](evidence/mysql-test-results.xml) |
| T03 | T03_purgeReleasesLogicalQuotaButNotSharedBlob | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.421s | [原始结果](evidence/mysql-test-results.xml) |
| T02 | T02_restoreConflictAndMissingParent | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.200s | [原始结果](evidence/mysql-test-results.xml) |
| D03 | D03_crossUserIsolation | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.129s | [原始结果](evidence/mysql-test-results.xml) |
| U08 | U08_databaseWriteFailureRollsBackAndRetries | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.304s | [原始结果](evidence/mysql-test-results.xml) |
| T04 | T04_purgeDirectoryIncludesHistoricTrash | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.362s | [原始结果](evidence/mysql-test-results.xml) |
| U07 | U07_invalidLengthAndExpiredSessionRejected | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.075s | [原始结果](evidence/mysql-test-results.xml) |
| A01 | A01_registrationRootAndProfile | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.014s | [原始结果](evidence/mysql-test-results.xml) |
| C01 | C01_concurrentQuotaCannotOversubscribe | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.233s | [原始结果](evidence/mysql-test-results.xml) |
| D01 | D01_nestedBreadcrumbsAndCycleProtection | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.052s | [原始结果](evidence/mysql-test-results.xml) |
| U05 | U05_storageFailureKeepsRetryableState | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.087s | [原始结果](evidence/mysql-test-results.xml) |
| F03 | F03_ordinaryUploadAndSearchPagination | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.056s | [原始结果](evidence/mysql-test-results.xml) |
| U03 | U03_quotaReservationCancellationExpiry | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.301s | [原始结果](evidence/mysql-test-results.xml) |
| F04 | F04_imageAndPdfDetectedFromContent | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.087s | [原始结果](evidence/mysql-test-results.xml) |
| U04 | U04_crossUserCannotInstantUpload | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.058s | [原始结果](evidence/mysql-test-results.xml) |
| C03 | C03_concurrentCompletePublishesOnce | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.260s | [原始结果](evidence/mysql-test-results.xml) |
| U01 | U01_partsResumeIdempotencyAndCompleteness | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.102s | [原始结果](evidence/mysql-test-results.xml) |
| A02 | A02_loginAndLogoutRevokeOldCookie | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.686s | [原始结果](evidence/mysql-test-results.xml) |
| D04 | D04_moveRenameKeepsPhysicalObject | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.083s | [原始结果](evidence/mysql-test-results.xml) |
| U06 | U06_metadataConflictAfterStorageWriteIsCompensated | MySQL 8.4.8 + Redis 3.0.504 | PASS | 0.319s | [原始结果](evidence/mysql-test-results.xml) |
| M01 | M01_s3AdapterRoundTripRangeAndDelete | S3 HTTP mock + H2 (not real MinIO) | PASS | 0.316s | [原始结果](evidence/storage-contract-results.xml) |
| M03 | M03_failedVerificationKeepsLocalReference | S3 HTTP mock + H2 (not real MinIO) | PASS | 0.022s | [原始结果](evidence/storage-contract-results.xml) |
| M02 | M02_migrationCopiesVerifiesSwitchesAndKeepsSource | S3 HTTP mock + H2 (not real MinIO) | PASS | 0.026s | [原始结果](evidence/storage-contract-results.xml) |
| M05 | M05_crashedLocalWriteCandidateCanBeCleaned | S3 HTTP mock + H2 (not real MinIO) | PASS | 0.004s | [原始结果](evidence/storage-contract-results.xml) |
| M04 | M04_recountRepairsLogicalCounters | S3 HTTP mock + H2 (not real MinIO) | PASS | 0.028s | [原始结果](evidence/storage-contract-results.xml) |
| UI01 | UI01 完整浏览器流程：注册、目录、上传预览、分享、恢复、网格与退出 | Chrome / browser | PASS | 10.231s | [原始结果](evidence/browser-results.json) |
| UI02 | UI02 错误登录显示明确反馈 | Chrome / browser | PASS | 1.069s | [原始结果](evidence/browser-results.json) |
| UI03 | UI03 上传暂停、刷新重选原文件续传与秒传 | Chrome / browser | PASS | 17.618s | [原始结果](evidence/browser-results.json) |
| UI04 | UI04 图片与 PDF 双页实际渲染 | Chrome / browser | PASS | 5.349s | [原始结果](evidence/browser-results.json) |
| L01 | 128 分片中的前 64 片在重启后保留；旧登录凭证仍可用于未过期会话 | Real HTTP / 256 MiB heap / file H2 | PASS | 9.009s | [原始结果](evidence/lifecycle-results.json) |
| L02 | 1 GiB 上传、合并、下载摘要完全一致；Java 最大堆 256 MiB | Real HTTP / 256 MiB heap / file H2 | PASS | 9.298s | [原始结果](evidence/lifecycle-results.json) |
| L03 | 同用户秒传不传分片；两个逻辑文件分别占用空间 | Real HTTP / 256 MiB heap / file H2 | PASS | 0.146s | [原始结果](evidence/lifecycle-results.json) |
| L04 | 离线备份恢复到全新目录，账号、配额、签名会话与 1 GiB 文件保持一致 | Real HTTP / 256 MiB heap / file H2 | PASS | 10.224s | [原始结果](evidence/lifecycle-results.json) |
| L05 | 离线维护核对配额与 1 GiB 对象摘要，无差异退出 0 | Real HTTP / 256 MiB heap / file H2 | PASS | 6.694s | [原始结果](evidence/lifecycle-results.json) |
| BACKUP01 | Windows 交付脚本归档恢复 | Windows / offline backup | PASS | 0.000s | [原始结果](evidence/backup-script-results.json) |
| PERF01 | 真实 HTTP 分页与小数据集延迟样本 | Real HTTP / local H2 / small dataset | PASS | 0.000s | [原始结果](evidence/http-smoke-results.json) |

## 构建和边界

- 后端发布包见 backend-package.txt，前端类型检查/构建见 frontend-build.txt，依赖复查见 dependency-audit.txt。
- 生命周期测试在最大 256 MiB Java 堆运行；该值不是整个进程 RSS，也不是生产压测结论。完整字节数、摘要、配额与重启恢复结果见 lifecycle-results.json。
- 浏览器截图见 login-desktop.png、drive-desktop.png、drive-mobile.png；UI01/03 子步骤见验收清单。
- MinIO 真实服务与迁移：未验证，官方社区下载返回 410；Linux Docker Compose、TLS、MySQL/对象卷成对恢复：未验证，当前环境无 Docker、目标服务器和证书。
- 已实际验证的是本地 H2 全目录离线备份恢复；不能替代 Linux/MySQL/MinIO 恢复演练。
- 初次 CSRF、H2 延迟提交和开发模式依赖重载问题及修复保存在执行日志，首次强制重启失败原始证据为 lifecycle-first-failure.json。
