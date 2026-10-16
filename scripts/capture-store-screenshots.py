"""Capture six debug-only app states on an explicitly selected Android emulator."""
import argparse
import io
import os
from pathlib import Path
import subprocess
import tarfile
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('This helper is restricted to emulators; it does not alter personal phones.')
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
adb = sdk / 'platform-tools/adb.exe'
def call(*parts):
    result = subprocess.run([str(adb), '-s', args.serial, *parts], check=True, capture_output=True, timeout=120)
    output = result.stdout.decode('utf-8', errors='replace')
    if output.strip(): print(output.strip(), flush=True)
    return output

for _ in range(45):
    boot = subprocess.run([str(adb),'-s',args.serial,'shell','getprop','sys.boot_completed'], capture_output=True)
    if boot.stdout.strip() == b'1': break
    time.sleep(1)
else:
    raise SystemExit('Emulator has not finished booting; retry after boot completes.')
call('shell','input','keyevent','KEYCODE_WAKEUP')
call('shell','wm','dismiss-keyguard')
call('shell','wm','size','1080x1920')
call('shell','wm','density','320')
for setting in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
    call('shell','settings','put','global',setting,'0')
call('shell','settings','put','global','sysui_demo_allowed','1')
for extra in [('clock','--es','hhmm','0941'),('battery','--es','level','100','--es','plugged','false'),('notifications','--es','visible','false')]:
    call('shell','am','broadcast','-a','com.android.systemui.demo','--es','command',*extra)
call('install','--no-streaming','-r','-t',str(args.apk.resolve()))
time.sleep(2)
result = call('shell','am','instrument','-w','com.statsupdate.npat/.ScreenshotInstrumentation')
if 'Captured six native screens' not in result:
    raise SystemExit('The screenshot instrumentation did not complete successfully.')
archive = subprocess.run([str(adb),'-s',args.serial,'exec-out','run-as','com.statsupdate.npat','tar','-cf','-','files/store-captures'], check=True, capture_output=True).stdout
output = ROOT / 'release/store-assets/screenshots/raw'
output.mkdir(parents=True,exist_ok=True)
count = 0
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    for member in tar.getmembers():
        if member.isfile() and member.name.endswith('.png'):
            (output / Path(member.name).name).write_bytes(tar.extractfile(member).read())
            count += 1
assert count == 6, count
print('Saved six real Android screenshots.', flush=True)
