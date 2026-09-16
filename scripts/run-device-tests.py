"""安装并执行全部 instrumentation；解析测试结论，不能仅依赖 adb 退出码。"""
import argparse
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('仅允许专用模拟器 emulator-*')
sdk = Path(os.environ.get('ANDROID_HOME', '.toolchains/android-sdk'))
adb = sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')

def run(*arguments):
    return subprocess.run([str(adb), '-s', args.serial, *arguments], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding='utf-8', errors='replace', timeout=900).stdout

run('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
run('install', '-r', '-t', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
output = run('shell', 'am', 'instrument', '-w', '-r',
             'dev.spcdts.volumemapper.debug.test/androidx.test.runner.AndroidJUnitRunner')
report = Path('artifacts/ci')
report.mkdir(parents=True, exist_ok=True)
(report / 'instrumentation.txt').write_text(output, encoding='utf-8')
print(output)
success = re.search(r'OK \((\d+) tests?\)', output)
if success is None or int(success.group(1)) == 0 or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', output):
    raise SystemExit('设备测试未通过；参见 artifacts/ci/instrumentation.txt')
# 跳过数明确打印，避免将缺少系统能力误报为覆盖通过。
skipped = len(re.findall(r'INSTRUMENTATION_STATUS_CODE: -[34]', output))
print(f'设备测试报告：skip={skipped}')
if skipped:
    raise SystemExit('CI 模拟器存在跳过的测试，请检查镜像能力与测试前置条件')
