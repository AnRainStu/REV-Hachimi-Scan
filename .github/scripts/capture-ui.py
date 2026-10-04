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


def decode_png(png):
    """Decode ordinary Android 8-bit RGB/RGBA screenshots without extra dependencies."""
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
    rows = []
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
        rows.append(row)
        previous = row
    return width, height, channels, rows


def background_samples(png):
    # Narrow margins avoid clocks, document content and controls.
    width, height, channels, rows = decode_png(png)
    values = []
    for y in range(120, height - 120, 4):
        for x in (4, 8, 12, width - 13, width - 9, width - 5):
            values.extend(rows[y][x * channels:x * channels + 3])
    return (width, height), values


def document_pixels(png):
    # Center of the colored coastal illustration, away from controls, handles and glass.
    width, height, channels, rows = decode_png(png)
    return [tuple(rows[y][x * channels:x * channels + 3])
            for y in range(round(height * .42), round(height * .56), 3)
            for x in range(round(width * .4), round(width * .6), 3)]


def pixel_delta(left, right):
    assert len(left) == len(right) and left, 'Document sample sizes changed'
    return sum(abs(a - b) for l, r in zip(left, right) for a, b in zip(l, r)) / (3 * len(left))


def chroma(pixels):
    return sum(max(p) - min(p) for p in pixels) / len(pixels)


def await_preview(name, predicate):
    # A native worker can finish after the tap; inspect its pixels instead of a fixed delay.
    deadline = time.monotonic() + 15
    while True:
        png = adb('exec-out', 'screencap', '-p')
        pixels = document_pixels(png)
        if predicate(pixels):
            (output / f'{name}.png').write_bytes(png)
            return pixels
        assert time.monotonic() < deadline, f'{name}: expected document pixels never appeared'
        time.sleep(.2)


def scan_files():
    return set(adb('shell', 'run-as', 'moe.hachimi.cam', 'ls', 'files/scans').decode().split())


def check_fresh_crop_filters():
    # Page 2 is deliberately unprocessed, matching the state immediately after capture.
    source_args = ('exec-out', 'run-as', 'moe.hachimi.cam', 'cat', 'files/scans/fixture-coast-original.jpg')
    source_before, files_before = adb(*source_args), scan_files()
    tap('Page 2')
    tap('Adjust Boundary')
    tap('Original')
    original = await_preview('crop-filter-original', lambda p: chroma(p) > 20)
    previews = {}
    for label in ('Magic', 'B&W', 'Gray'):
        tap(label)
        pixels = await_preview(f'crop-filter-{label.lower().replace("&", "")}',
            lambda p: pixel_delta(original, p) > 4 and (label == 'Magic' or chroma(p) < 1)
            and (label != 'Magic' or chroma(p) > 10)
            and (label != 'B&W' or sum(min(rgb) > 250 for rgb in p) > len(p) / 2)
            and (label != 'Gray' or sum(20 < min(rgb) <= max(rgb) < 220 for rgb in p) > len(p) / 2)
            and (label != 'Gray' or pixel_delta(previews['B&W'], p) > 3))
        previews[label] = pixels
        print(f'Fresh crop {label}: document RGB delta={pixel_delta(original, pixels):.3f}, chroma={chroma(pixels):.3f}', flush=True)
    tap('Original')
    await_preview('crop-filter-restored', lambda p: pixel_delta(original, p) < .1)

    # Rapid taps must leave the last selected filter on screen, without cumulative filtering.
    state = snapshot()
    for label in ('Magic', 'B&W', 'Original', 'Gray'):
        x1, y1, x2, y2 = locate(label, state, control=True)
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    await_preview('crop-filter-rapid-gray', lambda p: pixel_delta(previews['Gray'], p) < .1)
    state = snapshot()
    selected = next(node for node in state[0] if node.get('text') == 'Gray')
    while selected.get('selected') != 'true' and selected in state[1]:
        selected = state[1][selected]
    assert selected.get('selected') == 'true', 'Rapid switching did not select the final Gray filter'
    tap('Cancel')
    deadline = time.monotonic() + 5
    while scan_files() != files_before:
        assert time.monotonic() < deadline, 'Cancel persisted a preview or left a draft'
        time.sleep(.2)
    assert adb(*source_args) == source_before, 'Filter preview changed the original capture'

    # Confirm must commit the same grayscale effect that was previewed.
    tap('Page 2')
    tap('Adjust Boundary')
    tap('Gray')
    await_preview('crop-filter-confirm-gray', lambda p: pixel_delta(previews['Gray'], p) < .1)
    tap('Confirm')
    deadline = time.monotonic() + 15
    while True:
        try:
            locate('My scans')
            break
        except AssertionError:
            assert time.monotonic() < deadline, 'Crop confirmation did not finish'
            time.sleep(.2)
    assert adb(*source_args) == source_before, 'Confirm changed the original capture'
    files_after = scan_files()
    assert files_before <= files_after, 'Confirm removed an unrelated scan file'
    assert len(files_after - files_before) == 1, 'Confirm must save one processed result'
    tap('Page 2')
    locate('Page 2')
    locate('Adjust Boundary', control=True)
    await_preview('editor-confirmed-gray', lambda p: chroma(p) < 1
        and sum(max(rgb) < 180 for rgb in p) > len(p) / 5)
    tap('Close')


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
check_fresh_crop_filters()

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
