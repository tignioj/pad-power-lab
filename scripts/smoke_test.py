#!/usr/bin/env python3
"""Real-device checks. Requires an installed app and an explicitly selected ADB device."""
import csv
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
ADB = str(ROOT / '.tools/platform-tools/adb')
DEVICE = sys.argv[1] if len(sys.argv) > 1 else None
if not DEVICE:
    sys.exit('Usage: python3 scripts/smoke_test.py DEVICE_SERIAL')
BASE = [ADB, '-s', DEVICE]
OUT = ROOT / 'captures'
OUT.mkdir(exist_ok=True)
results = {}
controls = None

def adb(*args):
    return subprocess.check_output(BASE + list(args), timeout=25).decode('utf-8')

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/powerlab-test-ui.xml')
    raw = adb('shell', 'cat', '/sdcard/powerlab-test-ui.xml')
    return list(ET.fromstring(raw).iter('node'))

def tap(label):
    # Cache static controls before starting: continuously updated telemetry prevents
    # the platform's uiautomator dump command from reaching its idle timeout.
    node = next(n for n in controls if n.get('text') == label)
    a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((a + c) // 2), str((b + d) // 2))

def threads():
    pid = adb('shell', 'pidof', 'com.padpowerlab').strip()
    return adb('shell', 'ps', '-T', '-p', pid, '-o', 'TID,COMM')

def screenshot(name):
    (OUT / name).write_bytes(subprocess.check_output(BASE + ['exec-out', 'screencap', '-p'], timeout=25))

def foreground():
    adb('shell', 'am', 'start', '-W', '-n', 'com.padpowerlab/.MainActivity')
    # HyperOS finishes the Home-to-landscape animation after am reports ready.
    time.sleep(1.2)

def session():
    name = adb('shell', 'run-as', 'com.padpowerlab', 'ls', '-t', 'files/sessions').splitlines()[0]
    raw = adb('shell', 'run-as', 'com.padpowerlab', 'cat', 'files/sessions/' + name)
    return list(csv.DictReader(io.StringIO(raw)))

thermal_override = False
try:
    foreground()
    controls = ui()
    tap('游戏负载')
    tap('开始负载测试')
    time.sleep(6)
    assert threads().count('PowerLab-CPU-') == 4
    samples = session()
    fps = float(samples[-1]['render_fps'])
    assert fps > 0
    results['game_load'] = {'cpu_workers': 4, 'render_fps': fps}
    results['game_threads'] = threads()
    screenshot('game-load.png')

    thermal_override = True
    response = adb('shell', 'cmd', 'thermalservice', 'override-status', '3')
    assert 'Error' not in response, response
    time.sleep(1)
    assert '系统报告严重限频' in session()[-1]['event']
    assert not 'PowerLab-CPU-' in threads()
    results['thermal_stop'] = 'PASS: severe status stops all CPU workers'
    adb('shell', 'cmd', 'thermalservice', 'reset')
    thermal_override = False

    tap('开始负载测试')
    time.sleep(3)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    assert not 'PowerLab-CPU-' in threads()
    foreground()
    assert '应用离开前台，已自动停止' in session()[-1]['event']
    results['background_stop'] = 'PASS: Home stops all CPU workers; no automatic restart'

    tap('导出本次 CSV')
    time.sleep(1)
    exported = adb('shell', 'ls', '-t', '/sdcard/Download/PowerLab/').splitlines()[0]
    raw = adb('shell', 'cat', '/sdcard/Download/PowerLab/' + exported)
    rows = list(csv.DictReader(io.StringIO(raw)))
    assert len(rows) >= 2
    assert all(len(row) == 28 and None not in row and all(v is not None for v in row.values()) for row in rows)
    assert any(row['event'].startswith('stop:') for row in rows)
    assert all(row['current_sign_multiplier'] in {'1','-1'} for row in rows)
    (OUT / exported).write_text(raw, encoding='utf-8')
    results['csv_export'] = {'file': exported, 'rows': len(rows), 'columns': 28}

    tap('极限负载')
    tap('开始负载测试')
    time.sleep(8)
    assert threads().count('PowerLab-CPU-') == 8
    samples = session()
    fps = float(samples[-1]['render_fps'])
    assert fps > 0
    assert samples[-1]['gpu_width'] == '3200' and samples[-1]['gpu_height'] == '2136'
    results['maximum_load'] = {'cpu_workers': 8, 'render_fps': fps, 'resolution': '3200x2136'}
    screenshot('maximum-load.png')
    tap('开始负载测试')
    time.sleep(.5)
    assert not 'PowerLab-CPU-' in threads()
    results['manual_stop'] = 'PASS: no CPU workers remain'

    tap('游戏负载')
    tap('电池净功率 / W   ⓘ 电流方向')
    nodes = ui()
    assert any(n.get('text') == 'Android 标准：原始正值表示充入' for n in nodes)
    assert any(n.get('text') == '反转符号：原始负值表示充入' for n in nodes)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    results['current_direction_dialog'] = 'PASS: both polarity choices visible'
    screenshot('ready.png')
    crash = adb('logcat', '-d', '-s', 'AndroidRuntime:E', 'PowerLab:E')
    assert not 'FATAL EXCEPTION' in crash and not 'GPU 运行失败' in crash and not 'GPU 初始化失败' in crash, crash
    results['runtime_errors'] = 'none'
finally:
    if thermal_override:
        adb('shell', 'cmd', 'thermalservice', 'reset')
    # Always remove stress if an assertion or connection interruption occurs.
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    foreground()
    (OUT / 'smoke-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')

print(json.dumps(results, ensure_ascii=False, indent=2))
