"""
Sightline end-to-end checks against a running dev server (mvn quarkus:dev), in headless Chrome.

    python src/test/sightline/sightline_e2e.py --base http://localhost:8080 \
        --findings-dir docs/findings --out <scratch dir> [--only main,clock,deny,shortcut,guard,cap] \
        [--stop-server-cmd "<command that stops the server>"]

Uses the installed Chrome (channel="chrome") with a fake capture UI:
--use-fake-ui-for-media-stream --auto-select-desktop-capture-source=... --auto-accept-this-tab-capture.
Exits non-zero when any check fails. The "server-down" check runs last and only with --stop-server-cmd.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageChops, ImageStat
from playwright.sync_api import sync_playwright

FAKE_CAPTURE = ["--use-fake-ui-for-media-stream", "--auto-select-desktop-capture-source=Vehicle Routing",
                "--auto-accept-this-tab-capture"]
COLOURS = {"red": (0xe5, 0x39, 0x35), "green": (0x2e, 0x7d, 0x32), "blue": (0x1e, 0x63, 0xd6),
           "yellow": (0xff, 0xd6, 0x00), "white": (0xff, 0xff, 0xff)}

results = []


def check(name, ok, evidence):
    results.append((name, bool(ok), evidence))
    print(("PASS " if ok else "FAIL ") + name + " :: " + str(evidence), flush=True)
    return ok


def finding_files(d):
    return sorted(p.name for p in Path(d).glob("finding-*") if not p.name.endswith(".tmp"))


def open_app(browser, base, viewport=(1600, 1000), dpr=1):
    ctx = browser.new_context(viewport={"width": viewport[0], "height": viewport[1]}, device_scale_factor=dpr)
    page = ctx.new_page()
    page.on("pageerror", lambda e: print("PAGEERROR", e, flush=True))
    page.goto(base, wait_until="load", timeout=60000)
    page.wait_for_function("typeof initialized !== 'undefined' && initialized && window.Sightline", timeout=30000)
    page.wait_for_timeout(1500)  # tiles
    return ctx, page


def wait_view(page, visible=True, timeout=15000):
    page.wait_for_function(f"window.Sightline.isViewVisible() === {str(visible).lower()}", timeout=timeout)


def canvas_point(page, x, y):
    """Image coordinates -> client coordinates on the annotation canvas."""
    r = page.evaluate("""() => { const c = document.querySelector('#sightline-view canvas');
        const r = c.getBoundingClientRect(); return [r.left, r.top, r.width / c.width, r.height / c.height]; }""")
    return r[0] + x * r[2], r[1] + y * r[3]


def drag(page, pts, steps=6):
    x, y = canvas_point(page, *pts[0])
    page.mouse.move(x, y)
    page.mouse.down()
    for p in pts[1:]:
        x, y = canvas_point(page, *p)
        page.mouse.move(x, y, steps=steps)
    page.mouse.up()


def choose(page, kind_style, value):
    page.click(f"#sightline-view [data-{kind_style}='{value}']")


def solve_for(page, seconds):
    page.click("#solveButton")
    page.wait_for_function("$('#stopSolvingButton').is(':visible')", timeout=10000)
    page.wait_for_function("$('#score').text() !== '?'", timeout=30000)
    page.wait_for_timeout(seconds * 1000)


def stop_solving(page):
    page.click("#stopSolvingButton")
    page.wait_for_function("$('#solveButton').is(':visible')", timeout=15000)
    page.wait_for_timeout(2500)


def mean_diff(a, b):
    diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    return sum(ImageStat.Stat(diff).mean) / 3


def label_visible(img, m):
    """Sample a ring inside the number badge: most samples must be the mark's colour."""
    import math
    lx, ly = m["label"]  # the badge centre the client recorded; checked against the pixels
    w = m["width"]
    r = max(14, w * 4) if m["kind"] == "number" else max(10, w * 2.6)
    want = COLOURS[m["colour"]]
    hits = 0
    n = 24
    for i in range(n):
        a = 2 * math.pi * i / n
        px = int(round(lx + 0.85 * r * math.cos(a)))
        py = int(round(ly + 0.85 * r * math.sin(a)))
        if 0 <= px < img.width and 0 <= py < img.height:
            c = img.getpixel((px, py))[:3]
            if sum(abs(c[k] - want[k]) for k in range(3)) < 60:
                hits += 1
    return hits, n


