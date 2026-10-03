"""Exercise actual UI controls and save screenshots; use accessible bounds, never guessed taps."""
import itertools
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

output = Path('work/screenshots')
output.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=40)


def failure(kind, value, traceback):
    # Preserve the original assertion even if the device stopped responding.
    for name, command in (
        ('failure.png', ('exec-out', 'screencap', '-p')),
        ('failure.xml', ('shell', 'cat', '/sdcard/ui.xml')),
        ('crash.log', ('logcat', '-d', '-t', '500')),
    ):
        try:
            (output / name).write_bytes(adb(*command))
        except Exception as capture_error:
            print(f'Cannot save {name}: {capture_error}', file=sys.stderr)
    sys.__excepthook__(kind, value, traceback)


sys.excepthook = failure


def snapshot():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    source = adb('shell', 'cat', '/sdcard/ui.xml')
    root = ET.fromstring(source)
    return list(root.iter('node')), {child: parent for parent in root.iter() for child in parent}, source


def bounds(node):
    value = tuple(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    return value if len(value) == 4 and value[2] > value[0] and value[3] > value[1] else None


def locate(label, state=None, control=False):
    visible, parents, _ = state or snapshot()
    # The toolbar Settings icon and permission help have the same words.
    for attribute in ('content-desc', 'text'):
        for node in visible:
            if label != node.get(attribute) or not bounds(node):
                continue
            if control:
                # Text occupies only part of a button. Validate/tap its actual hit area.
                while node.get('clickable') != 'true' and node in parents:
                    node = parents[node]
                if node.get('clickable') != 'true' or node.get('enabled') != 'true':
                    continue
            return bounds(node)
    raise AssertionError(f'Visible {"enabled control" if control else "content"} not found: {label}')


def tap(label):
    x1, y1, x2, y2 = locate(label, control=True)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.6)


