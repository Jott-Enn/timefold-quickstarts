// Sightline findings page: lists index.json (open first, newest first), shows images, reopens a finding's state
// in the app, and closes findings with a verdict through the same endpoint the agent tooling uses.
(function () {
    'use strict';
    const list = document.getElementById('list');
    const lightbox = document.getElementById('lightbox');
    lightbox.addEventListener('click', () => { lightbox.style.display = 'none'; });

    const VERDICTS = {
        fixed: 'fixed (name the commit)',
        intended: 'intended',
        superseded: 'superseded',
        duplicate: 'duplicate (name the other finding)',
        answered: 'answered',
    };

    function el(tag, attrs, ...children) {
        const e = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs || {})) {
            if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.substring(2), v); else e.setAttribute(k, v);
        }
        for (const c of children) if (c != null) e.append(c);
        return e;
    }

    async function load() {
        list.replaceChildren();
        let entries;
        try {
            const resp = await fetch('/sightline/findings', {cache: 'no-store'});
            if (!resp.ok) throw new Error(resp.status + ' ' + resp.statusText);
            entries = await resp.json();
        } catch (e) {
            showError('Could not load the findings index: ' + e.message);
            return;
        }
        if (!entries.length) {
            list.append(el('p', {class: 'text-muted'}, 'No findings yet. Use the Finding button in the app (Alt+Shift+F).'));
            return;
        }
        for (const f of entries) list.append(render(f));
    }

    function showError(text) {
        const box = document.getElementById('error');
        box.textContent = text;
        box.classList.remove('d-none');
    }

    function render(f) {
        const done = f.status === 'done';
        const thumb = el('img', {class: 'thumb', src: `/sightline/findings/${f.thumb}`, alt: f.name, title: 'Show full image'});
        thumb.addEventListener('click', () => {
            lightbox.querySelector('img').src = `/sightline/findings/${f.image}`;
            lightbox.style.display = 'flex';
        });
        const created = new Date(f.created);
        const status = el('span', {class: 'badge ' + (done ? 'bg-secondary' : 'bg-danger')}, done ? 'done' : 'open');
        const body = el('div', {},
            el('div', {class: 'd-flex gap-2 align-items-center'}, status,
                el('strong', {}, isNaN(created) ? f.created : created.toLocaleString()),
                el('span', {class: 'meta'}, f.name)),
            el('div', {class: 'note my-1'}, f.note ? f.note : el('span', {class: 'text-muted'}, '(no note)')),
            el('div', {class: 'meta'}, `source: ${f.source}${f.view ? ' (' + f.view + ')' : ''}${f.complete ? '' : ' INCOMPLETE'}`
                + ` | ${f.marks} mark(s) | objects: ${f.objects.length ? f.objects.join(', ') : 'none resolved'}`),
        );
        if (done) {
            body.append(el('div', {class: 'mt-1'}, el('b', {}, 'Verdict: '), f.verdict
                + (f.commit ? ` (${f.commit})` : '') + (f.duplicateOf ? ` (of ${f.duplicateOf})` : '') + ' — ' + (f.closingNote || '')));
        }
        const actions = el('div', {class: 'd-flex gap-2 mt-2 flex-wrap'},
            el('a', {class: 'btn btn-sm btn-outline-dark', href: `/sightline/findings/${f.image}`, target: '_blank'}, 'Full image'),
            el('a', {class: 'btn btn-sm btn-primary sl-open', href: `/?finding=${encodeURIComponent(f.name)}`}, 'Open in app'),
            el('a', {class: 'btn btn-sm btn-outline-secondary', href: `/sightline/findings/${f.name}.json`, target: '_blank'}, 'Sidecar'));
        body.append(actions);
        if (!done) {
            const form = closeForm(f);
            actions.append(el('button', {class: 'btn btn-sm btn-outline-success sl-close', type: 'button',
                onclick: () => form.classList.toggle('d-none')}, 'Close…'));
            body.append(form);
        }
        return el('div', {class: 'finding' + (done ? ' done' : ''), 'data-name': f.name}, el('div', {}, thumb), body);
    }

    function closeForm(f) {
        const verdict = el('select', {class: 'form-select form-select-sm sl-verdict', style: 'width:auto'},
            ...Object.entries(VERDICTS).map(([v, label]) => el('option', {value: v}, label)));
        const reference = el('input', {class: 'form-control form-control-sm sl-reference', placeholder: 'commit hash', style: 'width:220px'});
        const cause = el('input', {class: 'form-control form-control-sm sl-cause', placeholder: 'one-line cause', maxlength: '300', style: 'min-width:280px;flex:1'});
        const message = el('div', {class: 'small text-danger sl-message'});
        const syncReference = () => {
            const v = verdict.value;
            reference.classList.toggle('d-none', v !== 'fixed' && v !== 'duplicate');
            reference.placeholder = v === 'fixed' ? 'commit hash' : 'other finding, e.g. finding-20260101-120000';
        };
        verdict.addEventListener('change', syncReference);
        syncReference();
        const submit = el('button', {class: 'btn btn-sm btn-success sl-submit', type: 'button'}, 'Close finding');
        submit.addEventListener('click', async () => {
            const resp = await fetch(`/sightline/findings/${encodeURIComponent(f.name)}/close`, {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({verdict: verdict.value, cause: cause.value, reference: reference.value.trim() || null}),
            });
            if (resp.ok) load();
            else message.textContent = ((await resp.json().catch(() => ({}))).error) || `${resp.status} ${resp.statusText}`;
        });
        return el('div', {class: 'd-none mt-2'}, el('div', {class: 'd-flex gap-2 flex-wrap align-items-center'}, verdict, reference, cause, submit), message);
    }

    load();
})();
