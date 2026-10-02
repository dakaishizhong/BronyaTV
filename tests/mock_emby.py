#!/usr/bin/env python3
"""Local Emby contract fixture, with real range video, subtitles, and fault injection."""
import http.server, json, pathlib, re, threading, urllib.parse, time
ROOT = pathlib.Path(__file__).resolve().parent
ASSETS = ROOT/'assets'
LOG = ROOT.parent/'logs/fixture-requests.jsonl'
lock = threading.Lock()
state = {'fail': 0, 'status': 503, 'streams': 0, 'failures': 0, 'reports': [], 'playback': [], 'url_generation': 0, 'subtitle_fail': 0, 'expose_original_case': False}

def sources(item_id='demo'):
    media = [dict(Id='mp4', Name='1080P · MP4', Container='mp4', SupportsDirectPlay=True,
                 Bitrate=1400000, MediaStreams=[dict(Index=0, Type='Video', Codec='h264', Width=640, Height=360, Profile='High', BitDepth=8, AverageFrameRate=25),
                    dict(Index=1, Type='Audio', Codec='aac', Language='eng', DisplayTitle='English AAC'),
                    dict(Index=2, Type='Audio', Codec='aac', Language='zho', DisplayTitle='中文 AAC'),
                    dict(Index=3, Type='Subtitle', Codec='srt', Language='eng', IsExternal=True, DisplayTitle='External SRT', DeliveryUrl='/subtitle.srt')]),
            dict(Id='mkv', Name='MKV · 内嵌 SRT + ASS', Container='mkv', SupportsDirectPlay=True, Bitrate=1400000,
                 MediaStreams=[dict(Index=0, Type='Video', Codec='h264', Width=640, Height=360, Profile='High', BitDepth=8, AverageFrameRate=25),
                    dict(Index=1, Type='Audio', Codec='aac'), dict(Index=3, Type='Subtitle', Codec='subrip'), dict(Index=4, Type='Subtitle', Codec='ass')]),
            dict(Id='hevc', Name='HEVC · HDR10', Container='mkv', SupportsDirectPlay=True, Bitrate=600000,
                 MediaStreams=[dict(Index=0, Type='Video', Codec='hevc', Width=640, Height=360, VideoRange='HDR10', Profile='Main 10', BitDepth=10, AverageFrameRate=24), dict(Index=1, Type='Audio', Codec='aac')])]
    if state['expose_original_case']:
        extra=dict(media[1], Id='originalfallback', Name='Original-route recovery test',
                   DirectStreamUrl='/Videos/demo/stream.mp4?MediaSourceId=originalfallback&Static=true', AddApiKeyToDirectStreamUrl=True)
        media.append(extra)
    return media

