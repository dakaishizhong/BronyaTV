#!/usr/bin/env python3
import hashlib, json, pathlib, subprocess, urllib.request
from PIL import Image
r=pathlib.Path(__file__).resolve().parents[1]
a=r/'tests/assets';a.mkdir(parents=True,exist_ok=True)
media=r/'tests/media'
reference=json.loads((media/'cinema-reference.json').read_text())
sources=reference['movies']
resources=[(entry['id'],entry['posterUrl'],entry['backdropUrl']) for entry in sources]
series=next(entry for entry in sources if entry.get('isSeries'))
resources.extend(('ep'+str(i+1),episode['backdropUrl'],episode['backdropUrl']) for i,episode in enumerate(series['episodes']))
resources.extend(('cast'+str(i+1),person['avatarUrl'],person['avatarUrl']) for entry in sources for i,person in enumerate(entry.get('cast',[])))
for resource,poster_url,backdrop_url in resources:
    for kind,url in [('poster',poster_url),('backdrop',backdrop_url)]:
        destination=a/(resource+'-'+kind+'.jpg')
        if not destination.exists():
            print('Downloading reference resource:',resource,kind,flush=True)
            with urllib.request.urlopen(url,timeout=60) as response:
                raw=response.read()
            import io
            with Image.open(io.BytesIO(raw)) as image:
                image.convert('RGB').save(destination,quality=92)
(a/'backdrop.jpg').write_bytes((a/'dune2-backdrop.jpg').read_bytes())
(a/'poster.jpg').write_bytes((a/'dune2-poster.jpg').read_bytes())
# The document's photography also supplies our generated playback test clips.
# These are short test videos, not copies of the named films, and stay outside the APK.
fingerprint=hashlib.sha256((media/'cinema-reference.json').read_bytes()+b'cinema-reference-v1').hexdigest()
marker=a/'source-fingerprint.txt'
if not marker.exists() or marker.read_text()!=fingerprint:
    for name in ['sample.mp4','sample.mkv','hevc.mkv','vp8.webm']:
        (a/name).unlink(missing_ok=True)
(a/'subtitle.srt').write_text('1\n00:00:00,000 --> 00:00:40,000\nDirect Play · SRT subtitle test\n\n2\n00:00:40,000 --> 00:01:30,000\nAudio and network recovery test\n')
(a/'subtitle.ass').write_text('''[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,24,&H0000FFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,1,1,2,10,10,16,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:01:30.00,Default,,0,0,0,,ASS subtitle test
''')
def run(args): subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error']+args,check=True)
if not (a/'sample.mp4').exists():
    run(['-loop','1','-framerate','24','-i',str(a/'backdrop.jpg'),'-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-f','lavfi','-i','sine=frequency=880:sample_rate=48000','-t','90','-map','0:v','-map','1:a','-map','2:a','-vf',"zoompan=z='1+0.03*on/2160':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=1:s=640x360:fps=24",'-c:v','libx264','-preset','ultrafast','-threads','2','-b:v','1200k','-pix_fmt','yuv420p','-c:a','aac','-metadata:s:a:0','language=eng','-metadata:s:a:1','language=zho','-movflags','+faststart',str(a/'sample.mp4')])
    run(['-i',str(a/'sample.mp4'),'-i',str(a/'subtitle.srt'),'-i',str(a/'subtitle.ass'),'-map','0','-map','1','-map','2','-c','copy',str(a/'sample.mkv')])
if not (a/'hevc.mkv').exists():
    run(['-loop','1','-framerate','24','-i',str(a/'backdrop.jpg'),'-f','lavfi','-i','sine=sample_rate=48000','-t','30','-vf',"zoompan=z='1+0.03*on/720':d=1:s=640x360:fps=24",'-c:v','libx265','-preset','ultrafast','-pix_fmt','yuv420p10le','-x265-params','pools=2:frame-threads=2:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc','-c:a','aac',str(a/'hevc.mkv')])
if not (a/'vp8.webm').exists():
    run(['-loop','1','-framerate','12','-i',str(a/'backdrop.jpg'),'-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','90','-vf',"zoompan=z='1+0.03*on/1080':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=1:s=320x180:fps=12",'-c:v','libvpx','-deadline','realtime','-cpu-used','8','-threads','2','-b:v','250k','-c:a','libvorbis',str(a/'vp8.webm')])
marker.write_text(fingerprint)
print('Created reference-document example assets and synthetic MP4 / MKV / HEVC / VP8 clips')
