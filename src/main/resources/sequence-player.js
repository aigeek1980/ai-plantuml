// Playback engine for the sequence player window.
//
// The step list is derived from the rendered diagram rather than by parsing the
// source again. PlantUML wraps a participant's link around its box, but a message's
// link around the label text alone - so a link containing no shape is a message, and
// sorting those by vertical position gives the order they happen in.
//
// Each message's arrow geometry is matched to its label by position, because the SVG
// is flat: no grouping, no ids, nothing tying an arrow to the text beside it.
(function () {

    var LINK = 'node://';
    // How far from the label's centre line an element may sit and still belong to it.
    var BAND = 24;
    // Anything this tall is a lifeline or activation bar spanning the diagram, not an arrow.
    var MAX_ARROW_HEIGHT = 48;

    var steps = [];

    function linkOf(el) {
        return el.getAttribute('href')
            || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
    }

    function build() {
        var geometry = [];
        var candidates = document.querySelectorAll('line, path, polygon');
        for (var c = 0; c < candidates.length; c++) {
            geometry.push({ el: candidates[c], r: candidates[c].getBoundingClientRect() });
        }

        // A label broken over several lines is rendered as one <text> per line, each
        // wrapped in its own link carrying the same href - so the anchors are grouped
        // back into one step per message rather than one per rendered line.
        var byHref = {};
        var order = [];
        var anchors = document.querySelectorAll('a');
        for (var i = 0; i < anchors.length; i++) {
            var a = anchors[i];
            var href = linkOf(a);
            if (!href || href.indexOf(LINK) !== 0) continue;
            // A link around a shape is a participant, not a message.
            if (a.querySelector('rect, path, polygon, ellipse')) continue;
            var r = a.getBoundingClientRect();
            if (!r.height) continue;

            var step = byHref[href];
            if (!step) {
                step = { href: href, els: [], top: r.top, bottom: r.bottom, parts: [] };
                byHref[href] = step;
                order.push(step);
            }
            step.els.push(a);
            step.top = Math.min(step.top, r.top);
            step.bottom = Math.max(step.bottom, r.bottom);
        }

        for (var s = 0; s < order.length; s++) {
            order[s].mid = (order[s].top + order[s].bottom) / 2;
        }
        order.sort(function (p, q) { return p.mid - q.mid; });

        for (var s = 0; s < order.length; s++) {
            var step = order[s];
            for (var g = 0; g < geometry.length; g++) {
                var gr = geometry[g].r;
                if (!gr.width && !gr.height) continue;
                if (gr.height > MAX_ARROW_HEIGHT) continue;
                var gy = gr.top + gr.height / 2;
                if (gy < step.top - BAND || gy > step.bottom + BAND) continue;
                step.parts.push(geometry[g].el);
            }
        }

        steps = order;
        return steps.length;
    }

    function clearHighlight() {
        for (var i = 0; i < steps.length; i++) {
            for (var e = 0; e < steps[i].els.length; e++) {
                steps[i].els[e].classList.remove('step-current');
            }
            for (var p = 0; p < steps[i].parts.length; p++) {
                steps[i].parts[p].classList.remove('step-current');
            }
        }
    }

    function show(index) {
        clearHighlight();
        document.body.classList.add('playing');
        if (index < 0 || index >= steps.length) return '';

        var step = steps[index];
        for (var e = 0; e < step.els.length; e++) {
            step.els[e].classList.add('step-current');
        }
        for (var p = 0; p < step.parts.length; p++) {
            step.parts[p].classList.add('step-current');
        }

        // Keep the active step in view without yanking the diagram around when it is
        // already comfortably on screen.
        var r = step.els[0].getBoundingClientRect();
        var margin = window.innerHeight * 0.25;
        if (r.top < margin || r.bottom > window.innerHeight - margin) {
            window.scrollBy(0, r.top - window.innerHeight / 2);
        }
        return step.href;
    }

    function reset() {
        clearHighlight();
        document.body.classList.remove('playing');
        window.scrollTo(0, 0);
    }

    window.player = {
        build: build,
        show: show,
        reset: reset,
        count: function () { return steps.length; }
    };
})();
