package com.aiplantuml.play;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The alt/else structure of a sequence diagram, so playback can ask which branch to
 * follow instead of running every branch one after another.
 * <p>
 * Only the blocks that represent a choice are tracked. The others still have to be
 * recognised, because they are closed by the same "end" and would otherwise unbalance
 * the nesting.
 */
public final class BlockModel {

    /** Which branch of which block a step sits in. */
    public record Choice(int blockId, int branchIndex) {
    }

    /** Block kinds whose branches the user picks between. */
    private static final Set<String> CHOICE_KINDS = Set.of("alt", "opt");
    /** Block kinds that merely need their "end" accounted for. */
    private static final Set<String> OTHER_BLOCK_KINDS = Set.of("loop", "par", "group", "critical", "break");
    /** Second words that make an "end" close something other than a block. */
    private static final Set<String> NON_BLOCK_ENDINGS = Set.of("box", "note", "title", "header", "footer", "legend");
    private static final Set<String> NOTE_KEYWORDS = Set.of("note", "hnote", "rnote");
    private static final Set<String> NOTE_POSITIONS = Set.of("left", "right", "over", "top", "bottom", "across");

    private final Map<Integer, List<Choice>> pathByLine = new HashMap<>();
    private final List<String> blockKinds = new ArrayList<>();
    private final List<List<String>> branchLabels = new ArrayList<>();

    private BlockModel() {
    }

    public static BlockModel parse(String source) {
        BlockModel model = new BlockModel();
        model.scan(source.split("\n", -1));
        return model;
    }

    private void scan(String[] lines) {
        Deque<Integer> open = new ArrayDeque<>();
        boolean insideStyle = false;
        boolean insideNote = false;
        boolean insideComment = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            String first = firstWord(lower);

            // A /' ... '/ comment often holds a whole block kept for later, whose "end"
            // would otherwise close a block that was never opened.
            if (insideComment) {
                if (trimmed.contains("'/")) insideComment = false;
                continue;
            }
            if (trimmed.startsWith("/'")) {
                insideComment = !trimmed.contains("'/");
                continue;
            }

            // The same traps as elsewhere: a <style> block's rules are named after
            // diagram keywords ("group {"), and a note's body is free text.
            if (insideStyle) {
                if (lower.contains("</style>")) insideStyle = false;
                continue;
            }
            if (lower.startsWith("<style")) {
                insideStyle = !lower.contains("</style>");
                continue;
            }
            if (insideNote) {
                if (first.equals("end")) insideNote = false;
                continue;
            }
            if (NOTE_KEYWORDS.contains(first) && opensMultiLineNote(lower)) {
                insideNote = true;
                continue;
            }

            if (CHOICE_KINDS.contains(first) || OTHER_BLOCK_KINDS.contains(first)) {
                int id = blockKinds.size();
                blockKinds.add(first);
                branchLabels.add(new ArrayList<>(List.of(labelAfter(trimmed, first))));
                open.push(id);
                continue;
            }
            if (first.equals("else") && !open.isEmpty()) {
                branchLabels.get(open.peek()).add(labelAfter(trimmed, first));
                continue;
            }
            if (first.equals("end")) {
                String second = firstWord(lower.substring(first.length()).strip());
                if (!NON_BLOCK_ENDINGS.contains(second) && !open.isEmpty()) {
                    open.pop();
                }
                continue;
            }

            pathByLine.put(i, currentPath(open));
        }
    }

    /** Outermost first, so a decision is asked for before the ones nested inside it. */
    private List<Choice> currentPath(Deque<Integer> open) {
        List<Choice> path = new ArrayList<>();
        List<Integer> outermostFirst = new ArrayList<>(open);
        java.util.Collections.reverse(outermostFirst);
        for (int id : outermostFirst) {
            if (!CHOICE_KINDS.contains(blockKinds.get(id))) continue;
            path.add(new Choice(id, branchLabels.get(id).size() - 1));
        }
        return path;
    }

    private static String labelAfter(String trimmed, String keyword) {
        String rest = trimmed.substring(keyword.length()).strip();
        return rest.isEmpty() ? "(unlabelled)" : rest;
    }

    private static boolean opensMultiLineNote(String lower) {
        if (lower.contains(":")) return false;
        String rest = lower.substring(firstWord(lower).length()).strip();
        return NOTE_POSITIONS.contains(firstWord(rest));
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return text.substring(0, end);
    }

    /** The branch path a source line sits in; empty when it is not inside any choice. */
    public List<Choice> pathFor(int lineIndex) {
        return pathByLine.getOrDefault(lineIndex, List.of());
    }

    public String kindOf(int blockId) {
        return blockKinds.get(blockId);
    }

    public List<String> branchesOf(int blockId) {
        return branchLabels.get(blockId);
    }
}