# ---------------------------------------------------------------------------------------------------------
def main_finding(browser, args, out):
    ctx, page = open_app(browser, args.base)
    solve_for(page, 6)
    stop_solving(page)
    scene = page.evaluate("Sightline.snapshotScene()")
    visits = [o for o in scene if o["kind"] == "visit-marker"]
    points = [o for o in scene if o["shape"] == "circle"]
    mr = page.evaluate("(() => { const r = document.getElementById('map').getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom]; })()")

    def isolation(o):
        return min([((o["x"] - q["x"]) ** 2 + (o["y"] - q["y"]) ** 2) ** 0.5 for q in points if q is not o] + [1e9])

    inner = [v for v in visits if mr[0] + 60 < v["x"] < mr[2] - 60 and mr[1] + 60 < v["y"] < mr[3] - 60]
    target = max(inner, key=isolation)
    homes = [o for o in scene if o["kind"] == "vehicle-home" and mr[0] + 90 < o["x"] < mr[2] - 90 and mr[1] + 90 < o["y"] < mr[3] - 30]
    home = max(homes, key=isolation) if homes else None
    legs = [o for o in scene if o["kind"] == "route-leg"]

    def leg_ok(l):
        mx, my = (l["x1"] + l["x2"]) / 2, (l["y1"] + l["y2"]) / 2
        length = ((l["x2"] - l["x1"]) ** 2 + (l["y2"] - l["y1"]) ** 2) ** 0.5
        clear = min(((mx - q["x"]) ** 2 + (my - q["y"]) ** 2) ** 0.5 for q in points)
        return length > 90 and clear > 35 and mr[0] + 40 < mx < mr[2] - 40 and mr[1] + 40 < my < mr[3] - 40

    leg = next((l for l in sorted(legs, key=lambda l: -((l["x2"] - l["x1"]) ** 2 + (l["y2"] - l["y1"]) ** 2)) if leg_ok(l)), None)
    print("target", target["id"], "isolation", round(isolation(target)), "home", home and home["id"], "leg", leg and leg["id"])

    before = page.screenshot()
    (out / "main_before.png").write_bytes(before)
    before_files = set(finding_files(args.findings_dir))
    page.click("#sightlineButton")
    wait_view(page)
    # Mark 1: red ellipse around the single target visit.
    choose(page, "kind", "ellipse"); choose(page, "colour", "red")
    r = 22
    drag(page, [[target["x"] - r, target["y"] - r], [target["x"] + r, target["y"] + r]])
    # Mark 2: green line across a route leg.
    choose(page, "kind", "line"); choose(page, "colour", "green")
    drawn = [{"kind": "ellipse", "colour": "red"}]
    if leg:
        mx, my = (leg["x1"] + leg["x2"]) / 2, (leg["y1"] + leg["y2"]) / 2
        dx, dy = leg["x2"] - leg["x1"], leg["y2"] - leg["y1"]
        L = (dx * dx + dy * dy) ** 0.5
        nx, ny = -dy / L * 18, dx / L * 18
        drag(page, [[mx - nx, my - ny], [mx + nx, my + ny]])
        drawn.append({"kind": "line", "colour": "green", "expect": leg["id"]})
    # Mark 3: green arrow pointing at a vehicle home.
    choose(page, "kind", "arrow")
    if home:
        drag(page, [[home["x"] + 70, home["y"] - 55], [home["x"] + 9, home["y"] - 7]])
        drawn.append({"kind": "arrow", "colour": "green", "expect": home["id"]})
    page.fill("#sightline-view .sl-note", "e2e: visit circled, leg crossed, home pointed at")
    page.click("#sightline-view .sl-file")
    page.wait_for_function("window.__sightlineLastFiled", timeout=30000)
    stem = page.evaluate("window.__sightlineLastFiled")
    wait_view(page, False)
    new = sorted(set(finding_files(args.findings_dir)) - before_files)
    check("main: exactly three new files", new == sorted([stem + ".json", stem + ".png", stem + ".thumb.png"]), new)
    d = Path(args.findings_dir)
    side = json.loads((d / (stem + ".json")).read_text(encoding="utf-8"))
    png = Image.open(d / (stem + ".png")).convert("RGB")
    bef = Image.open(out / "main_before.png").convert("RGB")
    check("main: image size equals viewport capture", png.size == bef.size, (png.size, bef.size))
    # Compare regions away from the marks: the navbar strip and the sidebar.
    nav = (0, 0, png.width, 60)
    side_r = (int(mr[2]) + 10, 120, png.width - 20, 700)
    dn = mean_diff(png.crop(nav), bef.crop(nav))
    ds = mean_diff(png.crop(side_r), bef.crop(side_r))
    toolbar_like = png.crop((0, 0, png.width, 40)).resize((1, 1)).getpixel((0, 0))
    check("main: PNG shows the application, not the annotation view",
          dn < 12 and ds < 12 and not (abs(toolbar_like[0] - 0x2b) < 10 and abs(toolbar_like[1] - 0x2b) < 10),
          f"mean diff navbar={dn:.2f} sidebar={ds:.2f}, top strip avg={toolbar_like}")
    marks = side["marks"]
    check("main: sidecar marks match what was drawn",
          len(marks) == len(drawn) and all(m["kind"] == e["kind"] and m["colour"] == e["colour"] for m, e in zip(marks, drawn))
          and [m["n"] for m in marks] == list(range(1, len(drawn) + 1))
          and abs((marks[0]["points"][0][0] + marks[0]["points"][1][0]) / 2 - target["x"]) < 3,
          [(m["n"], m["kind"], m["colour"], m["points"][:2]) for m in marks])
    labels = [label_visible(png, m) for m in marks]
    check("main: number labels appear in the image", all(h >= n * 0.6 for h, n in labels), labels)
    m1 = [o["id"] for o in marks[0]["objects"]]
    m1_visits = [i for i in m1 if i.startswith("visit:")]
    check("main: ellipse resolves to that single object's id", m1_visits == [target["id"]], marks[0]["objects"])
    for m, e in zip(marks[1:], drawn[1:]):
        ids = [o["id"] for o in m["objects"]]
        check(f"main: {e['kind']} resolves to {e['expect']}", e["expect"] in ids, m["objects"])
    obj = side.get("objects", {}).get(target["id"], {})
    check("main: resolved object carries the data that made it",
          obj.get("vehicle") is not None and "placedBy" in obj and "constraintImpacts" in obj,
          {k: obj.get(k) for k in ("vehicle", "routeIndex", "arrivalTime", "placedBy")})
    index = (d / "index.md").read_text(encoding="utf-8")
    first = next((l for l in index.splitlines() if l.startswith("- **")), "")
    check("main: index.md lists the finding at the top", stem in first, first[:160])
    job = side["state"].get("scheduleId")
    jobs = [j["jobId"] for j in side["backend"]["jobs"]]
    posts = [e for e in side["backend"]["events"] if e.get("type") == "http" and e.get("method") == "POST" and e.get("path") == "/route-plans"]
    check("backend log contains the job that produced the result", job in jobs and posts,
          f"scheduleId={job}, jobs={jobs}, POST /route-plans events={len(posts)}")
    thumb = Image.open(d / (stem + ".thumb.png"))
    check("thumbnail ~360px longest edge", max(thumb.size) == 360, thumb.size)
    (out / "main_stem.txt").write_text(stem)
    ctx.close()
    return stem


