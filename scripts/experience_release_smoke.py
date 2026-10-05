#!/usr/bin/env python3
"""Exercise the signed 1.0.3 -> 1.1.0 upgrade and TV playback controls."""
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as E

root=pathlib.Path(__file__).resolve().parents[1]
out=root/'artifacts/experience-1.1.0'
out.mkdir(parents=True,exist_ok=True)
serial='emulator-5554'

def adb(*args):
    return subprocess.check_output(['adb','-s',serial,*args],timeout=40)

def nodes():
    for attempt in range(5):
        adb('shell','rm','-f','/sdcard/ember-experience.xml')
        adb('shell','uiautomator','dump','/sdcard/ember-experience.xml')
        try:
            return list(E.fromstring(adb('exec-out','cat','/sdcard/ember-experience.xml')).iter('node'))
        except E.ParseError:
            time.sleep(.5)
    raise AssertionError('UI automation did not produce a valid hierarchy')

def find(text,timeout=35,scroll=False):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        matches=[n for n in nodes() if text in n.attrib.get('text','')]
        exact=[n for n in matches if text==n.attrib.get('text','')]
        if matches:
            return (exact or matches)[-1]
        if scroll:
            key(20)
        else:
            time.sleep(.2)
    raise AssertionError('Missing UI text: '+text)

def click(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.25)

def key(code):
    adb('shell','input','keyevent',str(code))
    time.sleep(.2)

def focus_to(text,limit=35):
    for _ in range(limit):
        if any(n.attrib.get('focused')=='true' and text in n.attrib.get('text','') for n in nodes()):
            return
        key(20)
    raise AssertionError('D-pad failed to focus '+text)

def capture(name):
    (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    adb('shell','uiautomator','dump','/sdcard/ember-experience.xml')
    (out/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/ember-experience.xml'))

def option(text):
    key(82)
    click(find(text,scroll=True))

urllib.request.urlopen('http://127.0.0.1:8765/fixture/control?expose_original_case=1').close()
for package in ['tv.ember.client.test','tv.ember.client']:
    if 'package:'+package in adb('shell','pm','list','packages',package).decode().splitlines():
        adb('uninstall',package)
adb('install',str(root/'releases/v1.0.3/EmberTV-1.0.3-release.apk'))
adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find('EMBER TV')
key(4)
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
assert len(fields)==5
for field,value in zip(fields[:3],['http://10.0.2.2:8765','demo','demo']):
    click(field);adb('shell','input','text',value);key(4)
click(find('连接服务器'))
find('沙丘 2')
click(find('设置'));click(find('分段接收'));click(find('4 路'))
key(4)
adb('shell','am','force-stop','tv.ember.client')
upgrade=adb('install','-r',str(root/'artifacts/BronyaTV-1.1.0-release.apk')).decode()
assert 'Success' in upgrade
(out/'install-upgrade.log').write_text(upgrade)
adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find('沙丘 2');capture('home')
print('Verified signed upgrade and home',flush=True)
click(find('设置'))
find('分段接收：4 路独立连接');capture('settings-network')
focus_to('预缓冲时间');key(23);click(find('2 秒'));find('预缓冲时间：2 秒')
focus_to('快进 / 快退步长');key(23);click(find('10 秒'));find('快进 / 快退步长：10 秒')
capture('settings-playback')
print('Verified remote settings and saved connection count',flush=True)
key(4)
click(find('沙丘 2'));find('选择喜欢的版本');capture('detail')
click(find('继续播放'));click(find('Original-route recovery test'))
time.sleep(3)
option('显示性能信息')
key(127)
option('播放诊断详情')
diag=find('TCP SO_RCVBUF').attrib['text']
assert all(word in diag for word in ['1.1.0','SoC/硬件','APP 最大堆','回退保留','原始地址','original.mkv','api_key=***','状态 READY','Content-Range bytes','独立 TCP 4 路分段']),diag
assert 'fixture-token' not in diag and 'fixture-session' not in diag
capture('diagnostics')
print('Verified HTTP 410 recovery and hardware diagnostics',flush=True)
key(4)
option('视频轨道')
assert not any(n.attrib.get('text')=='关闭' for n in nodes())
key(4)
option('跳转到指定时间')
field=next(n for n in nodes() if n.attrib.get('class')=='android.widget.EditText')
click(field)
adb('shell','input','keyevent','123',*(['67']*len(field.attrib.get('text',''))))
adb('shell','input','text','00:35');key(4);click(find('跳转'))
time.sleep(2)
option('播放速度');click(find('1.25 倍'))
option('画面比例');click(find('裁切填满'))
option('字幕大小');click(find('140%'))
option('切换片源');click(find('1080P · MP4'))
time.sleep(2)
(out/'playing.png').write_bytes(adb('exec-out','screencap','-p'))
option('播放诊断详情')
changed=find('TCP SO_RCVBUF').attrib['text']
assert '状态 READY' in changed and '片源 MP4' in changed and '独立 TCP 4 路分段' in changed and 'video/avc' in changed
state=json.load(urllib.request.urlopen('http://127.0.0.1:8765/fixture/status'))
started=next(r for r in reversed(state['reports']) if r['event']=='/Sessions/Playing' and r.get('MediaSourceId')=='mp4')
assert started['PositionTicks']>=350_000_000,started
capture('source-switched')
print('Verified time seek, live preferences and source switch',flush=True)
key(4)
for _ in range(6):
    activity=adb('shell','dumpsys','activity','activities').decode()
    if any('.ui.MainActivity' in line for line in activity.splitlines() if 'ResumedActivity' in line):
        break
    key(4)
else:
    raise AssertionError('Back did not return to the home screen')
click(find('设置'))
focus_to('字幕大小');find('字幕大小：140%');capture('settings-preferences')
focus_to('退出登录');key(23);click(find('退出'))
find('连接你的媒体库');capture('login-account')
click(find('Token 登录'));find('Token');capture('login-token')
visible_fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
assert len(visible_fields)==3
assert not any(n.attrib.get('text')=='fixture-token' for n in visible_fields)
result={'upgrade_preserves_login':True,'manual_four_connections_preserved':True,
        'remote_settings_focus_and_scroll':True,'http410_original_recovery':True,
        'video_track_cannot_be_disabled':True,'jump_to_time_and_live_preferences':True,
        'source_switch_keeps_playback':True,'diagnostics_hardware_heap_and_back_buffer':True,
        'query_secrets_redacted':True,'separate_login_modes':True,
        'diagnostics':diag,'switched_diagnostics':changed}
(out/'release-ui-smoke.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if 'diagnostics' not in k},ensure_ascii=False,indent=2))
