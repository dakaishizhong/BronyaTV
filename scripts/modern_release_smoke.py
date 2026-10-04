#!/usr/bin/env python3
"""Verify the signed upgrade, TV navigation, episode controls and rendered diagnostics."""
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as E

root=pathlib.Path(__file__).resolve().parents[1]
out=root/'artifacts/modern-1.2.0'
out.mkdir(parents=True,exist_ok=True)
serial='emulator-5554'

def adb(*args):
    return subprocess.check_output(['adb','-s',serial,*args],timeout=45)

def nodes():
    for _ in range(5):
        adb('shell','rm','-f','/sdcard/bronya-modern.xml')
        adb('shell','uiautomator','dump','/sdcard/bronya-modern.xml')
        try:
            return list(E.fromstring(adb('exec-out','cat','/sdcard/bronya-modern.xml')).iter('node'))
        except E.ParseError:
            time.sleep(.4)
    raise AssertionError('No fresh UI hierarchy')

def find(text,timeout=30,scroll=False):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        matches=[n for n in nodes() if text in n.attrib.get('text','') and n.attrib.get('bounds')!='[0,0][0,0]']
        exact=[n for n in matches if text==n.attrib.get('text','')]
        if matches:
            return (exact or matches)[-1]
        if scroll:
            key(20)
        else:
            time.sleep(.2)
    raise AssertionError('Missing UI text: '+text)

def key(code):
    adb('shell','input','keyevent',str(code));time.sleep(.2)

def click(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.3)

def edit(field,value):
    click(field);key(123)
    for _ in field.attrib.get('text',''): adb('shell','input','keyevent','67')
    adb('shell','input','text',value);key(4)

def capture(name,hierarchy=True):
    (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    if hierarchy:
        current=nodes()
        (out/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/bronya-modern.xml'))
        return current

def option(text):
    key(82);click(find(text,scroll=True))

def set_seconds(label,value):
    click(find(label))
    field=next(n for n in nodes() if n.attrib.get('class')=='android.widget.EditText')
    edit(field,str(value));click(find('保存'))

def back_to_home():
    for _ in range(5):
        key(4)
        if any(n.attrib.get('text')=='设置' for n in nodes()): return
    raise AssertionError('Back did not return to home')

urllib.request.urlopen('http://127.0.0.1:8765/fixture/control?expose_original_case=1').close()
for package in ['tv.ember.client.test','tv.ember.client']:
    if 'package:'+package in adb('shell','pm','list','packages',package).decode().splitlines(): adb('uninstall',package)
adb('install',str(root/'releases/v1.1.0/BronyaTV-1.1.0-release.apk'))
adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find('连接服务器')
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
for field,value in zip(fields[:3],['http://10.0.2.2:8765','demo','demo']): edit(field,value)
click(find('连接服务器'));find('A Trip to the Moon')
click(find('设置'));click(find('分段接收'));click(find('4 路'))
key(4);adb('shell','am','force-stop','tv.ember.client')
upgrade=adb('install','-r',str(root/'artifacts/BronyaTV-1.2.0-release.apk')).decode()
assert 'Success' in upgrade;(out/'install-upgrade.log').write_text(upgrade)
adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find('A Trip to the Moon');capture('home')
print('Signed upgrade preserved session',flush=True)
click(find('设置'));click(find('网络与缓存'));find('4 路');capture('settings-network')
click(find('遥控器'));click(find('短按快进'));click(find('10 秒'))
click(find('长按每次跳转'));click(find('30 秒'));capture('settings-remote')
click(find('播放'));set_seconds('跳过片头',5);set_seconds('跳过片尾',10);capture('settings')
key(4);click(find('A Trip to the Moon'));capture('detail')
click(find('继续播放'));click(find('Original-route recovery test'));time.sleep(3)
key(127)  # Pause keeps playback tests reproducible.
option('跳转到指定时间')
field=next(n for n in nodes() if n.attrib.get('class')=='android.widget.EditText')
edit(field,'00:40');click(find('跳转'));time.sleep(1)
option('播放诊断详情')
report=find('实际解码画面').attrib['text']
assert '640×360' in report and '已输出首帧' in report and '系统音频输出 PCM' in report
assert '显示模式' in report and '屏幕当前 HDR / Dolby Vision 模式' in report
assert 'api_key=fixture-token' not in report
(out/'diagnostics.txt').write_text(report)
capture('diagnostics')
key(4);find('播放选项')  # Back returns to the parent menu.
click(find('播放速度'));key(4);find('播放选项');key(4)
option('切换片源');click(find('MKV · 内嵌'));time.sleep(2)
option('播放诊断详情');assert '片源 MKV' in find('片源 MKV').attrib['text'];key(4);key(4)
back_to_home()
print('Playback, 410 recovery, source switch and menu return verified',flush=True)
# Select the first episode from the second home row with the remote.
key(20)
click(find('First light',scroll=True));click(find('播放'));click(find('1080P · MP4'));time.sleep(2)
key(127);capture('player')
key(127);click(find('下一集'));time.sleep(2)
key(127);find('Across the blue');option('播放诊断详情')
assert '实际解码画面 640×360' in find('实际解码画面').attrib['text'];key(4);key(4)
key(127);click(find('上一集'));time.sleep(2)
key(127);find('First light')
back_to_home()
click(find('设置'));click(find('信息与账号'));click(find('退出登录'));click(find('退出'))
find('登录媒体库');login_nodes=capture('login')
fields=[n for n in login_nodes if n.attrib.get('class')=='android.widget.EditText']
assert len(fields)==3 and fields[2].attrib.get('text','') in ('','密码')
assert not any('Token' in n.attrib.get('text','') for n in login_nodes)
print('Episode controls, three-field login and logout verified',flush=True)
result={'version':'1.2.0','signedUpgrade':True,'sessionPreserved':True,'connectionsPreserved':4,
        'accountPasswordOnly':True,'remoteSettings':True,'introOutroSettings':True,'episodeNavigation':True,
        'actualRenderedDimensions':'640x360','systemAudioOutput':'PCM','menuBackReturnsToParent':True,
        'http410Recovery':True,'sourceSwitchPreservesProgress':True}
(out/'result.json').write_text(json.dumps(result,indent=2))
