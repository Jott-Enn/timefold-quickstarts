/*
 * Sightline: in-app findings for the vehicle routing quickstart (dev and test builds only; served by
 * SightlineResource, which does not exist in a production build).
 *
 * Press the Finding button (or Alt+Shift+F outside text fields): the app clock freezes, the tab is captured
 * with getDisplayMedia, then the annotation view opens on the captured frame. Marks are data
 * ({n, kind, colour, width, points} in image coordinates), burned into the PNG and resolved against the
 * app's own model (route plan + Leaflet projection + vis-timeline items) to domain object ids.
 *
 * Relies on app.js globals (classic scripts share the global lexical scope): AppClock, map, loadedRoutePlan,
 * visitMarkerByIdMap, homeLocationMarkerByIdMap, routeGroup, byVehicleTimeline, byVisitTimeline,
 * demoDataId, scheduleId, optimizing, initialized, applyRoutePlan, colorByVehicle and the layer groups.
 */
(function () {
    'use strict';

    const MAX_EDGE = 2560;
    const FRAME_TIMEOUT_MS = 4000;
    const JOURNAL_KEY = 'sightline.journal.v1';
    const JOURNAL_MAX = 200;
    const COLOURS = [
        {name: 'red', value: '#e53935', halo: '#ffffff'},
        {name: 'green', value: '#2e7d32', halo: '#ffffff'},
        {name: 'blue', value: '#1e63d6', halo: '#ffffff'},
        {name: 'yellow', value: '#ffd600', halo: '#000000'},
        {name: 'white', value: '#ffffff', halo: '#000000'},
    ];
    const KINDS = ['ellipse', 'box', 'line', 'freehand', 'arrow', 'number'];
    const KIND_LABELS = {ellipse: 'Circle', box: 'Box', line: 'Line', freehand: 'Freehand', arrow: 'Arrow', number: 'Number'};
    const RESOLVABLE = 'all six kinds (ellipse/box enclose, line/freehand cross or enclose when closed, arrow points at, number labels)';

    class CaptureRefused extends Error {}
    class CaptureUnavailable extends Error {}

    /******************************** action journal ********************************/

    let journal = [];
    try { journal = JSON.parse(localStorage.getItem(JOURNAL_KEY) || '[]'); } catch (e) { journal = []; }
    if (!Array.isArray(journal)) journal = [];
    journal.push({t: Date.now(), type: 'page-load', url: location.pathname + location.search});

    function jpush(entry) {
        entry.t = entry.t || Date.now();
        journal.push(entry);
        while (journal.length > JOURNAL_MAX) journal.shift();
        try { localStorage.setItem(JOURNAL_KEY, JSON.stringify(journal)); } catch (e) { /* quota: keep in memory */ }
    }

    const ownUi = el => el && el.closest && el.closest('[data-sightline]');

    function describe(el) {
        if (!el || !el.tagName) return String(el);
        let d = el.tagName.toLowerCase();
        if (el.id) d += '#' + el.id;
        const text = (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ');
        if (text) d += ' "' + text.slice(0, 40) + '"';
        return d;
    }

    function mapViewInfo() {
        try { const c = map.getCenter(); return {center: [c.lat, c.lng], zoom: map.getZoom()}; } catch (e) { return null; }
    }

    document.addEventListener('click', ev => {
        if (ownUi(ev.target)) return;
        const el = ev.target.closest('button, a, [role=button], [role=tab], .dropdown-item, input[type=checkbox], input[type=radio], label');
        if (el) {
            const entry = {t: Date.now(), type: 'click', target: describe(el)};
            jpush(entry);
            setTimeout(() => { // the label the click resulted in, once the click's synchronous effects settled
                entry.resultingLabel = (el.innerText || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 60);
                entry.visibleAfter = el.offsetParent !== null;
                try { localStorage.setItem(JOURNAL_KEY, JSON.stringify(journal)); } catch (e) { /* ignore */ }
            }, 400);
        } else if (ev.target.closest('#map')) {
            let latlng = null;
            try { const ll = map.mouseEventToLatLng(ev); latlng = [ll.lat, ll.lng]; } catch (e) { /* ignore */ }
            jpush({t: Date.now(), type: 'click', target: 'map', latlng});
        }
    }, true);

    document.addEventListener('change', ev => {
        const el = ev.target;
        if (ownUi(el) || !el.matches || !el.matches('input, select, textarea')) return;
        const value = el.type === 'password' ? '***' : String(el.type === 'checkbox' ? el.checked : el.value).slice(0, 120);
        jpush({t: Date.now(), type: 'change', target: describe(el), value});
    }, true);

    let wheelGesture = null;
    document.addEventListener('wheel', ev => {
        if (ownUi(ev.target)) return;
        if (!wheelGesture) wheelGesture = {start: Date.now(), deltaY: 0, target: describe(ev.target.closest('[id]') || ev.target), onMap: !!ev.target.closest('#map')};
        wheelGesture.deltaY += ev.deltaY;
        clearTimeout(wheelGesture.timer);
        wheelGesture.timer = setTimeout(() => {
            const g = wheelGesture; wheelGesture = null;
            jpush({t: g.start, type: 'zoom', target: g.target, deltaY: Math.round(g.deltaY), durationMs: Date.now() - g.start,
                after: g.onMap ? mapViewInfo() : null});
        }, 350);
    }, {capture: true, passive: true});

    let drag = null;
    document.addEventListener('pointerdown', ev => {
        if (ownUi(ev.target)) return;
        drag = {x: ev.clientX, y: ev.clientY, start: Date.now(), target: describe(ev.target.closest('[id]') || ev.target), onMap: !!ev.target.closest('#map')};
    }, true);
    document.addEventListener('pointerup', ev => {
        if (!drag) return;
        const dx = ev.clientX - drag.x, dy = ev.clientY - drag.y;
        if (Math.hypot(dx, dy) > 5) {
            const d = drag;
            setTimeout(() => jpush({t: d.start, type: 'drag', target: d.target, dx: Math.round(dx), dy: Math.round(dy),
                durationMs: Date.now() - d.start, after: d.onMap ? mapViewInfo() : null}), 50);
        }
        drag = null;
    }, true);

    /******************************** state, display and scene snapshots ********************************/

    function activeTab() {
        if ($('#mapPanel').hasClass('active')) return 'map';
        if ($('#byVehiclePanel').hasClass('active')) return 'byVehicle';
        if ($('#byVisitPanel').hasClass('active')) return 'byVisit';
        return 'other';
    }

    function activeTopTab() {
        const el = document.querySelector('#navbarNav .nav-link.active');
        return el ? el.id : null;
    }

    function openModal() {
        const m = document.querySelector('.modal.show');
        return m ? m.id : null;
    }

    function timelineWindow(tl) {
        try { const w = tl.getWindow(); return [w.start.toISOString(), w.end.toISOString()]; } catch (e) { return null; }
    }

    function snapshotState() {
        return {
            format: 'VehicleRoutePlan JSON exactly as the app loads it (GET /demo-data/{id} or GET /route-plans/{jobId}) '
                + 'and posts it to solve; passed through opaque',
            routePlan: loadedRoutePlan ? JSON.parse(JSON.stringify(loadedRoutePlan)) : null,
            demoDataId: typeof demoDataId === 'undefined' ? null : demoDataId,
            scheduleId: typeof scheduleId === 'undefined' ? null : scheduleId,
            solving: typeof optimizing === 'undefined' ? null : optimizing,
            view: {
                topTab: activeTopTab(),
                tab: activeTab(),
                map: mapViewInfo(),
                byVehicleWindow: timelineWindow(byVehicleTimeline),
                byVisitWindow: timelineWindow(byVisitTimeline),
                openModal: openModal(),
            },
        };
    }

    function snapshotDisplay() {
        const vehicles = [];
        $('#vehicles tr').each(function () {
            const home = this.querySelector('[id^=home-]');
            const tds = this.querySelectorAll('td');
            vehicles.push({
                id: home ? home.id.substring(5) : null,
                load: (this.querySelector('.progress-bar') || {}).innerText?.trim() ?? null,
                drivingTime: tds.length > 3 ? tds[3].innerText.trim() : null,
            });
        });
        const visits = [];
        if (typeof visitMarkerByIdMap !== 'undefined') {
            for (const [id, marker] of visitMarkerByIdMap) {
                const ll = marker.getLatLng();
                visits.push({id: String(id), latlng: [ll.lat, ll.lng], colour: marker.options.color});
            }
        }
        const homes = [];
        if (typeof homeLocationMarkerByIdMap !== 'undefined') {
            for (const [id, marker] of homeLocationMarkerByIdMap) {
                const ll = marker.getLatLng();
                homes.push({id: String(id), latlng: [ll.lat, ll.lng]});
            }
        }
        const routes = [];
        if (typeof routeGroup !== 'undefined') {
            routeGroup.eachLayer(layer => {
                if (layer.getLatLngs) routes.push({colour: layer.options.color, latlngs: layer.getLatLngs().map(ll => [ll.lat, ll.lng])});
            });
        }
        return {
            score: $('#score').text(),
            drivingTime: $('#drivingTime').text(),
            info: $('#info').text(),
            solveButtonVisible: $('#solveButton').is(':visible'),
            stopButtonVisible: $('#stopSolvingButton').is(':visible'),
            vehicles, visits, homes, routes,
        };
    }

    // Domain objects with their on-screen geometry (CSS px, viewport-relative), from the app's own model.
    function snapshotScene() {
        const objects = [];
        const plan = loadedRoutePlan;
        if (!plan) return objects;
        const visible = r => r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
        const tab = activeTab();
        if (tab === 'map' && !openModal()) {
            const mr = document.getElementById('map').getBoundingClientRect();
            const inMap = (x, y) => x >= mr.left && x <= mr.right && y >= mr.top && y <= mr.bottom;
            const pt = latlng => { const p = map.latLngToContainerPoint(latlng); return [mr.left + p.x, mr.top + p.y]; };
            const visitById = new Map(plan.visits.map(v => [String(v.id), v]));
            for (const visit of plan.visits) {
                const marker = visitMarkerByIdMap.get(visit.id);
                if (!marker) continue;
                const [x, y] = pt(marker.getLatLng());
                if (inMap(x, y)) objects.push({id: 'visit:' + visit.id, kind: 'visit-marker', shape: 'circle', x, y, r: marker.getRadius ? marker.getRadius() : 10});
            }
            for (const vehicle of plan.vehicles) {
                const [x, y] = pt(vehicle.homeLocation);
                if (inMap(x, y)) objects.push({id: 'vehicle:' + vehicle.id, kind: 'vehicle-home', shape: 'circle', x, y, r: 10});
                const stops = [vehicle.homeLocation, ...vehicle.visits.map(id => visitById.get(String(id))?.location).filter(Boolean), vehicle.homeLocation];
                for (let i = 0; i + 1 < stops.length; i++) {
                    const [x1, y1] = pt(stops[i]);
                    const [x2, y2] = pt(stops[i + 1]);
                    if (inMap(x1, y1) || inMap(x2, y2)) objects.push({id: `leg:${vehicle.id}:${i}`, kind: 'route-leg', shape: 'segment', x1, y1, x2, y2});
                }
            }
            $('#vehicles tr').each(function () {
                const home = this.querySelector('[id^=home-]');
                const r = this.getBoundingClientRect();
                if (home && visible(r)) objects.push({id: 'vehicle:' + home.id.substring(5), kind: 'vehicle-row', shape: 'rect', x: r.left, y: r.top, w: r.width, h: r.height});
            });
            const s = document.getElementById('score').getBoundingClientRect();
            if (visible(s)) objects.push({id: 'score', kind: 'score', shape: 'rect', x: s.left, y: s.top, w: s.width, h: s.height});
        }
        if ((tab === 'byVehicle' || tab === 'byVisit') && !openModal()) {
            const tl = tab === 'byVehicle' ? byVehicleTimeline : byVisitTimeline;
            const legIndex = visitId => {
                for (const v of plan.vehicles) {
                    const i = v.visits.map(String).indexOf(String(visitId));
                    if (i >= 0) return `leg:${v.id}:${i}`;
                }
                return null;
            };
            const items = (tl.itemSet && tl.itemSet.items) || {};
            for (const key of Object.keys(items)) {
                const item = items[key];
                const el = item.dom && (item.dom.box || item.dom.point);
                if (!el) continue;
                const r = el.getBoundingClientRect();
                if (!visible(r)) continue;
                const raw = String(item.id);
                let id = null, kind = 'timeline-item';
                let m;
                if ((m = raw.match(/^(.+)_service$/))) { id = 'visit:' + m[1]; kind = 'timeline-service'; }
                else if ((m = raw.match(/^(.+)_wait$/))) { id = 'visit:' + m[1]; kind = 'timeline-wait'; }
                else if ((m = raw.match(/^(.+)_travel$/))) { id = legIndex(m[1]); kind = 'timeline-travel'; }
                else if ((m = raw.match(/^(.+)_travelBackToHomeLocation$/))) {
                    const v = plan.vehicles.find(v => String(v.id) === m[1]);
                    id = v ? `leg:${v.id}:${v.visits.length}` : null; kind = 'timeline-travel-home';
                }
                else if ((m = raw.match(/^(.+)_readyToDue$/))) { id = 'visit:' + m[1]; kind = 'timeline-time-window'; }
                else if ((m = raw.match(/^(.+)_unassigned$/))) { id = 'visit:' + m[1]; kind = 'timeline-unassigned'; }
                else { id = 'visit:' + raw; kind = 'timeline-assignment'; }
                if (id) objects.push({id, kind, shape: 'rect', x: r.left, y: r.top, w: r.width, h: r.height});
            }
            const groups = (tl.itemSet && tl.itemSet.groups) || {};
            for (const gid of Object.keys(groups)) {
                const label = groups[gid].dom && groups[gid].dom.label;
                if (!label) continue;
                const r = label.getBoundingClientRect();
                if (!visible(r)) continue;
                objects.push({id: (tab === 'byVehicle' ? 'vehicle:' : 'visit:') + gid, kind: 'timeline-row-label', shape: 'rect', x: r.left, y: r.top, w: r.width, h: r.height});
            }
        }
        return objects;
    }

    function transformScene(objects, scale, offsetX, offsetY) {
        const tx = x => (x - offsetX) * scale, ty = y => (y - offsetY) * scale;
        return objects.map(o => {
            const c = {...o};
            if (o.shape === 'segment') { c.x1 = tx(o.x1); c.y1 = ty(o.y1); c.x2 = tx(o.x2); c.y2 = ty(o.y2); }
            else { c.x = tx(o.x); c.y = ty(o.y); }
            if (o.r != null) c.r = o.r * scale;
            if (o.w != null) { c.w = o.w * scale; c.h = o.h * scale; }
            for (const k of ['x', 'y', 'x1', 'y1', 'x2', 'y2', 'r', 'w', 'h']) if (c[k] != null) c[k] = Math.round(c[k] * 10) / 10;
            return c;
        });
    }

    /******************************** geometry / resolution ********************************/

    const dist = (ax, ay, bx, by) => Math.hypot(ax - bx, ay - by);
    function distPointSeg(px, py, x1, y1, x2, y2) {
        const dx = x2 - x1, dy = y2 - y1, l2 = dx * dx + dy * dy;
        let t = l2 ? ((px - x1) * dx + (py - y1) * dy) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        return dist(px, py, x1 + t * dx, y1 + t * dy);
    }
    function segsIntersect(a, b, c, d) {
        const o = (p, q, r) => Math.sign((q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]));
        return o(a, b, c) !== o(a, b, d) && o(c, d, a) !== o(c, d, b);
    }
    function distSegSeg(a, b, c, d) {
        if (segsIntersect(a, b, c, d)) return 0;
        return Math.min(distPointSeg(a[0], a[1], c[0], c[1], d[0], d[1]), distPointSeg(b[0], b[1], c[0], c[1], d[0], d[1]),
            distPointSeg(c[0], c[1], a[0], a[1], b[0], b[1]), distPointSeg(d[0], d[1], a[0], a[1], b[0], b[1]));
    }
    function pointInPolygon(x, y, pts) {
        let inside = false;
        for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
            const [xi, yi] = pts[i], [xj, yj] = pts[j];
            if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }
    const center = o => o.shape === 'segment' ? [(o.x1 + o.x2) / 2, (o.y1 + o.y2) / 2]
        : o.shape === 'rect' ? [o.x + o.w / 2, o.y + o.h / 2] : [o.x, o.y];

    function distToPolyline(o, pts) {
        const segs = pts.length === 1 ? [[pts[0], pts[0]]] : pts.slice(1).map((p, i) => [pts[i], p]);
        let best = Infinity;
        for (const [a, b] of segs) {
            let d;
            if (o.shape === 'segment') d = distSegSeg(a, b, [o.x1, o.y1], [o.x2, o.y2]);
            else if (o.shape === 'rect') {
                const inside = p => p[0] >= o.x && p[0] <= o.x + o.w && p[1] >= o.y && p[1] <= o.y + o.h;
                if (inside(a) || inside(b)) d = 0;
                else {
                    const c = [[o.x, o.y], [o.x + o.w, o.y], [o.x + o.w, o.y + o.h], [o.x, o.y + o.h]];
                    d = Math.min(...c.map((p, i) => distSegSeg(a, b, p, c[(i + 1) % 4])));
                }
            } else d = Math.max(0, distPointSeg(o.x, o.y, a[0], a[1], b[0], b[1]) - (o.r || 0));
            best = Math.min(best, d);
        }
        return best;
    }

    // Which domain objects a mark encloses, crosses or points at (image coordinates).
    function resolveMark(mark, scene, imageScale) {
        if (!scene || !scene.length) return [];
        const pts = mark.points;
        const near = 40 * Math.max(1, imageScale);
        const tol = mark.width / 2 + 6 * Math.max(1, imageScale);
        const hits = [];
        const add = (o, relation, d) => hits.push({id: o.id, relation, via: o.kind, distance: Math.round(d * 10) / 10});
        const enclosing = test => {
            for (const o of scene) {
                if (o.shape === 'segment') {
                    if (test(o.x1, o.y1) && test(o.x2, o.y2)) add(o, 'encloses', 0);
                } else {
                    const [cx, cy] = center(o);
                    if (test(cx, cy)) add(o, 'encloses', 0);
                }
            }
        };
        if (mark.kind === 'ellipse' || mark.kind === 'box') {
            const [[x1, y1], [x2, y2]] = pts;
            const minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            if (mark.kind === 'box') enclosing((x, y) => x >= minX && x <= maxX && y >= minY && y <= maxY);
            else {
                const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, rx = Math.max(1, (maxX - minX) / 2), ry = Math.max(1, (maxY - minY) / 2);
                enclosing((x, y) => ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1);
            }
        } else if (mark.kind === 'line' || mark.kind === 'freehand') {
            const closed = mark.kind === 'freehand' && pts.length > 8 && (() => {
                const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
                const diag = dist(Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys));
                return dist(pts[0][0], pts[0][1], pts[pts.length - 1][0], pts[pts.length - 1][1]) < Math.max(20, diag * 0.2);
            })();
            if (closed) enclosing((x, y) => pointInPolygon(x, y, pts));
            const enclosed = new Set(hits.map(h => h.id + '|' + h.via));
            for (const o of scene) {
                if (enclosed.has(o.id + '|' + o.kind)) continue;
                const d = distToPolyline(o, pts);
                if (d <= tol) add(o, 'crosses', d);
            }
        } else if (mark.kind === 'arrow' || mark.kind === 'number') {
            const [hx, hy] = mark.kind === 'arrow' ? pts[1] : pts[0];
            let best = null;
            for (const pass of [o => o.shape !== 'segment', o => o.shape === 'segment']) {
                for (const o of scene) {
                    if (!pass(o)) continue;
                    let d;
                    if (o.shape === 'segment') d = distPointSeg(hx, hy, o.x1, o.y1, o.x2, o.y2);
                    else if (o.shape === 'rect') d = (hx >= o.x && hx <= o.x + o.w && hy >= o.y && hy <= o.y + o.h) ? 0
                        : Math.min(distPointSeg(hx, hy, o.x, o.y, o.x + o.w, o.y), distPointSeg(hx, hy, o.x, o.y + o.h, o.x + o.w, o.y + o.h),
                            distPointSeg(hx, hy, o.x, o.y, o.x, o.y + o.h), distPointSeg(hx, hy, o.x + o.w, o.y, o.x + o.w, o.y + o.h));
                    else d = Math.max(0, dist(hx, hy, o.x, o.y) - (o.r || 0));
                    if (d <= near && (!best || d < best.d)) best = {o, d};
                }
                if (best) break;
            }
            if (best) add(best.o, mark.kind === 'arrow' ? 'points-at' : 'labels', best.d);
        }
        // one entry per id, closest first
        hits.sort((a, b) => a.distance - b.distance);
        const seen = new Set();
        return hits.filter(h => !seen.has(h.id) && seen.add(h.id)).slice(0, 50);
    }

    /******************************** capture ********************************/

    function isViewVisible() {
        return !!(view && view.root && view.root.style.display !== 'none');
    }

    // Real pixels of this tab via the screen-capture API: one frame, then every track is stopped.
    async function capture() {
        if (isViewVisible()) {
            throw new CaptureRefused('capture() refused: the annotation view is visible. Capture first, then open the view.');
        }
        if (!window.isSecureContext) throw new CaptureUnavailable('insecure context (page is not https or localhost)');
        if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) throw new CaptureUnavailable('this browser has no getDisplayMedia');
        let stream;
        try {
            stream = await navigator.mediaDevices.getDisplayMedia({
                video: {displaySurface: 'browser'}, // no frameRate constraint: never throttle the stream
                audio: false,
                preferCurrentTab: true,
                selfBrowserSurface: 'include',
                surfaceSwitching: 'exclude',
                monitorTypeSurfaces: 'exclude',
            });
        } catch (e) {
            throw new CaptureUnavailable(`${e.name || 'Error'}: ${e.message || e}`);
        }
        try {
            const track = stream.getVideoTracks()[0];
            const settings = track ? track.getSettings() : {};
            const video = document.createElement('video');
            video.muted = true;
            video.playsInline = true;
            video.srcObject = stream;
            await video.play().catch(() => {});
            await firstFrame(video, FRAME_TIMEOUT_MS);
            const c = document.createElement('canvas');
            c.width = video.videoWidth;
            c.height = video.videoHeight;
            c.getContext('2d').drawImage(video, 0, 0);
            video.srcObject = null;
            return {canvas: c, surface: settings.displaySurface || null};
        } finally {
            stream.getTracks().forEach(t => t.stop());
        }
    }

    // Tab capture is damage-driven: a page that does not repaint may deliver no frame at all. While waiting,
    // a 1x1 px element alternates between alpha 0 and 1/255, which forces a repaint without visibly changing
    // anything (at most one pixel differs by 1/255). It is removed as soon as the first frame arrives.
    function firstFrame(video, timeoutMs) {
        const nudge = document.createElement('div');
        nudge.setAttribute('data-sightline', 'repaint-nudge');
        nudge.style.cssText = 'position:fixed;right:0;bottom:0;width:1px;height:1px;pointer-events:none;z-index:2147483000;background:rgba(0,0,0,0)';
        document.body.appendChild(nudge);
        let flip = false, raf = 0;
        const tick = () => { flip = !flip; nudge.style.background = flip ? 'rgba(0,0,0,0.004)' : 'rgba(0,0,0,0)'; raf = requestAnimationFrame(tick); };
        raf = requestAnimationFrame(tick);
        const cleanup = () => { cancelAnimationFrame(raf); nudge.remove(); };
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => { cleanup(); reject(new CaptureUnavailable(`the capture stream delivered no frame within ${timeoutMs / 1000} s`)); }, timeoutMs);
            const done = () => {
                if (video.videoWidth > 0) { clearTimeout(timer); cleanup(); resolve(); }
                else if (video.requestVideoFrameCallback) video.requestVideoFrameCallback(done);
                else setTimeout(done, 30);
            };
            if (video.requestVideoFrameCallback) video.requestVideoFrameCallback(done);
            else video.addEventListener('loadeddata', done, {once: true});
        });
    }

    // Fallback 2: the main canvas alone. Leaflet's map is tile images plus an SVG overlay; both are composited
    // from the live elements (tile pixels are real; the vector overlay is rasterised by the browser).
    async function captureMapCanvas() {
        const mapEl = document.getElementById('map');
        const r = mapEl.getBoundingClientRect();
        if (mapEl.offsetParent === null || r.width < 10 || r.height < 10) {
            throw new CaptureUnavailable('the map is not visible in the current tab');
        }
        const dpr = window.devicePixelRatio || 1;
        const c = document.createElement('canvas');
        c.width = Math.round(r.width * dpr);
        c.height = Math.round(r.height * dpr);
        const g = c.getContext('2d');
        g.scale(dpr, dpr);
        g.fillStyle = '#dddddd';
        g.fillRect(0, 0, r.width, r.height);
        for (const img of mapEl.querySelectorAll('.leaflet-tile-pane img')) {
            if (!img.complete || !img.naturalWidth) continue;
            const ir = img.getBoundingClientRect();
            g.drawImage(img, ir.left - r.left, ir.top - r.top, ir.width, ir.height);
        }
        const svg = mapEl.querySelector('.leaflet-overlay-pane svg');
        if (svg) {
            const sr = svg.getBoundingClientRect();
            const clone = svg.cloneNode(true);
            clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
            clone.setAttribute('width', sr.width);
            clone.setAttribute('height', sr.height);
            clone.removeAttribute('style');
            const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(clone)], {type: 'image/svg+xml'}));
            try {
                const img = new Image();
                await new Promise((res, rej) => { img.onload = res; img.onerror = () => rej(new Error('overlay did not rasterise')); img.src = url; });
                g.drawImage(img, sr.left - r.left, sr.top - r.top, sr.width, sr.height);
            } finally { URL.revokeObjectURL(url); }
        }
        try { c.toDataURL('image/png').length; } catch (e) {
            throw new CaptureUnavailable('map tiles are cross-origin without CORS, the map pixels cannot be read');
        }
        return {canvas: c, offsetX: r.left, offsetY: r.top, cssScale: dpr};
    }

    function capCanvas(src) {
        const longest = Math.max(src.width, src.height);
        if (longest <= MAX_EDGE) return {canvas: src, factor: 1};
        const factor = MAX_EDGE / longest;
        const c = document.createElement('canvas');
        c.width = Math.round(src.width * factor);
        c.height = Math.round(src.height * factor);
        const g = c.getContext('2d');
        g.imageSmoothingQuality = 'high';
        g.drawImage(src, 0, 0, c.width, c.height);
        return {canvas: c, factor};
    }

    function burnIncompleteLabel(canvas, text) {
        const g = canvas.getContext('2d');
        const size = Math.max(14, Math.round(canvas.width / 70));
        g.font = `bold ${size}px sans-serif`;
        const lines = ['INCOMPLETE CAPTURE', text];
        const w = Math.max(...lines.map(l => g.measureText(l).width)) + size;
        g.fillStyle = 'rgba(0,0,0,0.78)';
        g.fillRect(0, 0, w, size * 2.9);
        g.fillStyle = '#ffd600';
        lines.forEach((l, i) => g.fillText(l, size / 2, size * (1.2 + i * 1.3)));
    }

    /******************************** finding flow ********************************/

    let busy = false;
    let ctx = null; // the finding being made
    window.__sightlineLast = null;

    async function startFinding() {
        if (busy || isViewVisible()) return;
        busy = true;
        const clockAtFreeze = AppClock.freeze();
        ctx = {
            clockAtFreeze,
            capturedAt: new Date().toISOString(),
            state: snapshotState(),
            display: snapshotDisplay(),
            sceneCss: snapshotScene(),
            journal: journal.slice(),
            page: {url: location.href, userAgent: navigator.userAgent, viewport: [innerWidth, innerHeight],
                devicePixelRatio: window.devicePixelRatio, appClockAtFreeze: clockAtFreeze},
        };
        let frame = null;
        try {
            // Capture FIRST, then open the view; capture() refuses to run while the view is visible.
            const shot = await capture();
            frame = prepareFrame(shot.canvas, {name: 'page', method: 'getDisplayMedia', step: 0, surface: shot.surface},
                shot.surface === 'browser' || shot.surface == null ? {offsetX: 0, offsetY: 0, cssScale: shot.canvas.width / innerWidth} : null, true);
        } catch (e) {
            if (e instanceof CaptureRefused) {
                endFinding();
                showToast(e.message, true);
                throw e;
            }
            frame = await fallbackLadder(e.message || String(e));
        }
        if (!frame) { endFinding(); return; }
        openView(frame);
    }

    function prepareFrame(canvas, source, mapping, complete, label) {
        const capped = capCanvas(canvas);
        if (label) burnIncompleteLabel(capped.canvas, label);
        const imageScale = mapping ? mapping.cssScale * capped.factor : null;
        const scene = mapping ? transformScene(ctx.sceneCss, imageScale, mapping.offsetX, mapping.offsetY) : [];
        return {
            canvas: capped.canvas, source, complete, scene, imageScale,
            capturedSize: [canvas.width, canvas.height], cappedFactor: capped.factor,
        };
    }

    // Named fallback ladder: 1. paste an image from the clipboard, 2. the map canvas alone. Always says which
    // step is in use and why; Cancel is always available.
    function fallbackLadder(reason) {
        return new Promise(resolve => {
            const panel = document.createElement('div');
            panel.setAttribute('data-sightline', 'fallback');
            panel.id = 'sightline-fallback';
            panel.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);z-index:2147483000;max-width:760px;'
                + 'background:#fff8e1;border:2px solid #f9a825;border-radius:8px;padding:12px 16px;font:14px sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.3)';
            panel.innerHTML = `<div style="font-weight:bold;margin-bottom:4px">Screen capture unavailable: <span class="sl-reason"></span></div>
                <div class="sl-step"><b>Fallback 1 of 2 in use: paste an image from the clipboard</b> (Ctrl+V / Cmd+V now).</div>
                <div class="sl-error" style="color:#b71c1c;margin-top:4px"></div>
                <div style="margin-top:8px;display:flex;gap:8px">
                  <button type="button" class="btn btn-sm btn-warning sl-map">Use fallback 2: map only (incomplete)</button>
                  <button type="button" class="btn btn-sm btn-outline-secondary sl-cancel">Cancel</button></div>`;
            panel.querySelector('.sl-reason').textContent = reason;
            document.body.appendChild(panel);
            const finish = frame => {
                document.removeEventListener('paste', onPaste, true);
                panel.remove();
                resolve(frame);
            };
            const onPaste = async ev => {
                const item = [...(ev.clipboardData ? ev.clipboardData.items : [])].find(i => i.type.startsWith('image/'));
                if (!item) { panel.querySelector('.sl-error').textContent = 'The clipboard holds no image.'; return; }
                ev.preventDefault();
                const bitmap = await createImageBitmap(item.getAsFile());
                const c = document.createElement('canvas');
                c.width = bitmap.width;
                c.height = bitmap.height;
                c.getContext('2d').drawImage(bitmap, 0, 0);
                finish(prepareFrame(c, {name: 'clipboard', method: 'paste', step: 1, fallbackReason: reason,
                    label: 'fallback 1: pasted clipboard image; marks cannot be resolved to objects (unknown geometry)'}, null, true));
            };
            document.addEventListener('paste', onPaste, true);
            panel.querySelector('.sl-map').addEventListener('click', async () => {
                try {
                    const shot = await captureMapCanvas();
                    const label = 'fallback 2: map only (tiles + routes + visits); sidebar, tabs and home icons not included';
                    finish(prepareFrame(shot.canvas, {name: 'map', method: 'map-composite', step: 2, fallbackReason: reason, label},
                        {offsetX: shot.offsetX, offsetY: shot.offsetY, cssScale: shot.cssScale}, false, label));
                } catch (e) {
                    panel.querySelector('.sl-step').innerHTML = '<b>Fallback 2 failed</b>; fallback 1 (paste an image) is still available.';
                    panel.querySelector('.sl-error').textContent = 'Map only: ' + (e.message || e);
                }
            });
            panel.querySelector('.sl-cancel').addEventListener('click', () => finish(null));
        });
    }

    function endFinding() {
        const clockAfterResume = AppClock.resume();
        window.__sightlineLast = {clockAtFreeze: ctx ? ctx.clockAtFreeze : null, clockAfterResume};
        busy = false;
        ctx = null;
    }

    /******************************** annotation view ********************************/

    let view = null;

    function buildView() {
        const root = document.createElement('div');
        root.id = 'sightline-view';
        root.setAttribute('data-sightline', 'view');
        root.style.cssText = 'position:fixed;inset:0;z-index:2147483001;background:#1b1b1f;display:none;flex-direction:column;font:14px sans-serif;color:#eee';
        root.innerHTML = `
          <div class="sl-toolbar" style="display:flex;flex-wrap:wrap;gap:6px;align-items:center;padding:8px 10px;background:#2b2b31">
            <span style="font-weight:bold;margin-right:6px">Finding</span>
            <span class="sl-kinds" style="display:flex;gap:4px"></span>
            <span style="width:1px;height:24px;background:#555"></span>
            <span class="sl-colours" style="display:flex;gap:4px"></span>
            <span style="width:1px;height:24px;background:#555"></span>
            <span class="sl-widths" style="display:flex;gap:4px"></span>
            <span style="width:1px;height:24px;background:#555"></span>
            <button type="button" class="btn btn-sm btn-outline-light sl-undo">Undo</button>
            <button type="button" class="btn btn-sm btn-outline-light sl-discard">Discard all</button>
            <input type="text" class="form-control form-control-sm sl-note" maxlength="2000" placeholder="Note (optional)" style="width:260px">
            <button type="button" class="btn btn-sm btn-danger sl-file">File finding</button>
            <button type="button" class="btn btn-sm btn-secondary sl-cancel">Cancel</button>
          </div>
          <div class="sl-banner" style="padding:4px 10px;background:#33333a;font-size:13px"></div>
          <div class="sl-message" style="padding:0 10px;font-size:13px;min-height:0"></div>
          <div class="sl-stage" style="flex:1;overflow:auto;display:flex;align-items:flex-start;justify-content:center;padding:8px">
            <canvas class="sl-canvas" style="cursor:crosshair;box-shadow:0 0 0 1px #666;touch-action:none"></canvas>
          </div>`;
        document.body.appendChild(root);
        const v = {
            root,
            canvas: root.querySelector('.sl-canvas'),
            note: root.querySelector('.sl-note'),
            banner: root.querySelector('.sl-banner'),
            message: root.querySelector('.sl-message'),
            kind: 'ellipse', colour: COLOURS[0], widthIndex: 1,
            marks: [], drawing: null, frame: null, armedEmpty: 0,
        };
        const kinds = root.querySelector('.sl-kinds');
        for (const k of KINDS) {
            const b = document.createElement('button');
            b.type = 'button'; b.className = 'btn btn-sm btn-outline-light sl-kind'; b.dataset.kind = k; b.textContent = KIND_LABELS[k];
            b.addEventListener('click', () => { v.kind = k; refreshToolbar(); });
            kinds.appendChild(b);
        }
        const colours = root.querySelector('.sl-colours');
        for (const c of COLOURS) {
            const b = document.createElement('button');
            b.type = 'button'; b.className = 'sl-colour'; b.dataset.colour = c.name; b.title = c.name;
            b.style.cssText = `width:24px;height:24px;border-radius:50%;background:${c.value};border:2px solid #888`;
            b.addEventListener('click', () => { v.colour = c; refreshToolbar(); });
            colours.appendChild(b);
        }
        const widths = root.querySelector('.sl-widths');
        ['thin', 'medium', 'thick'].forEach((name, i) => {
            const b = document.createElement('button');
            b.type = 'button'; b.className = 'btn btn-sm btn-outline-light sl-width'; b.dataset.width = name; b.textContent = name;
            b.addEventListener('click', () => { v.widthIndex = i; refreshToolbar(); });
            widths.appendChild(b);
        });
        root.querySelector('.sl-undo').addEventListener('click', () => { v.marks.pop(); redraw(); });
        root.querySelector('.sl-discard').addEventListener('click', async () => {
            if (!v.marks.length) return;
            if (await askConfirm(`Discard all ${v.marks.length} mark(s)?`, 'Discard')) { v.marks = []; redraw(); }
        });
        root.querySelector('.sl-file').addEventListener('click', fileFinding);
        root.querySelector('.sl-cancel').addEventListener('click', cancelView);
        root.addEventListener('keydown', ev => {
            if (ev.key === 'Escape' && !v.confirming) { ev.preventDefault(); cancelView(); }
            if ((ev.ctrlKey || ev.metaKey) && ev.key === 'z' && document.activeElement !== v.note) { ev.preventDefault(); v.marks.pop(); redraw(); }
        });
        v.canvas.addEventListener('pointerdown', onPointerDown);
        v.canvas.addEventListener('pointermove', onPointerMove);
        v.canvas.addEventListener('pointerup', onPointerUp);
        v.canvas.addEventListener('pointercancel', () => { v.drawing = null; redraw(); });
        return v;
    }

    function refreshToolbar() {
        view.root.querySelectorAll('.sl-kind').forEach(b => b.classList.toggle('active', b.dataset.kind === view.kind));
        view.root.querySelectorAll('.sl-colour').forEach(b => b.style.borderColor = b.dataset.colour === view.colour.name ? '#fff' : '#555');
        view.root.querySelectorAll('.sl-width').forEach((b, i) => b.classList.toggle('active', i === view.widthIndex));
    }

    function widthPx(index) {
        const base = Math.max(2, Math.round(Math.max(view.frame.canvas.width, view.frame.canvas.height) / 640));
        return [base, base * 2, base * 4][index];
    }

    function openView(frame) {
        if (!view) view = buildView();
        view.frame = frame;
        view.marks = [];
        view.note.value = '';
        view.message.textContent = '';
        view.armedEmpty = 0;
        view.canvas.width = frame.canvas.width;
        view.canvas.height = frame.canvas.height;
        const fit = () => {
            const stage = view.root.querySelector('.sl-stage');
            const s = Math.min(1, (stage.clientWidth - 16) / frame.canvas.width, (stage.clientHeight - 16) / frame.canvas.height);
            view.canvas.style.width = Math.round(frame.canvas.width * s) + 'px';
            view.canvas.style.height = Math.round(frame.canvas.height * s) + 'px';
        };
        const src = frame.source;
        view.banner.textContent = `Source: ${src.name}${src.step ? ` (fallback ${src.step})` : ''}${src.fallbackReason ? ` because screen capture was unavailable: ${src.fallbackReason}` : ''}`
            + ` | ${frame.canvas.width}x${frame.canvas.height}px | ${frame.complete ? 'complete' : 'INCOMPLETE'}`
            + ` | ${frame.scene.length} objects resolvable under marks${src.label ? ' | ' + src.label : ''}`;
        view.root.style.display = 'flex';
        refreshToolbar();
        fit();
        redraw();
        view.root.tabIndex = -1;
        view.root.focus();
    }

    function hideView() {
        if (view) view.root.style.display = 'none';
    }

    async function cancelView() {
        if (view.marks.length && !(await askConfirm(`Leave without filing? ${view.marks.length} mark(s) will be lost.`, 'Leave'))) return;
        hideView();
        endFinding();
    }

    function askConfirm(text, yes) {
        return new Promise(resolve => {
            view.confirming = true;
            const bar = document.createElement('div');
            bar.className = 'sl-confirm';
            bar.style.cssText = 'padding:6px 10px;background:#5d4037;display:flex;gap:8px;align-items:center';
            bar.innerHTML = '<span class="sl-confirm-text"></span><button type="button" class="btn btn-sm btn-warning sl-yes"></button><button type="button" class="btn btn-sm btn-light sl-no">Keep</button>';
            bar.querySelector('.sl-confirm-text').textContent = text;
            bar.querySelector('.sl-yes').textContent = yes;
            view.message.replaceChildren(bar);
            const done = r => { view.confirming = false; view.message.replaceChildren(); resolve(r); };
            bar.querySelector('.sl-yes').addEventListener('click', () => done(true));
            bar.querySelector('.sl-no').addEventListener('click', () => done(false));
        });
    }

    function toImage(ev) {
        const r = view.canvas.getBoundingClientRect();
        const x = (ev.clientX - r.left) * view.canvas.width / r.width;
        const y = (ev.clientY - r.top) * view.canvas.height / r.height;
        return clampPoint([x, y]);
    }

    // Marks never grow the image: points are clamped to it, drawing is clipped by the canvas.
    function clampPoint([x, y]) {
        return [Math.round(Math.max(0, Math.min(view.canvas.width, x)) * 10) / 10,
            Math.round(Math.max(0, Math.min(view.canvas.height, y)) * 10) / 10];
    }

    function onPointerDown(ev) {
        if (view.confirming) return;
        ev.preventDefault();
        view.canvas.setPointerCapture(ev.pointerId);
        const p = toImage(ev);
        const mark = {n: view.marks.length + 1, kind: view.kind, colour: view.colour.name, width: widthPx(view.widthIndex), points: [p]};
        if (view.kind === 'number') {
            commitMark(mark);
            return;
        }
        if (view.kind !== 'freehand') mark.points.push(p);
        view.drawing = mark;
        redraw();
    }

    function onPointerMove(ev) {
        if (!view.drawing) return;
        const p = toImage(ev);
        const m = view.drawing;
        if (m.kind === 'freehand') {
            const last = m.points[m.points.length - 1];
            if (dist(last[0], last[1], p[0], p[1]) >= 2) m.points.push(p);
        } else m.points[1] = p;
        redraw();
    }

    function onPointerUp(ev) {
        const m = view.drawing;
        if (!m) return;
        view.drawing = null;
        const xs = m.points.map(p => p[0]), ys = m.points.map(p => p[1]);
        if (Math.max(...xs) - Math.min(...xs) < 3 && Math.max(...ys) - Math.min(...ys) < 3) { redraw(); return; } // a click, not a mark
        commitMark(m);
    }

    function commitMark(m) {
        m.objects = resolveMark(m, view.frame.scene, view.frame.imageScale || 1);
        view.marks.push(m);
        view.armedEmpty = 0;
        redraw();
    }

    function colourOf(name) { return COLOURS.find(c => c.name === name) || COLOURS[0]; }

    function redraw() {
        const g = view.canvas.getContext('2d');
        g.drawImage(view.frame.canvas, 0, 0);
        for (const m of view.marks) drawMark(g, m);
        if (view.drawing) drawMark(g, view.drawing);
    }

    function drawMark(g, m) {
        const c = colourOf(m.colour);
        const w = m.width;
        const halo = Math.max(1.5, w * 0.45);
        const pts = m.points;
        const path = () => {
            g.beginPath();
            if (m.kind === 'ellipse') {
                const [[x1, y1], [x2, y2]] = pts;
                g.ellipse((x1 + x2) / 2, (y1 + y2) / 2, Math.abs(x2 - x1) / 2, Math.abs(y2 - y1) / 2, 0, 0, Math.PI * 2);
            } else if (m.kind === 'box') {
                const [[x1, y1], [x2, y2]] = pts;
                g.rect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
            } else if (m.kind === 'line' || m.kind === 'arrow' || m.kind === 'freehand') {
                g.moveTo(pts[0][0], pts[0][1]);
                for (const p of pts.slice(1)) g.lineTo(p[0], p[1]);
                if (m.kind === 'arrow' && pts.length > 1) {
                    const [[x1, y1], [x2, y2]] = pts;
                    const a = Math.atan2(y2 - y1, x2 - x1), len = Math.max(14, w * 4.5);
                    g.moveTo(x2, y2); g.lineTo(x2 - len * Math.cos(a - 0.45), y2 - len * Math.sin(a - 0.45));
                    g.moveTo(x2, y2); g.lineTo(x2 - len * Math.cos(a + 0.45), y2 - len * Math.sin(a + 0.45));
                }
            }
        };
        g.save();
        g.lineCap = 'round';
        g.lineJoin = 'round';
        if (m.kind !== 'number') {
            path(); g.strokeStyle = c.halo; g.lineWidth = w + 2 * halo; g.stroke();
            path(); g.strokeStyle = c.value; g.lineWidth = w; g.stroke();
        }
        // number label for every mark: what makes "at mark 2" answerable
        const [lx, ly] = labelPoint(m);
        const r = m.kind === 'number' ? Math.max(14, w * 4) : Math.max(10, w * 2.6);
        g.beginPath(); g.arc(lx, ly, r + halo, 0, Math.PI * 2); g.fillStyle = c.halo; g.fill();
        g.beginPath(); g.arc(lx, ly, r, 0, Math.PI * 2); g.fillStyle = c.value; g.fill();
        g.fillStyle = c.halo;
        g.font = `bold ${Math.round(r * 1.25)}px sans-serif`;
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        g.fillText(String(m.n), lx, ly + 1);
        g.restore();
    }

    function labelPoint(m) {
        const pts = m.points;
        if (m.kind === 'number') return pts[0];
        if (m.kind === 'ellipse' || m.kind === 'box') {
            const [[x1, y1], [x2, y2]] = pts;
            if (m.kind === 'box') return [Math.min(x1, x2), Math.min(y1, y2)];
            // top-left of the ellipse outline (45 degrees)
            const cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, rx = Math.abs(x2 - x1) / 2, ry = Math.abs(y2 - y1) / 2;
            return [cx - rx * Math.SQRT1_2, cy - ry * Math.SQRT1_2];
        }
        // line, arrow, freehand: sit just behind the start so the badge never hides the stroke
        const far = pts.find(p => dist(p[0], p[1], pts[0][0], pts[0][1]) > 4) || pts[pts.length - 1];
        const dx = far[0] - pts[0][0], dy = far[1] - pts[0][1], len = Math.hypot(dx, dy) || 1;
        const off = Math.max(10, m.width * 2.6) + Math.max(1.5, m.width * 0.45) + 3;
        return [Math.round((pts[0][0] - dx / len * off) * 10) / 10, Math.round((pts[0][1] - dy / len * off) * 10) / 10];
    }

    /******************************** filing ********************************/

    function canvasToBlob(canvas) {
        return new Promise((res, rej) => canvas.toBlob(b => b ? res(b) : rej(new Error('PNG encoding failed')), 'image/png'));
    }

    function blobToBase64(blob) {
        return new Promise((res, rej) => {
            const r = new FileReader();
            r.onload = () => res(String(r.result).split(',')[1]);
            r.onerror = () => rej(r.error);
            r.readAsDataURL(blob);
        });
    }

    function buildSidecar() {
        const f = view.frame;
        return {
            note: view.note.value.trim(),
            source: {...f.source, view: ctx.state.view.tab},
            complete: f.complete,
            image: {width: f.canvas.width, height: f.canvas.height, capturedWidth: f.capturedSize[0], capturedHeight: f.capturedSize[1],
                cappedFactor: f.cappedFactor, cssToImageScale: f.imageScale, maxEdge: MAX_EDGE, format: 'png (lossless)'},
            marks: view.marks.map(m => ({n: m.n, kind: m.kind, colour: m.colour, width: m.width, points: m.points,
                label: labelPoint(m).map(v => Math.round(v * 10) / 10), objects: m.objects})),
            resolution: {
                sceneObjects: f.scene.length,
                resolvable: f.scene.length ? RESOLVABLE : 'none: this source has no known geometry',
                objectKinds: [...new Set(f.scene.map(o => o.kind))],
                notResolvable: 'map tiles/streets, the score analysis and add-visit dialogs, popups, the REST/guide tabs',
            },
            scene: f.scene,
            state: ctx.state,
            display: ctx.display,
            journal: ctx.journal,
            page: {...ctx.page, capturedAt: ctx.capturedAt},
        };
    }

    async function fileFinding() {
        if (view.confirming || view.filing) return;
        if (!view.marks.length && !view.note.value.trim()) {
            if (Date.now() - view.armedEmpty > 5000) {
                view.armedEmpty = Date.now();
                setMessage('Nothing marked and no note. Click "File finding" again within 5 s to file it anyway.', '#ffd54f');
                return;
            }
        }
        view.filing = true;
        redraw();
        let blob, sidecar;
        try {
            blob = await canvasToBlob(view.canvas);
            sidecar = buildSidecar();
            setMessage('Filing...', '#ccc');
            const resp = await fetch('/sightline/findings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
                body: JSON.stringify({png: await blobToBase64(blob), sidecar}),
            });
            if (!resp.ok) {
                let detail = '';
                try { detail = (await resp.json()).error || ''; } catch (e) { /* not json */ }
                throw new Error(`the server answered ${resp.status} ${resp.statusText}${detail ? ': ' + detail : ''}`);
            }
            const result = await resp.json();
            window.__sightlineLastFiled = result.name;
            hideView();
            endFinding();
            showToast(`Filed ${result.name} (3 files in ${result.dir})`, false);
        } catch (e) {
            const reason = e instanceof TypeError ? `the server is unreachable (${e.message})` : (e.message || String(e));
            offerDownload(reason, blob, sidecar);
        } finally {
            view.filing = false;
        }
    }

    function setMessage(text, colour) {
        const d = document.createElement('div');
        d.style.cssText = `padding:6px 0;color:${colour}`;
        d.textContent = text;
        view.message.replaceChildren(d);
    }

    // Filing failed: the annotated image must not be lost.
    function offerDownload(reason, blob, sidecar) {
        const box = document.createElement('div');
        box.className = 'sl-failure';
        box.style.cssText = 'padding:8px 0;color:#ff8a80';
        const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
        box.innerHTML = '<b>Filing failed:</b> <span class="sl-reason"></span>. The annotated image is not lost: ';
        box.querySelector('.sl-reason').textContent = reason;
        if (blob) {
            const a = document.createElement('a');
            a.className = 'sl-download btn btn-sm btn-warning ms-1';
            a.href = URL.createObjectURL(blob);
            a.download = `finding-unfiled-${stamp}.png`;
            a.textContent = 'Download annotated PNG';
            box.appendChild(a);
        }
        if (sidecar) {
            const a = document.createElement('a');
            a.className = 'btn btn-sm btn-outline-warning ms-1';
            a.href = URL.createObjectURL(new Blob([JSON.stringify(sidecar, null, 2)], {type: 'application/json'}));
            a.download = `finding-unfiled-${stamp}.json`;
            a.textContent = 'Download sidecar JSON';
            box.appendChild(a);
        }
        const hint = document.createElement('span');
        hint.textContent = ' You can also retry "File finding" once the server is back.';
        box.appendChild(hint);
        view.message.replaceChildren(box);
    }

    function showToast(text, error) {
        const t = document.createElement('div');
        t.setAttribute('data-sightline', 'toast');
        t.className = 'sl-toast';
        t.style.cssText = `position:fixed;bottom:16px;right:16px;z-index:2147483002;padding:10px 14px;border-radius:6px;font:14px sans-serif;`
            + `color:#fff;background:${error ? '#b71c1c' : '#2e7d32'};box-shadow:0 4px 12px rgba(0,0,0,.3)`;
        t.textContent = text;
        document.body.appendChild(t);
        setTimeout(() => t.remove(), 6000);
    }

    /******************************** open in app ********************************/

    async function openFindingFromUrl() {
        const stem = new URLSearchParams(location.search).get('finding');
        if (!stem) return;
        try {
            const resp = await fetch(`/sightline/findings/${encodeURIComponent(stem)}.json`);
            if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`);
            const sidecar = await resp.json();
            const state = sidecar.state || {};
            if (!state.routePlan) throw new Error('the finding has no route plan state');
            const t0 = Date.now();
            while (!initialized && Date.now() - t0 < 15000) await new Promise(r => setTimeout(r, 100));
            const plan = JSON.parse(JSON.stringify(state.routePlan));
            delete plan.solverStatus; // looking again, not resuming a solver job
            refreshSolvingButtons(false);
            scheduleId = null;
            demoDataId = state.demoDataId || demoDataId;
            initialized = false;
            homeLocationGroup.clearLayers(); homeLocationMarkerByIdMap.clear();
            visitGroup.clearLayers(); visitMarkerByIdMap.clear();
            applyRoutePlan(plan); // the application's own load path
            const v = state.view || {};
            if (v.tab && v.tab !== 'map') {
                const tabButton = document.getElementById(v.tab === 'byVehicle' ? 'byVehicleTab' : 'byVisitTab');
                if (tabButton) bootstrap.Tab.getOrCreateInstance(tabButton).show();
            }
            if (v.map) map.setView(v.map.center, v.map.zoom, {animate: false});
            if (v.byVehicleWindow) byVehicleTimeline.setWindow(v.byVehicleWindow[0], v.byVehicleWindow[1], {animation: false});
            if (v.byVisitWindow) byVisitTimeline.setWindow(v.byVisitWindow[0], v.byVisitWindow[1], {animation: false});
            window.__sightlineOpened = stem;
            showToast(`Showing ${stem} as captured (${sidecar.display ? 'score ' + sidecar.display.score : ''}).`, false);
        } catch (e) {
            showToast(`Could not open ${stem}: ${e.message || e}`, true);
        }
    }

    /******************************** button, link, shortcut ********************************/

    function isTextField(el) {
        if (!el || !el.tagName) return false;
        if (el.isContentEditable) return true;
        const tag = el.tagName.toLowerCase();
        if (tag === 'textarea' || tag === 'select') return true;
        if (tag !== 'input') return false;
        return !['button', 'checkbox', 'radio', 'submit', 'reset', 'range', 'color', 'file', 'image'].includes((el.type || 'text').toLowerCase());
    }

    document.addEventListener('keydown', ev => {
        if (!(ev.altKey && ev.shiftKey && ev.code === 'KeyF')) return;
        if (isTextField(ev.target) || isTextField(document.activeElement)) return;
        if (isViewVisible() || busy) return;
        ev.preventDefault();
        startFinding().catch(e => console.error(e));
    });

    function installButton() {
        const nav = document.getElementById('navbarNav');
        if (!nav) return;
        const group = document.createElement('div');
        group.setAttribute('data-sightline', 'nav');
        group.className = 'd-flex align-items-center gap-2 me-3';
        group.innerHTML = `<button type="button" id="sightlineButton" class="btn btn-outline-danger" title="Capture a finding (Alt+Shift+F)">
              <i class="fas fa-camera"></i> Finding</button>
            <a id="sightlineFindingsLink" class="btn btn-outline-secondary" href="/sightline" title="All findings">Findings</a>`;
        const dropdown = nav.querySelector('.dropdown');
        nav.insertBefore(group, dropdown);
        group.querySelector('#sightlineButton').addEventListener('click', ev => {
            ev.currentTarget.blur();
            startFinding().catch(e => console.error(e));
        });
    }

    window.Sightline = {startFinding, capture, isViewVisible, resolveMark, snapshotScene, CaptureRefused, CaptureUnavailable,
        get marks() { return view ? view.marks : []; }};

    $(function () {
        installButton();
        openFindingFromUrl();
    });
})();