def clock_check(browser, args, out):
    ctx, page = open_app(browser, args.base)
    solve_for(page, 5)
    before = page.evaluate("""() => ({stop: $('#stopSolvingButton').is(':visible'), solve: $('#solveButton').is(':visible'),
        score: $('#score').text(), clock: AppClock.now()})""")
    shot = page.screenshot()
    (out / "clock_before.png").write_bytes(shot)
    page.click("#sightlineButton")
    wait_view(page)
    frozen1 = page.evaluate("({clock: AppClock.now(), frozen: AppClock.isFrozen(), score: $('#score').text(), stop: $('#stopSolvingButton').is(':visible')})")
    page.wait_for_timeout(5000)  # solver keeps improving server-side; the page must not move
    frozen2 = page.evaluate("({clock: AppClock.now(), score: $('#score').text(), stop: $('#stopSolvingButton').is(':visible')})")
    frame = page.evaluate("document.querySelector('#sightline-view canvas').toDataURL('image/png')")
    import base64, io
    img = Image.open(io.BytesIO(base64.b64decode(frame.split(",")[1]))).convert("RGB")
    img.save(out / "clock_captured.png")
    bef = Image.open(out / "clock_before.png").convert("RGB")
    rects = page.evaluate("""() => ['#stopSolvingButton', '#score', '#solveButton'].map(s => {
        const e = document.querySelector(s); const r = e.getBoundingClientRect(); return [s, r.left, r.top, r.right, r.bottom]; })""")
    diffs = {}
    for s, l, t, r_, b in rects:
        if r_ - l < 2:
            continue
        box = (int(l) - 2, int(t) - 2, int(r_) + 2, int(b) + 2)
        diffs[s] = round(mean_diff(img.crop(box), bef.crop(box)), 2)
    check("clock: image shows the state before the press (buttons/labels)",
          all(v < 12 for v in diffs.values()) and frozen1["stop"] and before["stop"] and frozen1["score"] == before["score"],
          f"before={before['score']} stop={before['stop']}; captured-vs-before diffs={diffs}")
    check("clock: app clock frozen while annotating (no ticks, no re-render)",
          frozen1["frozen"] and frozen1["clock"] == frozen2["clock"] and frozen1["score"] == frozen2["score"],
          f"clock {frozen1['clock']:.1f} -> {frozen2['clock']:.1f} over 5 s; score {frozen1['score']} -> {frozen2['score']}")
    page.click("#sightline-view .sl-cancel")
    wait_view(page, False)
    last = page.evaluate("window.__sightlineLast")
    check("clock: resumes from the frozen value after closing",
          last and abs(last["clockAfterResume"] - last["clockAtFreeze"]) < 50,
          last)
    page.wait_for_timeout(4500)
    after = page.evaluate("({clock: AppClock.now(), frozen: AppClock.isFrozen(), score: $('#score').text()})")
    check("clock: keeps running after resume (polling continues)", not after["frozen"] and after["clock"] > last["clockAfterResume"] + 3000,
          f"clock {after['clock']:.0f}, score {after['score']}")
    stop_solving(page)
    ctx.close()


