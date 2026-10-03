"""Exercise actual UI controls and save screenshots; use accessible bounds, never guessed taps."""
import itertools
import re
import subprocess
import struct
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import zlib

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


def background_samples(png):
    """Decode ordinary Android 8-bit RGB/RGBA PNGs with the standard library only.

    Sample narrow outer margins below the toolbar and above the dock, avoiding clocks,
    document/text content, and controls. Unsupported PNGs still remain useful artifacts.
    """
    if not png.startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('Not a PNG')
    compressed = bytearray()
    offset = 8
    while offset < len(png):
        size = struct.unpack_from('>I', png, offset)[0]
        kind = png[offset + 4:offset + 8]
        data = png[offset + 8:offset + 8 + size]
        if kind == b'IHDR':
            width, height, bits, colors, compression, filtering, interlace = struct.unpack('>IIBBBBB', data)
            if bits != 8 or colors not in (2, 6) or compression or filtering or interlace:
                raise ValueError('PNG format is outside the screencap RGB/RGBA subset')
            channels = 3 if colors == 2 else 4
        elif kind == b'IDAT':
            compressed.extend(data)
        elif kind == b'IEND':
            break
        offset += size + 12
    stride = width * channels
    decoded = zlib.decompress(compressed)
    if len(decoded) != (stride + 1) * height:
        raise ValueError('Unexpected PNG row size')
    previous = bytearray(stride)
    values = []
    for y in range(height):
        start = y * (stride + 1)
        mode = decoded[start]
        row = bytearray(decoded[start + 1:start + 1 + stride])
        if mode not in range(5):
            raise ValueError('Unknown PNG row filter')
        if mode:
            for i in range(stride):
                left = row[i - channels] if i >= channels else 0
                above = previous[i]
                upper_left = previous[i - channels] if i >= channels else 0
                if mode == 1:
                    predictor = left
                elif mode == 2:
                    predictor = above
                elif mode == 3:
                    predictor = (left + above) // 2
                else:
                    p = left + above - upper_left
                    a, b, c = abs(p - left), abs(p - above), abs(p - upper_left)
                    predictor = left if a <= b and a <= c else above if b <= c else upper_left
                row[i] = (row[i] + predictor) & 255
        if 120 <= y < height - 120 and y % 4 == 0:
            for x in (4, 8, 12, width - 13, width - 9, width - 5):
                values.extend(row[x * channels:x * channels + 3])
        previous = row
    return (width, height), values


def ambient_pair(prefix, interval):
    # No taps, UI dumps, or settings changes between frames: only the background may move.
    first = adb('exec-out', 'screencap', '-p')
    (output / f'{prefix}-a.png').write_bytes(first)
    time.sleep(interval)
    second = adb('exec-out', 'screencap', '-p')
    (output / f'{prefix}-b.png').write_bytes(second)
    message = f'{prefix}: PNG bytes identical={first == second}; frame interval={interval}s'
    try:
        dimensions, a = background_samples(first)
        other_dimensions, b = background_samples(second)
        if dimensions != other_dimensions or len(a) != len(b) or not a:
            raise ValueError('Frame sizes or sample counts changed')
        differences = [abs(left - right) for left, right in zip(a, b)]
        message += f'; background RGB mean delta={sum(differences) / len(differences):.4f}, max delta={max(differences)}'
    except Exception as decode_error:
        message += f'; background decoder skipped: {decode_error}'
    print(message, flush=True)
    with (output / 'ambient-diagnostics.txt').open('a', encoding='utf-8') as report:
        report.write(message + '\n')


def record_ambient():
    """Best-effort six-second video; recording and pull share a 20-second deadline."""
    video = output / 'ambient.mp4'
    deadline = time.monotonic() + 20
    try:
        subprocess.run(['adb', 'shell', 'screenrecord', '--size', '480x800',
                        '--bit-rate', '2000000', '--time-limit', '6', '/sdcard/ambient.mp4'],
                       check=True, capture_output=True, timeout=20)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Video recording exceeded its 20-second deadline')
        subprocess.run(['adb', 'pull', '/sdcard/ambient.mp4', str(video)],
                       check=True, capture_output=True, timeout=remaining)
        with video.open('rb') as stream:
            header = stream.read(8)
        if video.stat().st_size < 32 or header[4:8] != b'ftyp':
            raise ValueError('screenrecord output has no MP4 container header')
        message = f'Saved ambient.mp4 ({video.stat().st_size} bytes, six-second recording)'
    except Exception as recording_error:
        # Unsupported encoders or a slow/offline device must not fail the UI checks.
        try:
            video.unlink(missing_ok=True)
        except OSError:
            pass
        detail = getattr(recording_error, 'stderr', b'') or b''
        if isinstance(detail, bytes):
            detail = detail.decode(errors='replace')
        message = f'screenrecord skipped: {recording_error}; {detail[:500]}'
    print(message, flush=True)
    with (output / 'ambient-diagnostics.txt').open('a', encoding='utf-8') as report:
        report.write(message + '\n')


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

# The emulator normally disables animations. Check both the frozen accessible fallback
# and actual movement after changing only animator scale on this same empty-library screen.
previous_animator_scale = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').decode().strip()
try:
    adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
    time.sleep(.4)
    ambient_pair('ambient-static', 1)
    adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1')
    time.sleep(.6)
    assert_controls(('Scan', 'Settings'))
    ambient_pair('ambient', 2.5)
    record_ambient()
finally:
    if previous_animator_scale == 'null':
        adb('shell', 'settings', 'delete', 'global', 'animator_duration_scale')
    else:
        adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', previous_animator_scale)
