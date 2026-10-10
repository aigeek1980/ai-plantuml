// Playback engine for the diagram player window.
//
// The step list is derived from the rendered diagram rather than by parsing the
// source again. PlantUML wraps a participant's link around its box, but a message's
// link around the label text alone - so a link containing no shape is a message, and
// sorting those by vertical position gives the order they happen in.
//
// Everything else is matched by position, because the SVG is flat: no grouping, no
// ids, nothing tying an arrow to the text beside it, to the lifelines it spans, or to
// the alt/group frame it sits inside.
(function () {

    var LINK = 'node://';
    var SEP = '\u0001';
    // Anything this tall is a lifeline, an activation bar or a frame, not an arrow.
    var MAX_ARROW_HEIGHT = 48;
    // Slack when deciding whether an arrow end lands on a participant's column.
    var COLUMN_SLACK = 14;
    // Slack past the outermost lifelines before an element is too wide to be an arrow.
    var ARROW_WIDTH_SLACK = 24;
    // Smallest box treated as an alt/group frame rather than diagram furniture.
    var MIN_FRAME_HEIGHT = 56;
    var MIN_FRAME_WIDTH = 120;
    // A box nearly as tall as the diagram is a participant grouping or the background,
    // which belong to the diagram's structure and are always shown.
    var FULL_HEIGHT_RATIO = 0.9;
    // Band below a frame top edge holding its "alt" tab and first condition.
    var FRAME_HEADER_BAND = 26;
    // How far right of a frame edge its tab shape, and then its condition text, may sit.
    var FRAME_TAB_REACH = 140;
    var FRAME_LABEL_REACH = 520;
    // How near a marker a stray label must be to belong to it.
    var MARKER_LABEL_BAND = 14;
    // Tolerance when judging whether a rule reaches a frame edge to edge.
    var FRAME_EDGE_SLACK = 6;
    var PAN_THRESHOLD = 4;

    var steps = [];
    var participants = [];
    var frames = [];
    var markers = [];

    // A participant's text runs its guillemet stereotype together with its name.
    function cleanName(text) {
        return (text || '').replace(/«[^»]*»/g, '').trim();
    }

    function linkOf(el) {
        return el.getAttribute('href')
            || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
    }

    function docRect(el) {
        var r = el.getBoundingClientRect();
        return {
            left: r.left + window.scrollX, right: r.right + window.scrollX,
            top: r.top + window.scrollY, bottom: r.bottom + window.scrollY,
            width: r.width, height: r.height
        };
    }

    function build() {
        steps = []; participants = []; frames = []; markers = [];

        var diagramHeight = document.documentElement.scrollHeight;

        // Frames first, so they can claim their own header text before the messages
        // sweep up whatever loose text sits in their row.
        var boxes = document.querySelectorAll('rect, path, polyline');
        for (var b = 0; b < boxes.length; b++) {
            var br = docRect(boxes[b]);
            if (br.height < MIN_FRAME_HEIGHT || br.width < MIN_FRAME_WIDTH) continue;
            if (br.height > diagramHeight * FULL_HEIGHT_RATIO) continue;
            frames.push({ el: boxes[b], left: br.left, right: br.right,
                          top: br.top, bottom: br.bottom, texts: [] });
        }

        // The "alt" tab is a small shape in the frame's top-left corner. Left unclaimed,
        // the message above it takes it for part of its own arrow, and since the tab sits
        // at the far left the message then looks as though it reached that far across.
        function frameHeaderOwner(r, reach) {
            for (var f = 0; f < frames.length; f++) {
                if (r.top < frames[f].top - 2 || r.top > frames[f].top + FRAME_HEADER_BAND) continue;
                if (r.left > frames[f].left + reach) continue;
                return frames[f];
            }
            return null;
        }

        for (var b = 0; b < boxes.length; b++) {
            var sr = docRect(boxes[b]);
            if (sr.height >= MIN_FRAME_HEIGHT) continue;
            var tabOwner = frameHeaderOwner(sr, FRAME_TAB_REACH);
            if (tabOwner) {
                tabOwner.texts.push(boxes[b]);
                boxes[b].__frameOwned = true;
            }
        }

        var geometry = [];
        var shapes = document.querySelectorAll('line, path, polygon');
        for (var g = 0; g < shapes.length; g++) {
            geometry.push({ el: shapes[g], r: docRect(shapes[g]) });
        }

        var looseText = [];
        var texts = document.querySelectorAll('text');
        for (var t = 0; t < texts.length; t++) {
            if (texts[t].closest('a')) continue;
            var tr = docRect(texts[t]);
            if (!tr.width && !tr.height) continue;
            var owner = frameHeaderOwner(tr, FRAME_LABEL_REACH);
            if (owner) { owner.texts.push(texts[t]); texts[t].__frameOwned = true; }
            else { looseText.push({ el: texts[t], y: tr.top + tr.height / 2 }); }
        }

        var messagesByHref = {}, messageOrder = [], participantsByHref = {};
        var anchors = document.querySelectorAll('a');
        for (var i = 0; i < anchors.length; i++) {
            var a = anchors[i];
            var href = linkOf(a);
            if (!href || href.indexOf(LINK) !== 0) continue;
            var r = docRect(a);
            if (!r.height) continue;

            // A participant is drawn at the top and bottom of the diagram; a label
            // wrapped over several lines becomes several links sharing one target. Both
            // are grouped by target rather than taken one element at a time.
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

        // An arrow runs between two lifelines, so it cannot be wider than the outermost
        // pair. Frame borders and "== separator ==" rules are thin enough to pass for
        // arrows but stretch past that, and counting them made a message look as though
        // it spanned the whole diagram - which threw off both its participants and the
        // zoom needed to fit it.
        var minCentre = Infinity, maxCentre = -Infinity;
        for (var p = 0; p < participants.length; p++) {
            for (var b = 0; b < participants[p].boxes.length; b++) {
                var centre = (participants[p].boxes[b].left + participants[p].boxes[b].right) / 2;
                minCentre = Math.min(minCentre, centre);
                maxCentre = Math.max(maxCentre, centre);
            }
        }
        var maxArrowWidth = maxCentre > minCentre
            ? (maxCentre - minCentre) + ARROW_WIDTH_SLACK : Infinity;

        // Rules too wide to be an arrow are "== separator ==" bars and the dotted line
        // dividing one branch of an alt from the next. They mark a place in the flow, so
        // they appear once the run passes them rather than standing there from the start -
        // and a divider inside a frame only counts runs inside that same frame, so the
        // branch not taken never announces itself.
        for (var g = 0; g < geometry.length; g++) {
            var mr = geometry[g].r;
            if (geometry[g].el.__frameOwned) continue;
            if (mr.height > MAX_ARROW_HEIGHT) continue;

            var mid = mr.top + mr.height / 2;
            var owner = null;
            for (var f = 0; f < frames.length; f++) {
                if (mid > frames[f].top && mid < frames[f].bottom) {
                    if (owner === null || frames[f].top > owner.top) owner = frames[f];
                }
            }

            // Too wide to be an arrow at all, or it stretches the full width of the frame
            // it sits in - which a message between two of that frame's lifelines never
            // does. Judging a divider by absolute width alone misses the ones inside a
            // narrow frame, which is most of them.
            var spansDiagram = mr.width > maxArrowWidth;
            var spansFrame = owner !== null
                    && mr.left <= owner.left + FRAME_EDGE_SLACK
                    && mr.right >= owner.right - FRAME_EDGE_SLACK;
            if (!spansDiagram && !spansFrame) continue;
            var marker = { el: geometry[g].el, y: mid, frame: owner, texts: [] };
            for (var t = 0; t < looseText.length; t++) {
                if (looseText[t].el.__frameOwned) continue;
                if (Math.abs(looseText[t].y - mid) > MARKER_LABEL_BAND) continue;
                marker.texts.push(looseText[t].el);
                looseText[t].el.__frameOwned = true;
            }
            geometry[g].el.__frameOwned = true;
            markers.push(marker);
        }

        for (var s = 0; s < messageOrder.length; s++) {
            var step = messageOrder[s];

            // A message owns everything from its label down to the next message's label.
            // PlantUML draws the label above its arrow, so this captures the arrow and
            // the several segments of a self-message loop, while excluding both the next
            // message's arrow and anything on the row above - a "..." delay line spans
            // the full width, and sweeping that in made messages look as though they ran
            // between the outermost participants.
            var limit = s + 1 < messageOrder.length ? messageOrder[s + 1].top : Infinity;

            var minX = Infinity, maxX = -Infinity, headX = null;
            for (var g = 0; g < geometry.length; g++) {
                var gr = geometry[g].r;
                if (!gr.width && !gr.height) continue;
                if (gr.height > MAX_ARROW_HEIGHT) continue;
                if (gr.width > maxArrowWidth) continue;
                if (geometry[g].el.__frameOwned) continue;
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

            // Autonumber digits are drawn outside the message's link and would otherwise
            // linger after the step is hidden.
            for (var t = 0; t < looseText.length; t++) {
                if (looseText[t].el.__frameOwned) continue;
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

    /** Horizontal span that must be on screen for a step: both its lifelines, end to end. */
    function spanOf(step) {
        var ends = endpointsOf(step);
        var min = step.minX, max = step.maxX;
        var sides = [ends.from, ends.to];
        for (var i = 0; i < sides.length; i++) {
            if (!sides[i]) continue;
            for (var b = 0; b < sides[i].boxes.length; b++) {
                min = Math.min(min, sides[i].boxes[b].left);
                max = Math.max(max, sides[i].boxes[b].right);
            }
        }
        return { min: min, max: max, ends: ends };
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
        for (var f = 0; f < frames.length; f++) {
            frames[f].el.classList.remove('step-future');
            setClass(frames[f].texts, 'step-future', false);
        }
        for (var m = 0; m < markers.length; m++) {
            markers[m].el.classList.remove('step-future');
            setClass(markers[m].texts, 'step-future', false);
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

        var shownBands = [];
        for (var i = 0; i < steps.length; i++) {
            var shown = (i === index) || executed[i] === true;
            setClass(steps[i].els, 'step-future', !shown);
            setClass(steps[i].parts, 'step-future', !shown);
            if (shown) shownBands.push(steps[i]);
        }

        // An alt or group frame is part of the flow, not the backdrop: it appears only
        // once something inside it has run, rather than standing empty beforehand.
        for (var f = 0; f < frames.length; f++) {
            var entered = false;
            for (var s = 0; s < shownBands.length; s++) {
                if (shownBands[s].mid >= frames[f].top && shownBands[s].mid <= frames[f].bottom) {
                    entered = true;
                    break;
                }
            }
            if (entered) {
                frames[f].el.classList.remove('step-future');
                setClass(frames[f].texts, 'step-future', false);
            } else {
                frames[f].el.classList.add('step-future');
                setClass(frames[f].texts, 'step-future', true);
            }
        }

        // A separator or branch divider marks a place the run goes past, so it shows once
        // something below it has run. For a divider inside a frame only runs inside that
        // same frame count, which is what keeps the branch not taken from announcing
        // itself with its own condition and dividing line.
        for (var m = 0; m < markers.length; m++) {
            var marker = markers[m];
            var passed = false;
            for (var s = 0; s < shownBands.length; s++) {
                var band = shownBands[s];
                if (band.mid <= marker.y) continue;
                if (marker.frame && (band.mid < marker.frame.top || band.mid > marker.frame.bottom)) continue;
                passed = true;
                break;
            }
            if (passed) {
                marker.el.classList.remove('step-future');
                setClass(marker.texts, 'step-future', false);
            } else {
                marker.el.classList.add('step-future');
                setClass(marker.texts, 'step-future', true);
            }
        }

        if (index < 0 || index >= steps.length) return '';
        var step = steps[index];
        setClass(step.els, 'step-current', true);
        setClass(step.parts, 'step-current', true);

        var span = spanOf(step);
        if (span.ends.from) setClass(span.ends.from.els, 'participant-current', true);
        if (span.ends.to) setClass(span.ends.to.els, 'participant-current', true);

        return step.href + SEP + (span.ends.from ? span.ends.from.name : '')
                         + SEP + (span.ends.to ? span.ends.to.name : '')
                         + SEP + Math.round(span.max - span.min)
                         + SEP + Math.round(window.innerWidth);
    }

    /** Brings the step, and the whole span between its two lifelines, into view. */
    function centre(index) {
        if (index < 0 || index >= steps.length) return '';
        var step = steps[index];
        var span = spanOf(step);

        if (span.min !== Infinity) {
            var middle = (span.min + span.max) / 2;
            window.scrollTo(Math.max(0, middle - window.innerWidth / 2), window.scrollY);
        }
        var r = step.els[0].getBoundingClientRect();
        var margin = window.innerHeight * 0.25;
        if (r.top < margin || r.bottom > window.innerHeight - margin) {
            window.scrollBy(0, r.top - window.innerHeight / 2);
        }
        return '';
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
        centre: centre,
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
