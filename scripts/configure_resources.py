#!/usr/bin/env python3
"""Use actual affinity/cgroup limits, leaving RAM for SDK, OS and emulator."""
import os, pathlib
root = pathlib.Path(__file__).resolve().parents[1]
cpus = len(os.sched_getaffinity(0))
memory = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
for p in ['/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/memory/memory.limit_in_bytes']:
    try:
        limit = int(pathlib.Path(p).read_text().strip())
        if 0 < limit < memory: memory = limit
    except (OSError, ValueError): pass
try:
    quota, period = pathlib.Path('/sys/fs/cgroup/cpu.max').read_text().split()
    if quota != 'max': cpus = min(cpus, max(1, (int(quota) + int(period) - 1) // int(period)))
except (OSError, ValueError): pass
heap = max(768, min(6144, int(memory / 1024**2 * .38)))
kotlin_heap = max(512, min(2048, int(memory / 1024**2 * .12)))
(root/'gradle.properties').write_text(f'''org.gradle.jvmargs=-Xmx{heap}m -XX:MaxMetaspaceSize=1024m -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.workers.max={cpus}
kotlin.daemon.jvmargs=-Xmx{kotlin_heap}m
kotlin.incremental=true
android.useAndroidX=true
''')
print(f'CPU workers={cpus}; effective RAM={memory/1024**3:.1f} GiB; Gradle heap={heap} MiB; Kotlin heap={kotlin_heap} MiB')
