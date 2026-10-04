#!/usr/bin/env python3
"""Local Emby contract fixture, with real range video, subtitles, and fault injection."""
import datetime
import http.server, json, pathlib, re, threading, urllib.parse, time
ROOT = pathlib.Path(__file__).resolve().parent
ASSETS = ROOT/'assets'
LOG = ROOT.parent/'logs/fixture-requests.jsonl'
FILMS = json.loads((ROOT/'media/SOURCES.json').read_text())
def movie_art(item_id):
    index=int(item_id[4:])%5+1 if item_id.startswith('film') else 0
    return FILMS[index]
lock = threading.Lock()
state = {'fail': 0, 'status': 503, 'streams': 0, 'failures': 0, 'reports': [], 'playback': [], 'url_generation': 0, 'subtitle_fail': 0, 'expose_original_case': False, 'missing_source': '', 'view_type': 'CollectionFolder', 'searches': [], 'slow_search': '', 'facet_requests': 0, 'view_requests': 0, 'small_catalog': False}

def sources(item_id='demo'):
    media = [dict(Id='mp4', Name='H.264 MP4', Container='mp4', SupportsDirectPlay=True,
                 Bitrate=1400000, MediaStreams=[dict(Index=0, Type='Video', Codec='h264', Width=640, Height=360, Profile='High', BitDepth=8, AverageFrameRate=25),
                    dict(Index=1, Type='Audio', Codec='aac', Language='eng', DisplayTitle='English AAC'),
                    dict(Index=2, Type='Audio', Codec='aac', Language='zho', DisplayTitle='中文 AAC'),
                    dict(Index=3, Type='Subtitle', Codec='srt', Language='eng', IsExternal=True, DisplayTitle='External SRT', DeliveryUrl='/subtitle.srt')]),
            dict(Id='mkv', Name='MKV · embedded SRT + ASS', Container='mkv', SupportsDirectPlay=True, Bitrate=1400000,
                 MediaStreams=[dict(Index=0, Type='Video', Codec='h264', Width=640, Height=360, Profile='High', BitDepth=8, AverageFrameRate=25),
                    dict(Index=1, Type='Audio', Codec='aac'), dict(Index=3, Type='Subtitle', Codec='subrip'), dict(Index=4, Type='Subtitle', Codec='ass')]),
            dict(Id='hevc', Name='HEVC · HDR10', Container='mkv', SupportsDirectPlay=True, Bitrate=600000,
                 MediaStreams=[dict(Index=0, Type='Video', Codec='hevc', Width=640, Height=360, VideoRange='HDR10', Profile='Main 10', BitDepth=10, AverageFrameRate=24), dict(Index=1, Type='Audio', Codec='aac')])]
    media.append(dict(Id='vp8',Name='VP8 emulator fixture',Container='webm',SupportsDirectPlay=True,Bitrate=300000,
        MediaStreams=[dict(Index=0,Type='Video',Codec='vp8',Width=320,Height=180,AverageFrameRate=12),
                      dict(Index=1,Type='Audio',Codec='vorbis'),dict(Index=2,Type='Subtitle',Codec='srt',IsExternal=True,DeliveryUrl='/subtitle.srt')]))
    if state['expose_original_case']:
        extra=dict(media[1], Id='originalfallback', Name='Original-route recovery test',
                   DirectStreamUrl='/Videos/demo/stream.mp4?MediaSourceId=originalfallback&Static=true', AddApiKeyToDirectStreamUrl=True)
        media.append(extra)
    return media

