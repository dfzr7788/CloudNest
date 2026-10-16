"""Summarize executed evidence, keeping unavailable environments explicit."""
from pathlib import Path
import datetime
import json
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
EVIDENCE=ROOT/'docs/evidence'
records=[]
for filename,environment in [('h2-test-results.xml','H2 + local'),('mysql-test-results.xml','MySQL 8.4.8 + Redis 3.0.504'),('storage-contract-results.xml','S3 HTTP mock + H2 (not real MinIO)')]:
    path=EVIDENCE/filename
    if not path.exists(): continue
    suite=ET.parse(path).getroot()
    for case in suite.findall('testcase'):
        name=case.get('name','')
        outcome='FAIL' if case.find('failure') is not None or case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'
        records.append(dict(id=name.split('_',1)[0],name=name,environment=environment,status=outcome,seconds=float(case.get('time','0')),evidence=filename))
browser=EVIDENCE/'browser-results.json'
if browser.exists():
    def suites(items):
        for suite in items:
            for spec in suite.get('specs',[]):
                for test in spec.get('tests',[]):
                    results=test.get('results',[])
                    latest=results[-1] if results else {}
                    name=spec['title']
                    records.append(dict(id=name.split()[0],name=name,environment='Chrome / browser',status='PASS' if latest.get('status')=='passed' else 'FAIL',seconds=latest.get('duration',0)/1000,evidence='browser-results.json'))
            suites(suite.get('suites',[]))
    suites(json.loads(browser.read_text(encoding='utf-8-sig')).get('suites',[]))
lifecycle=EVIDENCE/'lifecycle-results.json'
if lifecycle.exists():
    for result in json.loads(lifecycle.read_text(encoding='utf-8-sig')):
        records.append(dict(id=result['id'],name=result.get('expected',result['id']),environment='Real HTTP / 256 MiB heap / file H2',status=result['status'],seconds=result.get('seconds',0),actual=result.get('actual'),evidence='lifecycle-results.json'))
stamp=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(timespec='seconds')
for filename,name in [('backup-script-results.json','Windows 交付脚本归档恢复'),('http-smoke-results.json','真实 HTTP 分页与小数据集延迟样本')]:
    p=EVIDENCE/filename
    if p.exists():
        r=json.loads(p.read_text(encoding='utf-8-sig'))
        records.append(dict(id=r['id'],name=name,environment=r.get('environment','Windows / offline backup'),status=r['status'],seconds=0,actual=r.get('actual',r),evidence=filename))
(EVIDENCE/'results-index.json').write_text(json.dumps(dict(generatedAt=stamp,records=records),ensure_ascii=False,indent=2),encoding='utf-8')
passed=sum(r['status']=='PASS' for r in records)
failed=sum(r['status']=='FAIL' for r in records)
lines=['# 实际验证报告','',f'生成时间：{stamp}。由真实 XML/JSON 结果生成；预期细节见 [验收清单](acceptance.md)，问题与修复见 [执行日志](execution-log.md)。','',f'已导入 {len(records)} 项实际运行结果：{passed} 通过、{failed} 失败。相同后端用例在两种环境分别计为一次运行，不是不同功能数量。','', '| 编号 | 用例 | 环境 | 实际结果 | 耗时 | 证据 |','| --- | --- | --- | --- | --- | --- |']
for r in records:
    lines.append(f"| {r['id']} | {r['name']} | {r['environment']} | {r['status']} | {r['seconds']:.3f}s | [原始结果](evidence/{r['evidence']}) |")
lines += ['', '## 构建和边界', '', '- 后端发布包见 backend-package.txt，前端类型检查/构建见 frontend-build.txt，依赖复查见 dependency-audit.txt。','- 生命周期测试在最大 256 MiB Java 堆运行；该值不是整个进程 RSS，也不是生产压测结论。完整字节数、摘要、配额与重启恢复结果见 lifecycle-results.json。','- 浏览器截图见 login-desktop.png、drive-desktop.png、drive-mobile.png；UI01/03 子步骤见验收清单。','- MinIO 真实服务与迁移：未验证，官方社区下载返回 410；Linux Docker Compose、TLS、MySQL/对象卷成对恢复：未验证，当前环境无 Docker、目标服务器和证书。','- 已实际验证的是本地 H2 全目录离线备份恢复；不能替代 Linux/MySQL/MinIO 恢复演练。','- 初次 CSRF、H2 延迟提交和开发模式依赖重载问题及修复保存在执行日志，首次强制重启失败原始证据为 lifecycle-first-failure.json。','']
(ROOT/'docs/test-report.md').write_text('\n'.join(lines),encoding='utf-8')
print(f'{passed} passed, {failed} failed, {len(records)} executed records')
