#!/usr/bin/env python3
"""Verify signed 1.0.2 -> 1.0.3 upgrade, four TCP streams, 410 recovery and diagnostics via TV UI."""
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as E

root=pathlib.Path(__file__).resolve().parents[1]
out=root/'artifacts/network-fix-1.0.3'
out.mkdir(parents=True,exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', '-s', 'emulator-5556', *args],timeout=30)

def nodes():
    adb('shell','uiautomator','dump','/sdcard/ember-network.xml')
    return list(E.fromstring(adb('exec-out','cat','/sdcard/ember-network.xml')).iter('node'))

def find(text,timeout=35):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        for n in nodes():
            if text in n.attrib.get('text',''):
                return n
        time.sleep(.2)
    raise AssertionError('Missing UI text: '+text)

def click(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.2)

def key(code):
    adb('shell','input','keyevent',str(code));time.sleep(.2)

def focus_to(text,limit=25):
    for _ in range(limit):
        if any(n.attrib.get('focused')=='true' and text in n.attrib.get('text','') for n in nodes()):
            return
        key(20)
    raise AssertionError('D-pad failed to focus '+text)

def capture(name):
    (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    adb('shell','uiautomator','dump','/sdcard/ember-network.xml')
    (out/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/ember-network.xml'))

urllib.request.urlopen('http://127.0.0.1:8765/fixture/control?expose_original_case=1').close()
for package in ['tv.ember.client.test','tv.ember.client']:
    installed=adb('shell','pm','list','packages',package).decode().splitlines()
    if 'package:'+package in installed:
        adb('uninstall',package)
adb('install',str(root/'artifacts/EmberTV-1.0.2-release.apk'))
adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find('EMBER TV')
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
assert len(fields)==5
key(4)
for field,value in zip(fields[:3],['http://10.0.2.2:8765','demo','demo']):
    click(field);adb('shell','input','text',value);key(4)
click(find('连接服务器'));find('A Trip to the Moon');click(find('设置'))
click(find('网络接收缓冲'));click(find('4096KB'));find('网络接收缓冲：4096KB')
key(4);adb('shell','am','force-stop','tv.ember.client')
upgrade=adb('install','-r',str(root/'artifacts/EmberTV-1.0.3-release.apk')).decode()
assert 'Success' in upgrade,upgrade
(out/'install-upgrade.log').write_text(upgrade)
adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find('A Trip to the Moon');click(find('设置'));find('网络接收缓冲：系统自动')
focus_to('分段接收');key(23);click(find('4 路'));focus_to('分段接收');find('分段接收：4 路独立连接')
capture('release-parallel-setting');key(4)
key(20);key(23)
for _ in range(6):
    activity=adb('shell','dumpsys','activity','activities').decode()
    if any('.ui.DetailActivity' in line for line in activity.splitlines() if 'ResumedActivity' in line):
        break
    key(20);key(23)
else:
    raise AssertionError('D-pad failed to open detail')
key(23);click(find('Original-route recovery test'));time.sleep(3)
key(82);performance=find('显示性能信息')
if '关闭' in performance.attrib['text']:
    click(performance)
else:
    key(4)
osd=find('独立 TCP 4 路分段').attrib['text']
assert '片源 MKV' in osd and '解码输入 video/avc' in osd,osd
capture('release-parallel-playing')
key(82);click(find('播放诊断详情'));diag=find('TCP SO_RCVBUF').attrib['text']
assert all(word in diag for word in ['Profile High','原始地址','original.mkv','api_key=***','状态 READY','Content-Range bytes','同时连接峰值','tcp_rmem','实际视频解码器','音频欠载']),diag
assert 'fixture-token' not in diag and 'fixture-session' not in diag,diag
capture('release-diagnostics');click(find('复制'));click(find('关闭'))
# Android TV remote must be able to scroll through long diagnostics.
time.sleep(.8)
key(82)
try:
    detail=find('播放诊断详情',timeout=5)
except AssertionError:
    key(82);detail=find('播放诊断详情')
click(detail);key(19)
for _ in range(13):
    key(20)
capture('release-diagnostics-scrolled');key(4)
key(4);key(4);click(find('设置'));focus_to('分段接收');find('分段接收：4 路独立连接')
result={'upgrade_preserves_login':True,'old_manual_receive_reset_to_auto_once':True,'independent_connections':4,
        'selected_source':'originalfallback','http410_recovers_to_original_mkv':True,'parallel_ready':True,
        'settings_persist':True,'diagnostics_source_codec_http_window':True,'sensitive_query_redacted':True,
        'receive_buffer_osd':osd,'diagnostics':diag}
(out/'release-ui-smoke.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
