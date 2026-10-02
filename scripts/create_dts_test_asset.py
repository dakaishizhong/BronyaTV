#!/usr/bin/env python3
"""Generate the local AVC/DTS fixture used by DtsAudioDeviceTest."""
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
output = root / "tests/assets/dts-avc.mkv"
output.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([
    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
    "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
    "-t", "18", "-c:v", "libx264", "-preset", "ultrafast", "-threads", "2",
    "-pix_fmt", "yuv420p", "-c:a", "dca", "-strict", "-2", "-b:a", "768k", str(output)
], check=True)
print(output)