def guard_check(browser, args, out):
    ctx, page = open_app(browser, args.base)
    page.click("#sightlineButton")
    try:
        wait_view(page)
        page.wait_for_timeout(1000)
        frame = page.evaluate("""() => { const c = document.querySelector('#sightline-view canvas');
            const t = document.querySelector('.sl-toast'); return {w: c.width, h: c.height, toast: t ? t.textContent : null}; }""")
        ok_open = frame["w"] == 1600 and frame["h"] == 1000 and not frame["toast"]
    except Exception as e:
        frame, ok_open = repr(e), False
    res = page.evaluate("""async () => { try { await Sightline.capture(); return 'captured'; }
        catch (e) { return (e instanceof Sightline.CaptureRefused ? 'refused: ' : 'other: ') + e.message; } }""")
    check("guard: the Finding button opens the view on a captured frame", ok_open, frame)
    check("guard: capture() refuses while the annotation view is visible", res.startswith("refused"), res)
    if ok_open:
        page.click("#sightline-view .sl-cancel")
    ctx.close()


def shortcut_check(browser, args, out):
    ctx, page = open_app(browser, args.base)
    solve_for(page, 2)
    stop_solving(page)
    # A real app text field: the add-visit dialog's name input.
    mr = page.evaluate("(() => { const r = document.getElementById('map').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; })()")
    page.mouse.click(mr[0] + mr[2] * 0.37, mr[1] + mr[3] * 0.41)
    page.wait_for_selector("#inputName", state="visible", timeout=10000)
    page.focus("#inputName")
    page.keyboard.press("Alt+Shift+KeyF")
    page.wait_for_timeout(1500)
    in_field = page.evaluate("Sightline.isViewVisible()")
    check("shortcut: does not fire inside a text field", not in_field, f"focus=#inputName, view visible={in_field}")
    page.keyboard.press("Escape")
    page.wait_for_selector("#newVisitModal", state="hidden", timeout=10000)
    page.evaluate("document.activeElement && document.activeElement.blur()")
    page.keyboard.press("Alt+Shift+KeyF")
    try:
        wait_view(page)
        fired = True
    except Exception:
        fired = False
    check("shortcut: fires outside text fields", fired, "Alt+Shift+F on the page body")
    if fired:
        page.click("#sightline-view .sl-cancel")
    ctx.close()


