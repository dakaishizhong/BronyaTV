#!/usr/bin/env python3
import pathlib, subprocess
from PIL import Image, ImageDraw, ImageFont
r=pathlib.Path(__file__).resolve().parents[1]
a=r/'tests/assets';a.mkdir(parents=True,exist_ok=True)
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
im=Image.new('RGB',(500,750));d=ImageDraw.Draw(im)
for y in range(750):
    for x in range(500):
        c=(int(12+24*y/750),int(26+30*x/500),int(45+62*y/750));im.putpixel((x,y),c)
d=ImageDraw.Draw(im);d.ellipse((40,60,420,440),fill=(184,122,83));d.ellipse((115,112,440,500),fill=(19,49,66))
d.text((42,535),'OCEAN',font=ImageFont.truetype(f,55),fill=(248,237,215));d.text((44,605),'OF LIGHT',font=ImageFont.truetype(f,45),fill=(248,237,215));im.save(a/'poster.jpg',quality=92)
im.resize((1280,720)).save(a/'backdrop.jpg',quality=90)
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
    run(['-f','lavfi','-i','testsrc2=size=640x360:rate=24','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-f','lavfi','-i','sine=frequency=880:sample_rate=48000','-t','90','-map','0:v','-map','1:a','-map','2:a','-c:v','libx264','-preset','ultrafast','-threads','2','-b:v','1200k','-pix_fmt','yuv420p','-c:a','aac','-metadata:s:a:0','language=eng','-metadata:s:a:1','language=zho','-movflags','+faststart',str(a/'sample.mp4')])
    run(['-i',str(a/'sample.mp4'),'-i',str(a/'subtitle.srt'),'-i',str(a/'subtitle.ass'),'-map','0','-map','1','-map','2','-c','copy',str(a/'sample.mkv')])
if not (a/'hevc.mkv').exists():
    run(['-f','lavfi','-i','testsrc2=size=640x360:rate=24','-f','lavfi','-i','sine=sample_rate=48000','-t','30','-c:v','libx265','-preset','ultrafast','-pix_fmt','yuv420p10le','-x265-params','pools=2:frame-threads=2:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc','-c:a','aac',str(a/'hevc.mkv')])
if not (a/'vp8.webm').exists():
    run(['-f','lavfi','-i','testsrc2=size=320x180:rate=12','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','90','-c:v','libvpx','-deadline','realtime','-cpu-used','8','-threads','2','-b:v','250k','-c:a','libvorbis',str(a/'vp8.webm')])
print('Created real MP4 / MKV / HEVC / VP8 test assets')
