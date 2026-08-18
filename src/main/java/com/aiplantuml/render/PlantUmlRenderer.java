package com.aiplantuml.render;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PlantUmlRenderer {

    static {
        // PlantUML refuses to render images past this many pixels on either axis (default
        // 4096), silently cropping large diagrams instead of erroring. Raise it well past
        // what any reasonable diagram in this app needs. Must be set before the first
        // render call - PlantUML reads it once via System.getProperty, not per-call.
        System.setProperty("PLANTUML_LIMIT_SIZE", "16384");
    }

    private final DiagramNodeIndexer nodeIndexer = new DiagramNodeIndexer();

    public record SvgRenderResult(String svg, String errorText, Map<String, Integer> nodeLineNumbers) {
        public boolean isError() {
            return errorText != null;
        }
    }

    public record PngRenderResult(byte[] png, String errorText) {
        public boolean isError() {
            return errorText != null;
        }
    }

    /**
     * Renders the diagram to SVG for the interactive viewer. Node click-to-navigate no
     * longer needs a separately fetched pixel image-map the way the PNG viewer did:
     * PlantUML's SVG output wraps each linked element in a real {@code <a>} tag around
     * its actual vector shape, so the rendered SVG *is* the hit-testable structure once
     * it's loaded into a WebView's DOM - there's no separate coordinate data to keep in
     * sync with a separately rendered image.
     * <p>
     * Node links still require the same heuristic [[link]] injection into a "shadow"
     * copy of the source as before (see DiagramNodeIndexer) - that part is about mapping
     * diagram elements back to source lines, which is independent of the output format.
     * If the injected links break otherwise-valid syntax, falls back to the plain
     * source rendered without clickable links.
     */
    public SvgRenderResult renderSvg(String plantUmlSource) {
        DiagramNodeIndexer.IndexResult indexResult = null;
        try {
            indexResult = nodeIndexer.index(plantUmlSource);
        } catch (Exception ignored) {
            // heuristic indexing failed - fall through to a plain, non-interactive render
        }

        if (indexResult != null) {
            RenderedSvg shadow = tryRenderSvg(indexResult.shadowSource());
            if (shadow.svg != null) {
                return new SvgRenderResult(shadow.svg, null, indexResult.nodeLineNumbers());
            }
        }

        RenderedSvg original = tryRenderSvg(plantUmlSource);
        if (original.svg == null) {
            return new SvgRenderResult(null, original.error, Map.of());
        }
        return new SvgRenderResult(original.svg, null, Map.of());
    }

    /** Renders the plain source (no link injection needed - PNG export isn't interactive). */
    public PngRenderResult renderPng(String plantUmlSource) {
        SourceStringReader reader = new SourceStringReader(plantUmlSource);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var description = reader.outputImage(out, new FileFormatOption(FileFormat.PNG));
            if (description == null) {
                return new PngRenderResult(null, "PlantUML produced no output. Check the diagram syntax.");
            }
            return new PngRenderResult(out.toByteArray(), null);
        } catch (Exception e) {
            return new PngRenderResult(null, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private record RenderedSvg(String svg, String error) {
    }

    private RenderedSvg tryRenderSvg(String source) {
        SourceStringReader reader = new SourceStringReader(source);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var description = reader.outputImage(out, new FileFormatOption(FileFormat.SVG));
            if (description == null) {
                return new RenderedSvg(null, "PlantUML produced no output. Check the diagram syntax.");
            }
            return new RenderedSvg(out.toString(StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            return new RenderedSvg(null, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