def cap_check(browser, args, out):
    ctx, page = open_app(browser, args.base, viewport=(1600, 1000), dpr=2)
    before_files = set(finding_files(args.findings_dir))
    page.click("#sightlineButton")
    wait_view(page)
    size = page.evaluate("(() => { const c = document.querySelector('#sightline-view canvas'); return [c.width, c.height]; })()")
    choose(page, "kind", "number"); choose(page, "colour", "blue")
    x, y = canvas_point(page, 400, 400)
    page.mouse.click(x, y)
    page.click("#sightline-view .sl-file")
    page.wait_for_function("window.__sightlineLastFiled", timeout=30000)
    stem = page.evaluate("window.__sightlineLastFiled")
    side = json.loads((Path(args.findings_dir) / (stem + ".json")).read_text(encoding="utf-8"))
    png = Image.open(Path(args.findings_dir) / (stem + ".png"))
    check("size cap: a 3200x2000 capture is filed at <= 2560 px", max(png.size) == 2560 and side["image"]["capturedWidth"] == 3200,
          f"captured {side['image']['capturedWidth']}x{side['image']['capturedHeight']}, filed {png.size}, view canvas {size}")
    check("size cap: one number mark, filed with an empty note (no accidental-click guard needed)", len(side["marks"]) == 1, side["marks"])
    ctx.close()


def empty_guard_check(browser, args, out):
    ctx, page = open_app(browser, args.base)
    before_files = set(finding_files(args.findings_dir))
    page.click("#sightlineButton")
    wait_view(page)
    page.click("#sightline-view .sl-file")
    page.wait_for_timeout(1500)
    still = page.evaluate("Sightline.isViewVisible()")
    msg = page.inner_text("#sightline-view .sl-message")
    check("empty finding: one click does not file", still and set(finding_files(args.findings_dir)) == before_files, msg)
    page.click("#sightline-view .sl-file")
    page.wait_for_function("window.__sightlineLastFiled", timeout=30000)
    check("empty finding: a second click files it", len(set(finding_files(args.findings_dir)) - before_files) == 3,
          page.evaluate("window.__sightlineLastFiled"))
    ctx.close()


def deny_check(pw, args, out):
    # A real browser-level denial: the page is served with "Permissions-Policy: display-capture=()", so Chrome
    # rejects getDisplayMedia with NotAllowedError. (Headless Chrome without the fake-UI flags never answers the
    # permission prompt at all, and CDP's Browser.setPermission does not cover display capture.)
    browser = pw.chromium.launch(channel="chrome", headless=True)
    ctx = browser.new_context(viewport={"width": 1600, "height": 1000})

    def deny_policy(route):
        resp = route.fetch()
        route.fulfill(response=resp, headers={**resp.headers, "permissions-policy": "display-capture=()"})

    ctx.route(lambda url: url.rstrip("/") == args.base.rstrip("/"), deny_policy)
    page = ctx.new_page()
    page.goto(args.base, wait_until="load", timeout=60000)
    page.wait_for_function("typeof initialized !== 'undefined' && initialized && window.Sightline", timeout=30000)
    page.wait_for_timeout(1500)
    before_files = set(finding_files(args.findings_dir))
    page.click("#sightlineButton")
    page.wait_for_selector("#sightline-fallback", timeout=15000)
    text = page.inner_text("#sightline-fallback")
    check("deny: the fallback in use is named on screen, with the reason", "Fallback 1 of 2 in use" in text and "NotAllowedError" in text,
          text.replace("\n", " | ")[:220])
    page.click("#sightline-fallback .sl-map")
    wait_view(page)
    banner = page.inner_text("#sightline-view .sl-banner")
    choose(page, "kind", "box"); choose(page, "colour", "red")
    drag(page, [[200, 200], [320, 300]])
    page.fill("#sightline-view .sl-note", "e2e: capture denied, map-only fallback")
    page.click("#sightline-view .sl-file")
    page.wait_for_function("window.__sightlineLastFiled", timeout=30000)
    stem = page.evaluate("window.__sightlineLastFiled")
    side = json.loads((Path(args.findings_dir) / (stem + ".json")).read_text(encoding="utf-8"))
    png = Image.open(Path(args.findings_dir) / (stem + ".png")).convert("RGB")
    label_box = png.crop((0, 0, 200, 20))
    yellow = sum(1 for x in range(label_box.width) for y in range(label_box.height) for px in [label_box.getpixel((x, y))] if px[0] > 200 and px[1] > 170 and px[2] < 80)
    check("deny: filed finding marked incomplete in the sidecar", side["complete"] is False and side["source"]["name"] == "map"
          and side["source"]["step"] == 2, {"complete": side["complete"], "source": side["source"]})
    check("deny: and in the image (burned label)", yellow > 50 and "INCOMPLETE" in banner, f"yellow label pixels={yellow}; banner={banner[:120]}")
    png.save(out / "deny_filed.png")
    ctx.close()
    browser.close()