def video(i='demo'):
    film=movie_art(i)
    item=dict(Id=i, Name=film['title'], SortName=film['title'].lower() if state['small_catalog'] else i[4:].zfill(3) if i.startswith('film') else film['title'],
                Type='Movie', Overview=film['summary'],
                ProductionYear=film['year'], Genres=film['genres'], BackdropImageTags=['public-domain-films-v1'], People=[dict(Name=film['credit'],Type='Director')], RunTimeTicks=900000000, ImageTags={'Primary':'public-domain-films-v1'},
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
            f.write(json.dumps({'method':self.command,'path':url.path,'body':body if 'Playing' in url.path or 'PlaybackInfo' in url.path else None,'query':{k:v for k,v in urllib.parse.parse_qs(url.query).items() if k in ('ParentId','IncludeItemTypes','StartIndex','Limit','Genres','Years','IsPlayed','Filters','SortBy','SortOrder','Recursive','SearchTerm')}})+'\n')
    def respond(self, data, status=200, kind='application/json'):
        content=data if isinstance(data, bytes) else json.dumps(data).encode()
        if status in (204,304):content=b''
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
            if selected and selected==state['missing_source']: media=[]
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
                state['missing_source']=q.get('missing_source',[''])[0]
                state['view_type']=q.get('view_type',['CollectionFolder'])[0]
                state['small_catalog']=q.get('small_catalog',['0'])[0]=='1'
                state['slow_search']=q.get('slow_search',[''])[0]
                if q.get('reset_search',['0'])[0]=='1': state['searches']=[]
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
        if path.endswith('/Views'):
            with lock: state['view_requests']+=1
            self.respond({'Items':[dict(Id='movies',Name='Cinema',Type=state['view_type'],CollectionType='movies',ImageTags={'Primary':'fixture'}),dict(Id='tv',Name='Television',Type=state['view_type'],CollectionType='tvshows',ImageTags={'Primary':'fixture'})]}); return
        if path.endswith('/Items/Resume'): self.respond({'Items':[video()]}); return
        if path.endswith('/Items/Latest'):
            parent=q.get('ParentId',[''])[0]
            items=[video('ep'+str(i)) for i in range(1,4)] if parent=='tv' else [video('film'+str(i)) for i in range(5 if state['small_catalog'] else 8)] if parent=='movies' else [video('ep1')]+[video('film'+str(i)) for i in range(7)]
            self.respond(items); return
        if path.endswith('/Genres') or path.endswith('/Years'):
            with lock: state['facet_requests']+=1
            names=(sorted({g for film in FILMS for g in film['genres']}) if state['small_catalog'] else ['Science fiction','Fantasy','Adventure']) if path.endswith('/Genres') else sorted({str(film['year']) for film in FILMS})
            self.respond({'Items':[dict(Id='facet-'+str(i),Name=name) for i,name in enumerate(names)],'TotalRecordCount':len(names)}); return
        if path.endswith('/Users/u1/Items'):
            start=int(q.get('StartIndex',['0'])[0]); limit=int(q.get('Limit',['40'])[0])
            all_items=[]
            for i in range(5 if state['small_catalog'] else 45):
                item=video('film'+str(i))
                if not state['small_catalog']: item['Genres']=['Science fiction','Adventure'] if i%2==0 else ['Fantasy']
                item['UserData']['Played']=i%2==1;item['DateCreated']=str(datetime.date(2026,8,1)+datetime.timedelta(days=i));all_items.append(item)
            if state['small_catalog']: all_items.insert(0,video())
            parent=q.get('ParentId',[''])[0]
            if parent=='tv' or q.get('IncludeItemTypes',[''])[0]=='Series': all_items=[dict(video('series'),Type='Series',Name='The horizon')]
            if parent=='series': all_items=[dict(Id='season1',Name='Season 1',Type='Season'),dict(Id='season2',Name='Season 2',Type='Season')]
            if parent=='season1': all_items=[video('ep1'),video('ep2')]
            if parent=='season2': all_items=[video('ep3')]
            if parent=='movies' and q.get('Recursive',['false'])[0]!='true': all_items=[dict(Id='collection',Name='Collections',SortName='Collections',Type='Folder')]+all_items
            query=q.get('SearchTerm',[''])[0].lower()
            if query:
                with lock: state['searches'].append({'term':query,'time':time.monotonic(),'types':q.get('IncludeItemTypes',[''])[0]})
                if query==state['slow_search']: time.sleep(2)
                all_items=[v for v in {v['Id']:v for v in [video()]+all_items}.values() if query in v['Name'].lower() or (query=='月球' and v['Id']=='demo')]
            if 'IsFavorite' in q.get('Filters',[''])[0]: all_items=[video()]
            types=q.get('IncludeItemTypes',[''])[0].split(',')
            if types[0]: all_items=[v for v in all_items if v['Type'] in types]
            genre=q.get('Genres',[''])[0];year=q.get('Years',[''])[0]
            if genre: all_items=[v for v in all_items if genre in v.get('Genres',[])]
            if year: all_items=[v for v in all_items if str(v.get('ProductionYear'))==year]
            if 'IsPlayed' in q: all_items=[v for v in all_items if v.get('UserData',{}).get('Played',False)==(q['IsPlayed'][0]=='true')]
            sort=q.get('SortBy',[''])[0]
            if sort:
                field={'SortName':'SortName','DateCreated':'DateCreated','PremiereDate':'ProductionYear','CommunityRating':'CommunityRating'}[sort]
                all_items.sort(key=lambda v:str(v.get(field,'')),reverse=q.get('SortOrder',['Ascending'])[0]=='Descending')
            self.respond({'Items':all_items[start:start+limit],'TotalRecordCount':len(all_items)}); return
        if '/Users/u1/Items/' in path: self.respond(video(path.rsplit('/',1)[-1])); return
        if '/Images/Backdrop/' in path or '/Images/Primary' in path:
            item=path.split('/Items/')[-1].split('/')[0]
            resource=movie_art(item)['file'].removesuffix('.jpg')
            image=ASSETS/(resource+('-backdrop.jpg' if '/Backdrop/' in path else '-poster.jpg'))
            self.respond(image.read_bytes(),kind='image/jpeg'); return
        if path.endswith('/Similar'): self.respond({'Items':[video('film'+str(i)) for i in range(6)]}); return
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
            if start>=size:
                self.send_response(416);self.send_header('Content-Range',f'bytes */{size}');self.send_header('Content-Length','0');self.end_headers();return
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
