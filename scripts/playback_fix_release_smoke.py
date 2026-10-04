#!/usr/bin/env python3
"""Exercise the signed APK and its 410 recovery through the actual TV UI."""
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as E

root = pathlib.Path(__file__).resolve().parents[1]
out = root / 'artifacts/playback-fix-1.0.2'
out.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=15)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ember-playback-fix.xml')
    xml = adb('exec-out', 'cat', '/sdcard/ember-playback-fix.xml')
    return list(E.fromstring(xml).iter('node'))

def find(text, timeout=35):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for n in nodes():
            if text in n.attrib.get('text', ''):
                return n
        time.sleep(.2)
    raise AssertionError('Missing UI text: ' + text)

def click(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.2)

def key(code):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(.2)

def capture(name):
    (out/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    adb('shell', 'uiautomator', 'dump', '/sdcard/ember-playback-fix.xml')
    (out/(name+'.xml')).write_bytes(adb('exec-out', 'cat', '/sdcard/ember-playback-fix.xml'))

urllib.request.urlopen('http://127.0.0.1:8765/fixture/control?expose_original_case=1').close()
adb('shell', 'am', 'start', '-n', 'tv.ember.client/.ui.MainActivity')
find('EMBER TV')
fields = [n for n in nodes() if n.attrib.get('class') == 'android.widget.EditText']
if fields:
    assert len(fields) == 5
    key(4)
    for field, value in zip(fields[:3], ['http://10.0.2.2:8765', 'demo', 'demo']):
        click(field)
        adb('shell', 'input', 'text', value)
        key(4)
    click(find('连接服务器'))
find('A Trip to the Moon')
click(find('设置'))
click(find('网络接收缓冲'))
click(find('1024KB'))
find('网络接收缓冲：1024KB')
for _ in range(15):
    focused = [n for n in nodes() if n.attrib.get('focused') == 'true']
    if any(n.attrib.get('text', '').startswith('关于 Ember TV') for n in focused):
        break
    key(20)
else:
    raise AssertionError('D-pad failed to reach About')
for _ in range(7):
    key(23)
key(4)
key(20)
key(23)
for _ in range(5):
    activity=adb('shell','dumpsys','activity','activities').decode()
    if any('.ui.DetailActivity' in line for line in activity.splitlines() if 'ResumedActivity' in line):
        break
    key(20)
    key(23)
else:
    raise AssertionError('D-pad failed to open the movie detail')
key(23)  # The detail page focuses its play/continue button.
click(find('Original-route recovery test'))
time.sleep(3)
key(82)
performance = find('显示性能信息')
if '关闭' in performance.attrib['text']:
    click(performance)
else:
    key(4)
osd = find('TCP 接收').attrib['text']
assert '请求 1024KB' in osd and '等待连接' not in osd, osd
debug = find('最终地址').attrib['text']
assert 'original.mkv' in debug and '状态 READY' in debug and 'api_key=***' in debug, debug
capture('release-original-recovery')
key(4)
key(4)
click(find('设置'))
find('网络接收缓冲：1024KB')
capture('release-receive-buffer-setting')
result = {'signed_release_login': True, 'version_selected': 'originalfallback',
          'rejected_stream_mp4_recovers_to_original_mkv': True, 'player_ready': True,
          'tcp_receive_request_kb': 1024, 'receive_buffer_osd': osd,
          'settings_persist_on_return': True, 'sensitive_query_redacted': True}
(out/'release-ui-smoke.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(result, ensure_ascii=False, indent=2))