def server_down_check(browser, args, out):
    ctx, page = open_app(browser, args.base)
    page.click("#sightlineButton")
    wait_view(page)
    choose(page, "kind", "freehand"); choose(page, "colour", "yellow")
    drag(page, [[300, 300], [360, 320], [420, 380], [380, 440]])
    subprocess.run(args.stop_server_cmd, shell=True, check=False)
    time.sleep(3)
    with page.expect_download(timeout=5000) as dl_info:
        page.click("#sightline-view .sl-file")
        page.wait_for_selector("#sightline-view .sl-failure", timeout=15000)
        text = page.inner_text("#sightline-view .sl-failure")
        page.click("#sightline-view .sl-download")
    dl = dl_info.value
    target = out / dl.suggested_filename
    dl.save_as(target)
    img = Image.open(target)
    check("server down: failure named and annotated image offered for download",
          "Filing failed" in text and "unreachable" in text and img.size[0] > 100,
          f"{text[:140]} | downloaded {dl.suggested_filename} {img.size}")
    ctx.close()


def open_in_app_check(browser, args, out):
    """The findings page lists the finding; "Open in app" rebuilds the captured state through the app's load path."""
    stem = (out / "main_stem.txt").read_text().strip()
    side = json.loads((Path(args.findings_dir) / (stem + ".json")).read_text(encoding="utf-8"))
    ctx = browser.new_context(viewport={"width": 1600, "height": 1000})
    page = ctx.new_page()
    page.goto(args.base.rstrip("/") + "/sightline", wait_until="load")
    page.wait_for_selector(f".finding[data-name='{stem}']", timeout=15000)
    first = page.evaluate("document.querySelector('.finding').dataset.name")
    page.click(f".finding[data-name='{stem}'] .sl-open")
    page.wait_for_function("window.__sightlineOpened", timeout=30000)
    page.wait_for_timeout(1000)
    shown = page.evaluate("""() => ({score: $('#score').text(), driving: $('#drivingTime').text(),
        rows: $('#vehicles tr').map(function () { return $(this).find('.progress-bar').text().trim(); }).get(),
        stop: $('#stopSolvingButton').is(':visible')})""")
    want_rows = [v["load"] for v in side["display"]["vehicles"]]
    check("open in app: findings page lists it and reloads the captured state",
          shown["score"] == side["display"]["score"] and shown["driving"] == side["display"]["drivingTime"]
          and shown["rows"] == want_rows and not shown["stop"],
          f"first listed={first}; shown score={shown['score']} driving={shown['driving']} loads={shown['rows']}")
    page.screenshot(path=str(out / "open_in_app.png"))
    ctx.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--findings-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="guard,main,clock,shortcut,open,cap,empty,deny")
    ap.add_argument("--stop-server-cmd", default=None)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    only = set(args.only.split(","))
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome", headless=True, args=FAKE_CAPTURE)
        steps = [("guard", guard_check), ("main", main_finding), ("clock", clock_check), ("shortcut", shortcut_check),
                 ("open", open_in_app_check), ("cap", cap_check), ("empty", empty_guard_check)]
        for name, fn in steps:
            if name in only:
                try:
                    fn(browser, args, out)
                except Exception as e:
                    check(f"{name}: ran without error", False, repr(e)[:400])
        if "deny" in only:
            try:
                deny_check(pw, args, out)
            except Exception as e:
                check("deny: ran without error", False, repr(e)[:400])
        if args.stop_server_cmd:
            try:
                server_down_check(browser, args, out)
            except Exception as e:
                check("server down: ran without error", False, repr(e)[:400])
        browser.close()
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