def display_geometry():
    width, height = map(int, re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size').decode())[-1])
    density = int(re.findall(r'(?:Physical|Override) density: (\d+)', adb('shell', 'wm', 'density').decode())[-1]) / 160
    # This CI device uses gesture navigation. Also read the actual visible inset when available.
    nav = round(24 * density)
    windows = adb('shell', 'dumpsys', 'window', 'windows').decode()
    match = re.search(r'type=navigationBars[^\n]*frame=\[\d+,(\d+)\]\[\d+,(\d+)\][^\n]*visible=true', windows)
    if match:
        nav = int(match[2]) - int(match[1])
    return width, height, nav


def assert_controls(labels):
    state = snapshot()
    width, height, navigation_inset = display_geometry()
    controls = {label: locate(label, state, control=True) for label in labels}
    for label, (x1, y1, x2, y2) in controls.items():
        assert 0 <= x1 < x2 <= width, f'{label} extends outside the display: {controls[label]}'
        assert 0 <= y1 < y2 <= height - navigation_inset, f'{label} overlaps system navigation: {controls[label]}'
    for left, right in itertools.combinations(labels, 2):
        a, b = controls[left], controls[right]
        overlap = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
        assert overlap == 0, f'{left} and {right} have overlapping hit areas: {a}, {b}'
    return state


def capture(name, expected, controls=()):
    state = assert_controls(controls) if controls else snapshot()
    locate(expected, state)
    (output / f'{name}.xml').write_bytes(state[2])
    (output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    print(f'Captured {name}', flush=True)


def back():
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(.6)


def scroll(down):
    width, height, _ = display_geometry()
    start, end = (.76, .25) if down else (.25, .76)
    adb('shell', 'input', 'swipe', str(width // 2), str(round(height * start)),
        str(width // 2), str(round(height * end)), '450')
    time.sleep(.6)


def reset_scroll():
    scroll(False)
    scroll(False)
    locate('Page 1')


def toggle_transparency(expected):
    state = snapshot()
    # Find the actual checkable row containing the preference label, avoiding HDR.
    preference = next(node for node in state[0] if node.get('text') == 'Reduce transparency')
    row = preference
    while row in state[1] and not any(child.get('checkable') == 'true' for child in row.iter('node')):
        row = state[1][row]
    switch = next(node for node in row.iter('node') if node.get('checkable') == 'true')
    x1, y1, x2, y2 = bounds(switch)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.6)
    refreshed = snapshot()
    preference = next(node for node in refreshed[0] if node.get('text') == 'Reduce transparency')
    row = preference
    while row in refreshed[1] and not any(child.get('checkable') == 'true' for child in row.iter('node')):
        row = refreshed[1][row]
    switch = next(node for node in row.iter('node') if node.get('checkable') == 'true')
    assert switch.get('checked') == str(expected).lower(), 'Transparency preference did not update'


capture('library', 'My scans', ('Export', 'Scan', 'Settings', 'Select'))
first_page_top = locate('Page 1')[1]
scroll(True)
scroll_state = snapshot()
try:
    assert locate('Page 1', scroll_state)[1] < first_page_top, 'Library did not scroll under its toolbar'
except AssertionError as error:
    if 'not found' not in str(error):
        raise
locate('Page 5', scroll_state)  # More than the first row must genuinely have entered the viewport.
capture('library-scroll', 'Scan', ('Export', 'Scan', 'Settings', 'Select'))
reset_scroll()
adb('shell', 'cmd', 'uimode', 'night', 'yes')
time.sleep(1)
capture('library-dark', 'My scans', ('Export', 'Scan', 'Settings', 'Select'))
scroll(True)
capture('library-dark-scroll', 'Scan', ('Export', 'Scan', 'Settings', 'Select'))
reset_scroll()
adb('shell', 'cmd', 'uimode', 'night', 'no')
time.sleep(1)

# Enlarge text on a narrow viewport, checking actual control hit areas for clipping/overlap.
adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
adb('shell', 'wm', 'size', '320x640')
time.sleep(2)
capture('library-large-text', 'Scan', ('Export', 'Scan', 'Settings', 'Select'))
tap('Scan')
capture('camera-large-text', 'Settings', ('Settings', 'Import'))
permission_state = snapshot()
help_bounds = []
for node in permission_state[0]:
    if node.get('text') not in ('Grant Permission', 'Camera permission is required', 'Settings') or not bounds(node):
        continue
    hit_area = node
    while hit_area.get('clickable') != 'true' and hit_area in permission_state[1]:
        hit_area = permission_state[1][hit_area]
    help_bounds.append(bounds(hit_area) if hit_area.get('clickable') == 'true' else bounds(node))
assert help_bounds, 'Camera permission help is missing'
help_bottom = max(area[3] for area in help_bounds)
# Capture remains visible but disabled without permission, so inspect its bounds without requiring enabled=True.
dock_top = min(locate('Import', permission_state, control=True)[1], locate('Capture', permission_state)[1])
assert help_bottom < dock_top, f'Camera dock covers permission help: help bottom={help_bottom}, dock top={dock_top}'
tap('Settings')
capture('settings-large-text', 'Reduce transparency', ('Cancel',))
back()
back()
adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
adb('shell', 'wm', 'size', '480x800')
time.sleep(2)

tap('Page 1')
capture('editor', 'Adjust Boundary', ('Adjust Boundary', 'Rotate', 'Close'))
tap('Adjust Boundary')
capture('crop', 'Cancel', ('Cancel', 'Confirm'))
back()
tap('Export')
capture('export', 'Close', ('Close',))
back()
tap('Scan')
capture('camera', 'Settings', ('Settings', 'Import'))
tap('Settings')
capture('settings', 'Reduce transparency', ('Cancel',))
toggle_transparency(True)
capture('settings-solid', 'Reduce transparency', ('Cancel',))
back()
back()
capture('library-solid', 'My scans', ('Export', 'Scan', 'Settings', 'Select'))

# The opaque preference must survive both app recreation and subsequent navigation.
adb('shell', 'am', 'force-stop', 'moe.hachimi.cam')
adb('shell', 'am', 'start', '-n', 'moe.hachimi.cam/com.scanner.app.ui.MainActivity')
time.sleep(2)
tap('Settings')
toggle_transparency(False)
back()
capture('library', 'My scans', ('Export', 'Scan', 'Settings', 'Select'))

# Delete only the synthetic fixtures, after all editing captures, and exercise the empty state.
tap('Select')
tap('Select All')
tap('Delete')
locate('Delete Pages')
tap('Delete')
time.sleep(1)
empty = snapshot()
assert not any(node.get('content-desc') == 'Page 1' or node.get('text') == 'Page 1' for node in empty[0]), 'Delete left fixture pages visible'
capture('library-empty', 'Scan', ('Scan', 'Settings'))