def video(i='demo'):
    item=dict(Id=i, Name='Ocean of light' if i=='demo' else 'After the horizon '+i,
                Type='Movie', Overview='A journey through colour, motion and sound. This server fixture exercises Direct Play, version selection, audio and subtitle tracks.',
                ProductionYear=2026, OfficialRating='TV', CommunityRating=8.7, Genres=['Science fiction','Adventure'], BackdropImageTags=['fixture-scene'], People=[dict(Name='Demo Director',Type='Director'),dict(Name='Demo Actor',Type='Actor',Role='Explorer')], RunTimeTicks=900000000, ImageTags={'Primary':'fixture'},
                UserData={'PlaybackPositionTicks':120000000 if i=='demo' else 0}, MediaSources=sources(i))
    if i.startswith('ep'):
        number=int(i[2:])
        item.update(Type='Episode',SeriesId='series',SeasonId='season1' if number<3 else 'season2',
                    ParentIndexNumber=1 if number<3 else 2,IndexNumber=number if number<3 else number-2,
                    Name=['First light','Across the blue','New horizons'][number-1],UserData={'PlaybackPositionTicks':0})
    return item


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, fmt, *args): pass
    def log_request_safe(self, body=None):
        url=urllib.parse.urlsplit(self.path)
        with lock, LOG.open('a') as f:
            f.write(json.dumps({'method':self.command,'path':url.path,'body':body if 'Playing' in url.path or 'PlaybackInfo' in url.path else None})+'\n')
    def respond(self, data, status=200, kind='application/json'):
        content=data if isinstance(data, bytes) else json.dumps(data).encode()
        self.send_response(status); self.send_header('Content-Type',kind); self.send_header('Content-Length',str(len(content))); self.end_headers()
        if self.command != 'HEAD': self.wfile.write(content)
    def do_POST(self):
        path=urllib.parse.urlsplit(self.path).path
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))) or '{}')
        self.log_request_safe(body)
        if path.endswith('/Users/AuthenticateByName'):
            if body.get('Username')=='demo' and body.get('Pw')=='demo': self.respond({'User':{'Id':'u1','Name':'Demo TV'},'AccessToken':'fixture-token'})
            else: self.respond({},401)
        elif path.endswith('/PlaybackInfo'):
            with lock: state['playback'].append(body)
            if body.get('EnableTranscoding') is not False: self.respond({'ErrorCode':'TranscodingForbidden'},400); return
            media=sources(path.split('/')[-2]); selected=body.get('MediaSourceId')
            if selected in ('queryauth','refreshurl','originalfallback','norange'):
                extra=sources()[1 if selected=='originalfallback' else 0]
                extra['Id']=selected
                extra['AddApiKeyToDirectStreamUrl']=True
                generation=0
                if selected=='refreshurl':
                    with lock:
                        state['url_generation']+=1; generation=state['url_generation']
                extra['DirectStreamUrl']=f'/Videos/demo/stream.mp4?MediaSourceId={selected}&generation={generation}&Static=true'
                media=[extra]
            elif selected=='vp8':
                media=[dict(Id='vp8',Name='VP8 emulator fixture',Container='webm',SupportsDirectPlay=True,Bitrate=300000,
                    MediaStreams=[dict(Index=0,Type='Video',Codec='vp8',Width=320,Height=180,AverageFrameRate=12),
                        dict(Index=1,Type='Audio',Codec='vorbis'),
                        dict(Index=2,Type='Subtitle',Codec='srt',IsExternal=True,DeliveryUrl='/subtitle.srt')])]
            elif selected: media=[s for s in media if s['Id']==selected]
            self.respond({'MediaSources':media,'PlaySessionId':'fixture-session'})
        elif '/Sessions/Playing' in path:
            with lock: state['reports'].append(dict(event=path,**body))
            self.respond({},204)
        else: self.respond({},404)
    def do_HEAD(self): self.do_GET()
    def do_GET(self):
        url=urllib.parse.urlsplit(self.path); path=url.path; q=urllib.parse.parse_qs(url.query)
        self.log_request_safe()
        if path=='/fixture/control':
            with lock:
                state['fail']=int(q.get('fail',['0'])[0]); state['status']=int(q.get('status',['503'])[0]); state['url_generation']=0
                state['subtitle_fail']=int(q.get('subtitle_fail',['0'])[0])
                state['expose_original_case']=q.get('expose_original_case',['0'])[0]=='1'
            self.respond({'ok':True}); return
        if path=='/fixture/status':
            with lock: result=json.loads(json.dumps(state))
            self.respond(result); return
        if path.endswith('/Sessions'):
            device=q.get('DeviceId',[''])[0]
            self.respond([{'DeviceId':device,'UserId':'u1','UserName':'Demo TV'}]); return
        if path.endswith('/Users/Me') or path.endswith('/Users/u1'): self.respond({'Id':'u1','Name':'Demo TV'}); return
        if path.endswith('/Shows/series/Episodes'):
            all_items=[video('ep'+str(i)) for i in range(1,4)]
            self.respond({'Items':all_items,'TotalRecordCount':3}); return
        if path.endswith('/Views'): self.respond({'Items':[dict(Id='movies',Name='Cinema',Type='CollectionFolder',ImageTags={'Primary':'fixture'})]}); return
        if path.endswith('/Items/Resume'): self.respond({'Items':[video()]}); return
        if path.endswith('/Items/Latest'): self.respond([video('ep1')]+[video('film'+str(i)) for i in range(7)]); return
        if path.endswith('/Users/u1/Items'):
            start=int(q.get('StartIndex',['0'])[0]); limit=int(q.get('Limit',['40'])[0]); all_items=[video('film'+str(i)) for i in range(45)]
            query=q.get('SearchTerm',[''])[0].lower()
            if query: all_items=[v for v in [video()]+all_items if query in v['Name'].lower()]
            types=q.get('IncludeItemTypes',[''])[0].split(',')
            if types!=['']: all_items=[v for v in all_items if v['Type'] in types]
            if q.get('Filters',[''])[0]=='IsFavorite': all_items=all_items[:3]
            self.respond({'Items':all_items[start:start+limit],'TotalRecordCount':len(all_items)}); return
        if '/Users/u1/Items/' in path: self.respond(video(path.rsplit('/',1)[-1])); return
        if '/Images/Backdrop/' in path: self.respond((ASSETS/'backdrop.jpg').read_bytes(),kind='image/jpeg'); return
        if path.endswith('/Similar'): self.respond({'Items':[video('film'+str(i)) for i in range(6)]}); return
        if '/Images/Primary' in path: self.respond((ASSETS/'poster.jpg').read_bytes(),kind='image/jpeg'); return
        if 'Subtitles' in path or path=='/subtitle.srt':
            with lock: fail_subtitle=state['subtitle_fail']
            if fail_subtitle:self.respond({},fail_subtitle);return
            self.respond((ASSETS/'subtitle.srt').read_bytes(),kind='text/plain'); return
        if '/Videos/' in path and ('/stream.' in path or '/original.' in path):
            source=q.get('MediaSourceId',['mp4'])[0]
            if source in ('queryauth','refreshurl') and q.get('api_key',[''])[0]!='fixture-token':self.respond({},403);return
            if source=='refreshurl' and q.get('generation',['0'])[0]=='1':self.respond({},410);return
            if source=='originalfallback':
                if path.endswith('/stream.mp4'):self.respond({},410);return
                if not path.endswith('/original.mkv') or q.get('Static',[''])[0]!='true' or q.get('api_key',[''])[0]!='fixture-token':self.respond({},400);return
            with lock:
                state['streams']+=1; fail=state['fail']>0
                if fail: state['fail']-=1; state['failures']+=1
                code=state['status']
            if fail: self.respond({},code); return
            file=ASSETS/({'mp4':'sample.mp4','mkv':'sample.mkv','hevc':'hevc.mkv','vp8':'vp8.webm','originalfallback':'sample.mkv'}.get(source,'sample.mp4'))
            if not file.exists(): self.respond({},404); return
            size=file.stat().st_size; start=0; end=size-1
            header=self.headers.get('Range','') if source!='norange' else ''
            if header:
                match=re.fullmatch(r'bytes=(\d+)-(\d*)',header)
                if match: start=int(match[1]); end=min(end,int(match[2])) if match[2] else end
            if start>=size: self.respond({},416); return
            self.send_response(206 if header else 200); self.send_header('Content-Type','video/webm' if source=='vp8' else 'video/x-matroska' if source in ('mkv','hevc','originalfallback') else 'video/mp4')
            self.send_header('ETag',f'"fixture-{file.name}-v1"'); self.send_header('Accept-Ranges','bytes'); self.send_header('Content-Length',str(end-start+1))
            if header: self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
            self.end_headers()
            if self.command=='HEAD': return
            try:
                with file.open('rb') as f:
                    f.seek(start); remaining=end-start+1
                    while remaining:
                        chunk=f.read(min(65536,remaining)); self.wfile.write(chunk); remaining-=len(chunk); time.sleep(.006)
            except (BrokenPipeError,ConnectionResetError): pass
            return
        self.respond({},404)

if __name__=='__main__':
    LOG.parent.mkdir(exist_ok=True)
    server=http.server.ThreadingHTTPServer(('127.0.0.1',8765),Handler)
    print('Fixture listening on port 8765',flush=True); server.serve_forever()
