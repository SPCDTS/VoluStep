"""发布标签必须与应用版本一致，正式更新使用递增的 versionCode。"""
import os
import re
import subprocess
from pathlib import Path

source = Path('app/build.gradle.kts').read_text(encoding='utf-8')
version = re.search(r'versionName\s*=\s*"([^"]+)"', source).group(1)
code = int(re.search(r'versionCode\s*=\s*(\d+)', source).group(1))
tag = os.environ['RELEASE_TAG']
if tag != f'v{version}' or code <= 0:
    raise SystemExit(f'发布标签 {tag} 与应用版本 {version} / {code} 不一致')
tags = subprocess.check_output(['git', 'tag', '--merged', 'HEAD'], text=True).splitlines()
for previous in tags:
    if previous == tag or not re.fullmatch(r'v\d+\.\d+\.\d+', previous):
        continue
    result = subprocess.run(['git', 'show', f'{previous}:app/build.gradle.kts'],
                            capture_output=True, text=True, encoding='utf-8')
    if result.returncode:
        raise SystemExit(f'无法读取历史版本 {previous}，请检查标签与构建文件')
    old_code = re.search(r'versionCode\s*=\s*(\d+)', result.stdout)
    if old_code is None or int(old_code.group(1)) >= code:
        raise SystemExit(f'versionCode 必须大于历史版本 {previous}')
print(f'版本校验通过：{version} ({code})')
