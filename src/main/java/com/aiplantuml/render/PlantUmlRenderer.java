package com.aiplantuml.render;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

import java.io.ByteArrayOutputStream;
import java.util.List;
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

    public record RenderResult(byte[] png, String errorText, List<DiagramNodeIndexer.NodeArea> nodeAreas,
                                Map<String, Integer> nodeLineNumbers) {
        public boolean isError() {
            return errorText != null;
        }
    }

    /**
     * Renders the diagram and, when possible, also returns clickable node hit-areas.
     * <p>
     * Node areas require adding invisible [[link]] annotations to a "shadow" copy of
     * the source (see DiagramNodeIndexer). For most diagram types this shadow copy
     * renders pixel-identical to the original, but some diagram types (e.g. mindmap)
     * allocate different layout space once any element becomes linked. To guarantee
     * the returned coordinates always match the returned PNG exactly, the *shadow*
     * render (not the original) is what gets displayed whenever it renders
     * successfully; the plain original is only used as a fallback if the shadow
     * source somehow fails to render (a bug in the heuristic link-injection) while
     * the user's original source is valid.
     */
    public RenderResult render(String plantUmlSource) {
        Rendered original = tryRender(plantUmlSource);
        if (original.png == null) {
            return new RenderResult(null, original.error, List.of(), Map.of());
        }

        DiagramNodeIndexer.IndexResult indexResult;
        try {
            indexResult = nodeIndexer.index(plantUmlSource);
        } catch (Exception e) {
            return new RenderResult(original.png, null, List.of(), Map.of());
        }

        Rendered shadow = tryRender(indexResult.shadowSource());
        if (shadow.png == null) {
            // Our link injection broke otherwise-valid syntax; show the diagram without node interactivity.
            return new RenderResult(original.png, null, List.of(), Map.of());
        }

        List<DiagramNodeIndexer.NodeArea> areas = nodeIndexer.parseCMap(shadow.cmap);
        return new RenderResult(shadow.png, null, areas, indexResult.nodeLineNumbers());
    }

    private record Rendered(byte[] png, String cmap, String error) {
    }

    private Rendered tryRender(String source) {
        SourceStringReader reader = new SourceStringReader(source);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var description = reader.outputImage(out, new FileFormatOption(FileFormat.PNG));
            if (description == null) {
                return new Rendered(null, null, "PlantUML produced no output. Check the diagram syntax.");
            }
            String cmap;
            try {
                cmap = reader.getCMapData(0, new FileFormatOption(FileFormat.PNG));
            } catch (Exception e) {
                cmap = null;
            }
            return new Rendered(out.toByteArray(), cmap, null);
        } catch (Exception e) {
            return new Rendered(null, null, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
