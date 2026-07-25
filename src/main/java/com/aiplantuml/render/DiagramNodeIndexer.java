package com.aiplantuml.render;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a "shadow" copy of a PlantUML source with invisible [[node://name]] link
 * annotations added to every declared/implied element, so PlantUML's image-map
 * (cmapx) output can be used to hit-test clicks against element names.
 * The visible diagram rendered from the original source is never touched.
 *
 * This is a best-effort heuristic (regex-based, not a full PlantUML parser):
 * reliable for sequence diagram participants/actors and for explicitly declared
 * elements in other diagram types; arrow-implied elements in non-sequence
 * diagrams may not be picked up.
 */
public class DiagramNodeIndexer {

    private static final String LINK_PREFIX = "node://";
    private static final Set<String> DECLARABLE_TYPES = Set.of(
            "participant", "actor", "boundary", "control", "entity",
            "database", "collections", "queue", "class", "interface",
            "object", "enum", "usecase");
    private static final Set<String> SKIP_LINE_PREFIXES = Set.of(
            "note", "hnote", "rnote", "alt", "else", "opt", "loop", "par",
            "break", "critical", "group", "end", "activate", "deactivate",
            "autonumber", "title", "skinparam", "return", "box", "newpage");

    private static final Pattern STARTUML = Pattern.compile("(?m)^\\s*@start\\w*.*$");
    private static final String IDENT = "(\"(?:[^\"\\\\]|\\\\.)*\"|[\\w.]+)";
    private static final Pattern DECLARATION = Pattern.compile(
            "^(\\s*)(" + String.join("|", DECLARABLE_TYPES) + ")\\s+" + IDENT + "(\\s+as\\s+([\\w.]+))?");
    private static final Pattern ARROW = Pattern.compile(
            "^\\s*" + IDENT + "\\s*[<o*]?[-.]{1,2}[\\[\\]#\\w]{0,12}[-.]{0,2}[>|]{0,2}\\s*" + IDENT + "\\b");

    private static final Pattern MINDMAP_START = Pattern.compile("(?im)^\\s*@start(mindmap|wbs)\\b");
    private static final Pattern MINDMAP_BULLET = Pattern.compile("^(\\s*)([*+_-]{1,10})(:)?\\s*(.*)$");
    private static final Pattern TRAILING_STYLE_TAG = Pattern.compile("(\\s*<<\\w+>>\\s*)+$");

    public record NodeArea(String name, double x1, double y1, double x2, double y2) {
        public boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }

    /**
     * @param shadowSource   source with [[node://name]] links injected, for cmap extraction
     * @param nodeLineNumbers name -> 0-based line number of its declaration/first use in the original source
     */
    public record IndexResult(String shadowSource, Map<String, Integer> nodeLineNumbers) {
    }

    public IndexResult index(String original) {
        Matcher startMatcher = STARTUML.matcher(original);
        if (!startMatcher.find()) {
            return new IndexResult(original, Map.of());
        }

        if (MINDMAP_START.matcher(original).find()) {
            return indexMindmap(original);
        }

        String[] lines = original.split("\n", -1);
        Set<String> declaredNames = new LinkedHashSet<>();
        List<String> implicitNamesInOrder = new ArrayList<>();
        List<String> outputLines = new ArrayList<>();
        Map<String, Integer> nodeLineNumbers = new LinkedHashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            String lower = trimmed.toLowerCase();
            boolean skip = trimmed.isEmpty() || trimmed.startsWith("@")
                    || lower.startsWith("'") || lower.startsWith("!")
                    || SKIP_LINE_PREFIXES.contains(firstWord(lower));

            if (!skip && !line.contains("[[")) {
                Matcher declMatcher = DECLARATION.matcher(line);
                if (declMatcher.find()) {
                    String alias = declMatcher.group(4) != null ? declMatcher.group(4).replaceFirst("(?i)^\\s*as\\s+", "") : null;
                    String rawName = stripQuotes(declMatcher.group(3));
                    String copyName = alias != null ? stripQuotes(alias) : rawName;
                    declaredNames.add(copyName);
                    nodeLineNumbers.putIfAbsent(copyName, i);
                    // Link must be inserted right after the declaration (before any trailing
                    // "#color"/stereotype) - PlantUML rejects "participant X #color [[link]]"
                    // but accepts "participant X [[link]] #color".
                    int insertPos = declMatcher.end();
                    line = line.substring(0, insertPos) + " [[" + LINK_PREFIX + urlEncode(copyName) + "]]" + line.substring(insertPos);
                } else {
                    Matcher arrowMatcher = ARROW.matcher(line);
                    if (arrowMatcher.find()) {
                        String left = stripQuotes(arrowMatcher.group(1));
                        String right = stripQuotes(arrowMatcher.group(2));
                        if (isPlausibleIdentifier(left) && !declaredNames.contains(left)) {
                            if (!implicitNamesInOrder.contains(left)) implicitNamesInOrder.add(left);
                            nodeLineNumbers.putIfAbsent(left, i);
                        }
                        if (isPlausibleIdentifier(right) && !declaredNames.contains(right)) {
                            if (!implicitNamesInOrder.contains(right)) implicitNamesInOrder.add(right);
                            nodeLineNumbers.putIfAbsent(right, i);
                        }
                    }
                }
            }
            outputLines.add(line);
        }

        StringBuilder header = new StringBuilder();
        for (String name : implicitNamesInOrder) {
            header.append("participant \"").append(escapeQuotes(name)).append("\" as ")
                    .append(sanitizeAlias(name))
                    .append(" [[").append(LINK_PREFIX).append(urlEncode(name)).append("]]\n");
        }

        String body = String.join("\n", outputLines);
        int insertAt = startMatcher.end();
        String shadowSource = body.substring(0, insertAt) + "\n" + header + body.substring(insertAt);
        return new IndexResult(shadowSource, nodeLineNumbers);
    }

    /**
     * Mindmap/WBS diagrams use bullet-depth syntax ("*", "**", ...) instead of
     * declarations and arrows. A node is either a single line ("* label <<style>>")
     * or a multi-line CREOLE block ("**: label" ... terminated by a ";" line).
     * Only the label line needs a link annotation; interior/terminator lines are
     * left untouched since they don't start with a bullet marker.
     * <p>
     * Unlike declarations elsewhere, a plain "text [[url]]" here is parsed as
     * inline CREOLE markup and the raw URL becomes visible node text. The fix is
     * CREOLE's two-part link syntax "[[url displaytext]]", which replaces the
     * label with a link styled span showing exactly the original text - but the
     * displaytext portion must have "[", "]" and "~" escaped, since those are
     * CREOLE's own markup/escape characters and unescaped brackets (this diagram's
     * labels are full of "[New]"/"[Reuse]" tags) breaks the link parse entirely.
     */
    private IndexResult indexMindmap(String original) {
        String[] lines = original.split("\n", -1);
        List<String> outputLines = new ArrayList<>();
        Map<String, Integer> nodeLineNumbers = new LinkedHashMap<>();
        Map<String, Integer> nameCounts = new java.util.HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            boolean skip = trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("'") || line.contains("[[");

            if (!skip) {
                Matcher bulletMatcher = MINDMAP_BULLET.matcher(line);
                if (bulletMatcher.matches() && !bulletMatcher.group(2).isEmpty()) {
                    String leadingWs = bulletMatcher.group(1);
                    String bullet = bulletMatcher.group(2);
                    String colon = bulletMatcher.group(3) != null ? bulletMatcher.group(3) : "";
                    String rest = bulletMatcher.group(4);

                    Matcher styleMatcher = TRAILING_STYLE_TAG.matcher(rest);
                    String label;
                    String trailingStyle;
                    if (styleMatcher.find()) {
                        label = rest.substring(0, styleMatcher.start()).strip();
                        trailingStyle = rest.substring(styleMatcher.start());
                    } else {
                        label = rest.strip();
                        trailingStyle = "";
                    }

                    if (!label.isEmpty()) {
                        String name = disambiguate(label, nameCounts);
                        nodeLineNumbers.put(name, i);
                        line = leadingWs + bullet + colon + " [[" + LINK_PREFIX + urlEncode(name)
                                + " " + escapeCreole(label) + "]]" + trailingStyle;
                    }
                }
            }
            outputLines.add(line);
        }

        return new IndexResult(String.join("\n", outputLines), nodeLineNumbers);
    }

    private String disambiguate(String name, Map<String, Integer> nameCounts) {
        int count = nameCounts.merge(name, 1, Integer::sum);
        return count == 1 ? name : name + " (" + count + ")";
    }

    private String escapeCreole(String s) {
        return s.replace("~", "~~").replace("[", "~[").replace("]", "~]");
    }

    public List<NodeArea> parseCMap(String cmapHtml) {
        List<NodeArea> areas = new ArrayList<>();
        if (cmapHtml == null || cmapHtml.isBlank()) return areas;

        Pattern areaTag = Pattern.compile("<area[^>]*href=\"" + Pattern.quote(LINK_PREFIX) + "([^\"]*)\"[^>]*coords=\"([^\"]*)\"[^>]*/?>");
        Matcher m = areaTag.matcher(cmapHtml);
        while (m.find()) {
            String encodedName = m.group(1);
            String[] coords = m.group(2).split(",");
            if (coords.length != 4) continue;
            try {
                String name = java.net.URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
                double x1 = Double.parseDouble(coords[0].trim());
                double y1 = Double.parseDouble(coords[1].trim());
                double x2 = Double.parseDouble(coords[2].trim());
                double y2 = Double.parseDouble(coords[3].trim());
                areas.add(new NodeArea(name, x1, y1, x2, y2));
            } catch (NumberFormatException ignored) {
                // skip malformed area entry
            }
        }
        return areas;
    }

    private String firstWord(String lowerTrimmedLine) {
        int end = 0;
        while (end < lowerTrimmedLine.length() && !Character.isWhitespace(lowerTrimmedLine.charAt(end))) {
            end++;
        }
        return lowerTrimmedLine.substring(0, end);
    }

    private boolean isPlausibleIdentifier(String s) {
        return s != null && !s.isBlank() && s.matches("[\\w.\" ]+");
    }

    private String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String escapeQuotes(String s) {
        return s.replace("\"", "\\\"");
    }

    private String sanitizeAlias(String s) {
        String alias = s.replaceAll("[^\\w]", "_");
        if (alias.isEmpty() || Character.isDigit(alias.charAt(0))) {
            alias = "n_" + alias;
        }
        return alias;
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
