"""Reject mismatched stable release tags before packaging or publishing."""
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
version = json.loads((root / 'cloudnest-web/package.json').read_text(encoding='utf-8'))['version']
assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version), 'Expected a semantic version'
pom = ET.parse(root / 'cloudnest-server/pom.xml')
assert pom.findtext('{http://maven.apache.org/POM/4.0.0}version') == version
lock = json.loads((root / 'cloudnest-web/package-lock.json').read_text(encoding='utf-8'))
assert lock['version'] == lock['packages']['']['version'] == version
assert json.loads((root / 'docs/openapi.json').read_text(encoding='utf-8'))['info']['version'] == version
ref = os.environ.get('GITHUB_REF', '')
if ref.startswith('refs/tags/'):
    assert re.fullmatch(r'\d+\.\d+\.\d+', version), 'Release tags require a stable version'
    assert ref == 'refs/tags/v' + version, 'Release tag does not match package versions'
if '-' not in version:
    assert (root / f'docs/releases/{version}.md').is_file(), 'Release notes missing'
print(f'Release version contract passed: {version}')
