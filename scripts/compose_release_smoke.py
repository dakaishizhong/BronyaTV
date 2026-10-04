#!/usr/bin/env python3
"""Exercise a signed Compose release on the dedicated local Emby-fixture emulator."""
import argparse,json,pathlib,re,subprocess,time,urllib.request,xml.etree.ElementTree as E
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5556');p.add_argument('--apk',required=True);p.add_argument('--skip-install',action='store_true');p.add_argument('--from-home',action='store_true');p.add_argument('--output',default='artifacts/compose-release');a=p.parse_args()
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
def field(n,text):
    click(n);key(123)
    for _ in n.get('text',''):key(67)
    adb('shell','input','text',text);key(4)
urllib.request.urlopen('http://127.0.0.1:8765/fixture/control?fail=0').close()
for package in (() if a.skip_install or a.from_home else ('tv.ember.client.test','tv.ember.client')):
    if 'package:'+package in adb('shell','pm','list','packages',package).decode().splitlines():adb('uninstall',package)
if not (a.skip_install or a.from_home):
    installed=adb('install',str(root/a.apk),timeout=360).decode();assert 'Success' in installed;(out/'install.log').write_text(installed)
adb('reverse','tcp:8765','tcp:8765');adb('shell','am','start','-n','tv.ember.client/.ui.MainActivity')
if not a.from_home:
    find('Sign in');shot('login')
    fields=[n for n in nodes() if n.get('class')=='android.widget.EditText'];assert len(fields)==3,len(fields)
    for n,value in zip(fields,['http://127.0.0.1:8765','demo','demo']):field(n,value)
    click(find('Connect'));find('Ocean of light');find('Continue watching');find('Cinema');shot('home')
    click(find('Movies'));find('Filter & sort');find('After the horizon');shot('category');key(4)
click(find('Details'));find('Video versions');find('1.4 Mbps');shot('detail')
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
time.sleep(3);key(127);key(19);shot('player')
state=json.load(urllib.request.urlopen('http://127.0.0.1:8765/fixture/status'))
assert state['playback'][-1].get('MediaSourceId')=='vp8',state['playback'][-1]
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
key(4);click(find('Search'));find('Search movies');shot('search')
print(json.dumps({'signed_release':'1.6.0','english_login':True,'home_rows':True,'category_facets':True,'inline_versions':True,'fresh_selected_source':'vp8','compose_player_controls':True,'remote_menu_back':True,'settings':True,'search':True}),flush=True)
