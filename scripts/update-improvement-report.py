"""Generate improvement evidence index from completed XML/JSON runs."""
from pathlib import Path
import datetime,hashlib,json,re,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1];EVIDENCE=ROOT/'docs/evidence';results=[]
for file,environment in [('improvement-h2-test-results.xml','H2 + local'),('improvement-mysql-test-results.xml','MySQL 8.4 + Redis 3.0 + local'),('improvement-storage-test-results.xml','Simulated S3 contract')]:
 tree=ET.parse(EVIDENCE/file).getroot()
 for case in tree.findall('testcase'):
  status='FAIL'if case.find('failure')is not None or case.find('error')is not None else 'SKIP'if case.find('skipped')is not None else 'PASS'
  results.append(dict(name=case.attrib['name'],environment=environment,status=status,seconds=float(case.attrib.get('time',0)),evidence=file))
browser=json.loads((EVIDENCE/'improvement-browser-results.json').read_text(encoding='utf8'))
def visit(suites):
 for suite in suites:
  visit(suite.get('suites',[]))
  for spec in suite.get('specs',[]):
   for test in spec.get('tests',[]):
    last=test['results'][-1]
    results.append(dict(name=spec['title'],environment='Chrome',status='PASS'if last['status']=='passed' else 'FAIL',seconds=last.get('duration',0)/1000,evidence='improvement-browser-results.json'))
visit(browser['suites'])
for item in json.loads((EVIDENCE/'improvement-operator-results.json').read_text(encoding='utf8')):
 results.append(dict(name=item['id'],environment='Isolated real Java process',status=item['status'],actual=item['actual'],evidence='improvement-operator-results.json'))
stamp=datetime.datetime.now().astimezone().isoformat(timespec='seconds')
(EVIDENCE/'improvement-results-index.json').write_text(json.dumps(dict(generatedAt=stamp,results=results),ensure_ascii=False,indent=2),encoding='utf8')
counts={group:sum(1 for r in results if r['environment']==group)for group in sorted({r['environment']for r in results})}
passed=sum(r['status']=='PASS'for r in results);failed=sum(r['status']=='FAIL'for r in results)
lines=['# 改进版实际验收报告','',f'生成时间：{stamp}。由本轮真实 XML/JSON 生成，当前 {len(results)} 次运行：{passed} 通过、{failed} 失败。相同业务在 H2/MySQL 的两次运行分开记录；存储契约的重复运行仅保留一次，不与原版 74 次历史记录相加。','', '功能与逐项预期见 [改进验收清单](improvement-acceptance.md)，使用方法见 [新版使用说明](improvement-usage.md)。','', '| 环境 | 本次结果数量 |','| --- | --- |']
lines += [f'| {group} | {count} |'for group,count in counts.items()]
lines += ['','## 逐项实际结果','','| 用例 | 环境 | 实际结果 | 耗时（秒） | 证据 |','| --- | --- | --- | --- | --- |']
for r in results:lines.append(f"| {r['name']} | {r['environment']} | {r['status']} | {r.get('seconds','—')} | [原始记录](evidence/{r['evidence']}) |")
lines += ['','## 构建与真实恢复','', '- 后端 Maven package 成功，前端类型检查及生产构建成功；原始输出分别见 evidence/improvement-backend-tests.txt 和 improvement-frontend-build.txt。', '- 数据从原 H2 V1 增量升级到 V6；真实 MySQL 独立测试库升级到 V7。升级前备份：backups/pre-improvement/cloudnest-20261016-152757.zip，SHA-256 为 39DB234C61AA50EA081EB8B2D59267B8886D97C1F7157B2260A0821B81073CF2。', '- OP03 将新版数据离线复制并从新目录启动，验证当前内容、历史内容、固定分享、逻辑配额和原登录会话均保留。没有将其写成生产 MySQL/对象存储恢复演练。', '- 截图：evidence/improvement-account.png、improvement-folder-share.png；历史流程也重新保存桌面、移动端和 PDF 截图。', '', '## 发现与修复','', '1. 未知接口被通用异常处理成 500，增加资源不存在处理，实际为 404。', '2. 批量父级操作失败时子项曾被计为已覆盖；改为先处理祖先，父级失败时子项明确失败。', '3. 传输浮层遮挡文件操作，改成页面内传输区域。', '4. 浏览器测试产物中的 HTML 触发 Vite 整页刷新，配置忽略测试产物目录。', '5. 无状态认证导致每次请求都触发 CSRF 身份变更策略并清除 Cookie，并行读取偶尔使写入 403。改为显式在登录/注册/退出清除令牌，普通认证不轮换；IMPUI04 验证并行读取稳定、合法写入 200、无令牌写入仍 403。', '6. ZIP 增加创建时的文件修订快照，版本变化后旧包返回 409，避免下载过时内容。', '', '## 仍然保留的范围与限制','', '- 用户选择后续自行配置服务器，本轮交付本机版和部署配置。真实 Linux/HTTPS、MinIO 与生产成对恢复仍待验收。', '- S3 适配仍是模拟契约验证；Redis 实测版本为本机 3.0.504，Compose 中的 7.4 尚未运行验收。', '- 图片缩略图只覆盖受支持且受大小/像素限制的文件，JPEG EXIF 自动方向未验收；音视频格式/编码的完整播放兼容矩阵未验收。', '- 邮箱自助找回尚未实现；WebDAV、全文检索、Office 转换与 AI 功能继续按计划暂缓。', '- 前端主 UI 包仍有较大体积构建提示；没有把构建成功当成性能优化完成。', '', '记录中每个 PASS 均来自实际运行；外部环境与上述未覆盖项不记为通过。']
(ROOT/'docs/improvement-report.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
files=[ROOT/'cloudnest-server/target/cloudnest-server-1.0.0.jar',ROOT/'cloudnest-web/package-lock.json',*sorted((ROOT/'cloudnest-web/dist').rglob('*'))]
artifacts=[dict(path=p.relative_to(ROOT).as_posix(),bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest())for p in files if p.is_file()]
(ROOT/'docs/release-manifest.json').write_text(json.dumps(dict(generatedAt=stamp,version='1.0.0',variant='2026-10-16-improvements',artifacts=artifacts),indent=2),encoding='utf8')
print(f'Recorded {len(results)} runs: {passed} PASS, {failed} FAIL; manifest {len(artifacts)} artifacts')
