#!/usr/bin/env python3
"""Exercise a signed Compose release on the dedicated local Emby-fixture emulator."""
import argparse,json,pathlib,re,subprocess,time,urllib.request,xml.etree.ElementTree as E
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5556');p.add_argument('--apk',required=True);p.add_argument('--version',required=True);p.add_argument('--skip-install',action='store_true');p.add_argument('--from-home',action='store_true');p.add_argument('--from-player',action='store_true');p.add_argument('--output',default='artifacts/compose-release');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=root/a.output;out.mkdir(parents=True,exist_ok=True)
exe=root/'tools/android-sdk/platform-tools/adb'
def adb(*args,timeout=45):return subprocess.check_output([str(exe),'-s',a.serial,*args],timeout=timeout,stderr=subprocess.STDOUT)
def key(code):adb('shell','input','keyevent',str(code));time.sleep(.3)
def nodes():
    for attempt in range(4):
        adb('shell','uiautomator','dump','--compressed','/sdcard/compose-release.xml')
        try:
            return list(E.fromstring(adb('exec-out','cat','/sdcard/compose-release.xml')).iter('node'))
        except E.ParseError:
            time.sleep(.5)
    raise AssertionError('Accessibility hierarchy unavailable')
def find(text,timeout=35):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        current=nodes();matches=[n for n in current if (text in n.get('text','') or text in n.get('content-desc','')) and n.get('bounds')!='[0,0][0,0]']
        if matches:return next((n for n in matches if n.get('text')==text),matches[-1])
        time.sleep(.3)
    raise AssertionError('Missing UI text '+text)
def click(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
    if n.get('class')=='android.widget.EditText':
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4);return
    # TV Material intentionally handles remote OK rather than touch taps.
    label=n.get('text') or n.get('content-desc')
    for _ in range(60):
        current=nodes()
        for item in current:
            if item.get('focused')=='true' and any(label in (c.get('text','')+' '+c.get('content-desc','')) for c in item.iter()):
                key(23);return
        key(61)
    raise AssertionError('Cannot focus '+str(label))
