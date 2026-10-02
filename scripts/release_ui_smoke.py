#!/usr/bin/env python3
"""Exercise the installed signed release through TV UI using only ADB remote input."""
import subprocess, pathlib, time, re, xml.etree.ElementTree as E, json
r=pathlib.Path(__file__).resolve().parents[1]
adb=str(r/'tools/android-sdk/platform-tools/adb')
def run(*args):return subprocess.check_output([adb,*args],stderr=subprocess.STDOUT)
def key(code):run('shell','input','keyevent',str(code));time.sleep(.2)
def dump():
    run('shell','uiautomator','dump','/sdcard/ember-window.xml')
    return E.fromstring(run('shell','cat','/sdcard/ember-window.xml'))
def nodes():return list(dump().iter('node'))
def click_node(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.35)
def find_text(text,timeout=25):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        for n in nodes():
            if text in n.attrib.get('text',''):return n
        time.sleep(.3)
    raise AssertionError('UI text missing: '+text)
def shot(name):
    (r/'artifacts'/name).write_bytes(run('exec-out','screencap','-p'))
def activity():
    return run('shell','dumpsys','activity','activities').decode()
run('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
find_text('EMBER TV')
key(4) # Dismiss the TV keyboard; focus the fields explicitly.
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
assert len(fields)==5
for field,value in zip(fields[:3],['http://10.0.2.2:8097/emby','embertest','Ember-Test-Only-2026']):
    click_node(field);run('shell','input','text',value);key(4)
click_node(find_text('连接服务器'))
find_text('Ocean of Light');shot('release-home.png')
click_node(find_text('设置'))
for _ in range(12):
    focused=[n for n in nodes() if n.attrib.get('focused')=='true']
    if any(n.attrib.get('text','').startswith('关于 Ember TV') for n in focused):break
    key(20)
else:raise AssertionError('Remote focus did not reach About')
for _ in range(7):key(23)
key(4)
# Open a TV card from the navigation row using the remote.
key(20)
key(23)
for _ in range(5):
    current=activity()
    if '.ui.DetailActivity' in current.split('mResumedActivity:')[-1].split('\n')[0]:break
    focus=[n for n in nodes() if n.attrib.get('focused')=='true']
    if not focus: key(20)
    key(23);time.sleep(.6)
else:raise AssertionError('D-pad could not reach the movie detail page')
find_text('选择片源');shot('release-detail.png')
click_node(find_text('选择片源并播放'))
find_text('选择视频版本')
versions=[n.attrib.get('text') for n in nodes() if 'Mbps' in n.attrib.get('text','')]
assert len(versions)>=3,versions
shot('release-versions.png')
click_node(find_text('MP4'))
time.sleep(4)
key(82) # Real remote MENU key.
find_text('播放选项')
performance=find_text('显示性能信息')
if '关闭' in performance.attrib.get('text',''):click_node(performance)
else:key(4)
find_text('缓冲内存');shot('release-playback-osd.png')
debug_text=find_text('CDN').attrib.get('text','')
assert 'HTTP' in debug_text and 'Dropped frames' in debug_text and 'api_key=***' in debug_text
shot('release-debug.png')
key(82);find_text('音频轨道');click_node(find_text('音频轨道'));find_text('轨道');shot('release-tracks.png');key(4)
key(82);click_node(find_text('使用外部播放器'));click_node(find_text('Just Player'))
end=time.monotonic()+20
while time.monotonic()<end:
    if 'com.brouken.player' in activity().split('mResumedActivity:')[-1].split('\n')[0]:break
    time.sleep(.2)
else:raise AssertionError('External Just Player did not launch')
shot('release-external-player.png')
print(json.dumps({'release_login':True,'dpad_browse':True,'versions':versions,'osd':True,'debug_osd_and_redaction':True,'remote_menu':True,'tracks_dialog':True,'just_player_handoff':True},ensure_ascii=False))
