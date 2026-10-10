// Playback engine for the diagram player window.
//
// The step list is derived from the rendered diagram rather than by parsing the
// source again. PlantUML wraps a participant's link around its box, but a message's
// link around the label text alone - so a link containing no shape is a message, and
// sorting those by vertical position gives the order they happen in.
//
// Arrow geometry is matched to its label by position, and participants to an arrow by
// where its ends land, because the SVG is flat: no grouping, no ids, nothing tying an
// arrow to the text beside it or to the lifelines it spans.
(function () {

    var LINK = 'node://';
    var SEP = '\u0001';
    // Anything this tall is a lifeline or activation bar spanning the diagram, not an arrow.
    var MAX_ARROW_HEIGHT = 48;
    // Slack when deciding whether an arrow end lands on a participant's column.
    var COLUMN_SLACK = 14;
    var PAN_THRESHOLD = 4;

    var steps = [];
    var participants = [];

    // A participant's text runs its guillemet stereotype together with its name.
    function cleanName(text) {
        return (text || '').replace(/«[^»]*»/g, '').trim();
    }

    function linkOf(el) {
        return el.getAttribute('href')
            || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
    }

    function build() {
        var geometry = [];
        var shapes = document.querySelectorAll('line, path, polygon');
        for (var g = 0; g < shapes.length; g++) {
            geometry.push({ el: shapes[g], r: shapes[g].getBoundingClientRect() });
        }

        // A label wrapped over several lines becomes several links sharing one target,
        // and a participant is drawn at both the top and bottom of the diagram, so both
        // are grouped by target rather than taken one element at a time.
        var messagesByHref = {}, messageOrder = [];
        var participantsByHref = {};

        var anchors = document.querySelectorAll('a');
        for (var i = 0; i < anchors.length; i++) {
            var a = anchors[i];
            var href = linkOf(a);
            if (!href || href.indexOf(LINK) !== 0) continue;
            var r = a.getBoundingClientRect();
            if (!r.height) continue;

            if (a.querySelector('rect, path, polygon, ellipse')) {
                var p = participantsByHref[href];
                if (!p) {
                    p = { href: href, els: [], boxes: [], name: cleanName(a.textContent) };
                    participantsByHref[href] = p;
                    participants.push(p);
                }
                p.els.push(a);
                p.boxes.push(r);
                continue;
            }

            var step = messagesByHref[href];
            if (!step) {
                step = { href: href, els: [], top: r.top, bottom: r.bottom, parts: [] };
                messagesByHref[href] = step;
                messageOrder.push(step);
            }
            step.els.push(a);
            step.top = Math.min(step.top, r.top);
            step.bottom = Math.max(step.bottom, r.bottom);
        }

        for (var s = 0; s < messageOrder.length; s++) {
            messageOrder[s].mid = (messageOrder[s].top + messageOrder[s].bottom) / 2;
        }
        messageOrder.sort(function (p, q) { return p.mid - q.mid; });

        // Autonumber digits are drawn as their own text, outside the message's link, so
        // they have to be claimed by position or they linger after the step is hidden.
        var looseText = [];
        var texts = document.querySelectorAll('text');
        for (var t = 0; t < texts.length; t++) {
            if (texts[t].closest('a')) continue;
            var tr = texts[t].getBoundingClientRect();
            if (!tr.width && !tr.height) continue;
            looseText.push({ el: texts[t], y: tr.top + tr.height / 2 });
        }

        for (var s = 0; s < messageOrder.length; s++) {
            var step = messageOrder[s];

            // A message owns everything from its label down to the next message's label.
            // PlantUML draws the label above its arrow, so this captures the arrow and the
            // several segments of a self-message loop, while excluding both the next
            // message's arrow and anything on the row above - a "..." delay line spans the
            // full width, and sweeping that in made messages look as though they ran
            // between the outermost participants.
            var limit = s + 1 < messageOrder.length ? messageOrder[s + 1].top : Infinity;

            var minX = Infinity, maxX = -Infinity, headX = null;
            for (var g = 0; g < geometry.length; g++) {
                var gr = geometry[g].r;
                if (!gr.width && !gr.height) continue;
                if (gr.height > MAX_ARROW_HEIGHT) continue;
                var gy = gr.top + gr.height / 2;
                if (gy < step.top || gy >= limit) continue;
                step.parts.push(geometry[g].el);
                minX = Math.min(minX, gr.left);
                maxX = Math.max(maxX, gr.right);
                // The arrowhead marks the receiving end, which is what makes the
                // direction recoverable at all.
                if (geometry[g].el.tagName.toLowerCase() === 'polygon') {
                    headX = gr.left + gr.width / 2;
                }
            }

            for (var t = 0; t < looseText.length; t++) {
                if (looseText[t].y < step.top || looseText[t].y >= limit) continue;
                step.parts.push(looseText[t].el);
            }

            step.minX = minX;
            step.maxX = maxX;
            step.headX = headX;
        }

        steps = messageOrder;
        return steps.length;
    }

    function participantAt(x) {
        var best = null, bestDistance = Infinity;
        for (var p = 0; p < participants.length; p++) {
            for (var b = 0; b < participants[p].boxes.length; b++) {
                var box = participants[p].boxes[b];
                if (x < box.left - COLUMN_SLACK || x > box.right + COLUMN_SLACK) continue;
                var distance = Math.abs((box.left + box.right) / 2 - x);
                if (distance < bestDistance) { bestDistance = distance; best = participants[p]; }
            }
        }
        return best;
    }

    function endpointsOf(step) {
        if (step.minX === Infinity) return { from: null, to: null };
        var left = participantAt(step.minX);
        var right = participantAt(step.maxX);
        if (!left && !right) return { from: null, to: null };
        if (!left) return { from: right, to: right };
        if (!right) return { from: left, to: left };
        if (left === right) return { from: left, to: left };
        // Without an arrowhead to go on, assume the usual left-to-right reading.
        var pointsLeft = step.headX !== null
            && Math.abs(step.headX - step.minX) < Math.abs(step.headX - step.maxX);
        return pointsLeft ? { from: right, to: left } : { from: left, to: right };
    }

    function setClass(els, name, on) {
        for (var i = 0; i < els.length; i++) {
            if (on) els[i].classList.add(name); else els[i].classList.remove(name);
        }
    }

    function clearAll() {
        for (var i = 0; i < steps.length; i++) {
            setClass(steps[i].els, 'step-current', false);
            setClass(steps[i].els, 'step-future', false);
            setClass(steps[i].parts, 'step-current', false);
            setClass(steps[i].parts, 'step-future', false);
        }
        for (var p = 0; p < participants.length; p++) {
            setClass(participants[p].els, 'participant-current', false);
        }
    }

    /**
     * executedCsv lists the steps that have actually run, which is not simply
     * "everything before the current one": branches the user did not choose are skipped
     * and must stay hidden even once playback has moved past them.
     */
    function render(executedCsv, index) {
        clearAll();

        // Before the first step the whole diagram is shown, so the player opens on
        // something recognisable rather than an empty canvas.
        if (index < 0 && !executedCsv) {
            document.body.classList.remove('playing');
            return '';
        }
        document.body.classList.add('playing');

        var executed = {};
        if (executedCsv) {
            var ids = executedCsv.split(',');
            for (var k = 0; k < ids.length; k++) executed[parseInt(ids[k], 10)] = true;
        }

        for (var i = 0; i < steps.length; i++) {
            var shown = (i === index) || executed[i] === true;
            setClass(steps[i].els, 'step-future', !shown);
            setClass(steps[i].parts, 'step-future', !shown);
        }

        if (index < 0 || index >= steps.length) return '';
        var step = steps[index];
        setClass(step.els, 'step-current', true);
        setClass(step.parts, 'step-current', true);

        var ends = endpointsOf(step);
        if (ends.from) setClass(ends.from.els, 'participant-current', true);
        if (ends.to) setClass(ends.to.els, 'participant-current', true);

        var r = step.els[0].getBoundingClientRect();
        var margin = window.innerHeight * 0.25;
        if (r.top < margin || r.bottom > window.innerHeight - margin) {
            window.scrollBy(0, r.top - window.innerHeight / 2);
        }

        return step.href + SEP + (ends.from ? ends.from.name : '')
                         + SEP + (ends.to ? ends.to.name : '');
    }

    // Drag to move the diagram. Deliberately a local copy rather than shared with the
    // editor's viewer: the player must not be able to disturb it.
    (function installPan() {
        var down = false, panning = false, lastX = 0, lastY = 0;
        var pendingX = 0, pendingY = 0, queued = false;

        function flush() {
            queued = false;
            if (pendingX || pendingY) {
                window.scrollBy(pendingX, pendingY);
                pendingX = 0; pendingY = 0;
            }
        }

        document.addEventListener('dragstart', function (e) { e.preventDefault(); });

        document.addEventListener('mousedown', function (e) {
            if (e.button !== 0) return;
            down = true; panning = false;
            lastX = e.clientX; lastY = e.clientY;
            e.preventDefault();
        });

        document.addEventListener('mousemove', function (e) {
            if (!down) return;
            if (e.buttons === 0) { down = false; document.body.classList.remove('panning'); return; }
            var dx = e.clientX - lastX, dy = e.clientY - lastY;
            if (!panning && Math.abs(dx) + Math.abs(dy) < PAN_THRESHOLD) return;
            if (!panning) { panning = true; document.body.classList.add('panning'); }
            // Scrolling on every move repaints faster than a large diagram can be
            // redrawn, so the deltas are applied once per frame instead.
            pendingX -= dx; pendingY -= dy;
            lastX = e.clientX; lastY = e.clientY;
            if (!queued) { queued = true; requestAnimationFrame(flush); }
            e.preventDefault();
        });

        document.addEventListener('mouseup', function () {
            down = false;
            document.body.classList.remove('panning');
        });
    })();

    window.player = {
        build: build,
        render: render,
        count: function () { return steps.length; },
        // Every step's target, in order, so the caller can resolve each to a source line
        // once rather than asking again on each step.
        targets: function () {
            var out = [];
            for (var i = 0; i < steps.length; i++) out.push(steps[i].href);
            return out.join(SEP);
        }
    };
})();