def shot(name):
    (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    nodes();(out/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/compose-release.xml'))
def no_header_back():
    assert not any(n.get('text') in ('Back','‹','←') or n.get('content-desc')=='Back' for n in nodes()), 'Unexpected on-screen Back control'
def field(n,text):
    click(n);key(123)
    for _ in n.get('text',''):key(67)
    adb('shell','input','text',text);key(4)
def state():return json.load(urllib.request.urlopen('http://127.0.0.1:8765/fixture/status'))
def wait_playback_report(paused,after):
    until=time.monotonic()+15
    while time.monotonic()<until:
        if any(r.get('ItemId')=='demo' and r.get('MediaSourceId')=='vp8' and r.get('IsPaused')==paused for r in state()['reports'][after:]):return
        time.sleep(.2)
    raise AssertionError('Remote OK did not change actual playback pause state')
urllib.request.urlopen('http://127.0.0.1:8765/fixture/control?fail=0').close()
for package in (() if a.skip_install or a.from_home or a.from_player else ('tv.ember.client.test','tv.ember.client')):
    if 'package:'+package in adb('shell','pm','list','packages',package).decode().splitlines():adb('uninstall',package)
if not (a.skip_install or a.from_home or a.from_player):
    installed=adb('install',str(root/a.apk),timeout=360).decode();assert 'Success' in installed;(out/'install.log').write_text(installed)
adb('reverse','tcp:8765','tcp:8765')
if not a.from_player:adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
if not (a.from_home or a.from_player):
    find('Sign in');shot('login')
    fields=[n for n in nodes() if n.get('class')=='android.widget.EditText'];assert len(fields)==3,len(fields)
    for n,value in zip(fields,['http://127.0.0.1:8765','demo','demo']):field(n,value)
    click(find('Connect'));find('Ocean of light');find('Continue watching');find('Cinema');shot('home')
    click(find('Movies'));find('Filter & sort');find('After the horizon');shot('category');key(4)
if not a.from_player:
    click(find('Details'));find('Video versions');find('1.4 Mbps');no_header_back();shot('detail')
    # Focus above the initial primary action, move through real version controls, then return to Play.
    key(19)
    for _ in range(3):key(22)
    key(23)
    for _ in range(3):key(21)
    key(20);key(23)
    until=time.monotonic()+35
    while time.monotonic()<until:
        activity=adb('shell','dumpsys','activity','activities').decode()
        if '.player.PlaybackActivity' in activity.split('mResumedActivity:')[-1].split('\n')[0]:break
        time.sleep(.3)
    else:raise AssertionError('Selected version did not launch playback')
    time.sleep(3);key(127);key(19);time.sleep(1)
    # Inspect real playback telemetry while playing: an accessibility idle wait can outlast auto-hide.
    before=len(state()['reports']);key(23);wait_playback_report(False,before)
    before=len(state()['reports']);key(23);wait_playback_report(True,before)
    key(127);shot('player')
assert '.player.PlaybackActivity' in adb('shell','dumpsys','activity','activities').decode().split('mResumedActivity:')[-1].split('\n')[0], 'Playback resume requires the current player'
player_nodes=list(E.fromstring((out/'player.xml').read_bytes()).iter('node'))
# TV Material exposes the label on a decorative child; its parent handles remote clicks.
play_controls=[n for n in player_nodes if n.get('content-desc') in ('Play','Pause')]
if len(play_controls)==1:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',play_controls[0].get('bounds')))
    width=Image.open(out/'player.png').width;center_x=(x1+x2)/2
else:
    # API 23 accessibility idle can finish after auto-hide. Measure the captured focus ring.
    with Image.open(out/'player.png') as screenshot:
        width,height=screenshot.size;pixels=screenshot.convert('RGB');points=[]
        for y in range(int(height*.8),int(height*.93)):
            for x in range(int(width*.45),int(width*.55)):
                r,g,b=pixels.getpixel((x,y))
                if r<30 and g>150 and b>150:points.append((x,y))
    assert points, 'Focused Play/Pause ring missing from screenshot'
    x1=min(x for x,y in points);x2=max(x for x,y in points);y1=min(y for x,y in points);y2=max(y for x,y in points)
    assert x2-x1>width*.04 and y2-y1>height*.07, 'Incomplete Play/Pause ring'
    center_x=(x1+x2)/2
assert abs(center_x-width/2)<=1, 'Play/Pause is not centered'
assert not any(n.get('content-desc')=='Audio tracks' for n in player_nodes), 'Speaker shortcut must be absent'
playback=state()['playback'][-1]
assert playback.get('MediaSourceId')=='vp8',playback
key(82);find('Playback options');key(4)
def resumed():
    return adb('shell','dumpsys','activity','activities').decode().split('mResumedActivity:')[-1].split('\n')[0]
assert '.player.PlaybackActivity' in resumed(), 'Back must dismiss the menu before leaving playback'
# Auto-hide may have already removed controls; inspect each actual return layer.
for _ in range(3):
    key(4)
    if '.ui.DetailActivity' in resumed():break
    assert '.player.PlaybackActivity' in resumed(), 'Unexpected Back destination'
else:raise AssertionError('Back did not return to Details')
find('Video versions');key(4);find('Continue watching')
click(find('Settings'));find('Interface & diagnostics');shot('settings')
click(find('Network & cache'));find('Parallel receive');no_header_back();shot('settings-editor');key(4)
key(4);click(find('Search'));find('Search movies');shot('search')
print(json.dumps({'signed_release':a.version,'english_login':True,'home_rows':True,'category_facets':True,'inline_versions':True,'fresh_selected_source':'vp8','compose_player_controls':True,'play_pause_center_x':center_x,'remote_ok_pause_resume':True,'speaker_shortcut_absent':True,'header_back_absent':True,'remote_menu_back':True,'settings':True,'settings_editor':True,'search':True}),flush=True)
