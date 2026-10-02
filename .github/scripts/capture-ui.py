"""Exercise visible controls and capture the actual Android UI, using accessibility bounds."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

output = Path('work/screenshots')
output.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=40)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/ui.xml')).iter('node')

def find(label):
    for node in nodes():
        if label in (node.get('text'), node.get('content-desc')):
            bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return bounds
    raise AssertionError(f'Visible control not found: {label}')

def tap(label):
    x1, y1, x2, y2 = find(label)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)

def capture(name, expected):
    find(expected)  # Fail CI if navigation/rendering crashed or the target UI is missing.
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))

def back():
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)

def toggle_transparency(expected):
    # The first switch belongs to the transparency row; HDR is farther down.
    switch = next(node for node in nodes() if node.get('checkable') == 'true')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', switch.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)
    switch = next(node for node in nodes() if node.get('checkable') == 'true')
    assert switch.get('checked') == str(expected).lower(), 'Transparency preference did not update'

capture('library', 'My scans')
adb('shell', 'cmd', 'uimode', 'night', 'yes')
time.sleep(2)
capture('library-dark', 'My scans')
adb('shell', 'cmd', 'uimode', 'night', 'no')
time.sleep(2)
tap('Page 1')
capture('editor', 'Adjust Boundary')
tap('Adjust Boundary')
capture('crop', 'Cancel')
back()
tap('Export Document')
capture('export', 'Close')
back()
tap('Scan')
capture('camera', 'Settings')
tap('Settings')
capture('settings', 'Reduce transparency')
toggle_transparency(True)
capture('settings-solid', 'Reduce transparency')
back()
back()
capture('library-solid', 'My scans')
# Confirm that the opaque accessibility preference persisted across navigation.
tap('Scan')
tap('Settings')
toggle_transparency(False)
back()
back()
capture('library', 'My scans')
