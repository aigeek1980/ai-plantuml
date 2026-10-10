package com.aiplantuml.play;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a sequence diagram as a script to be run rather than a picture to be shown:
 * its setup, its participants, and the statements in the order they execute.
 * <p>
 * Statements are kept as their original text and re-emitted verbatim, so colours,
 * arrow styles and labels survive without being understood, let alone reconstructed.
 */
public final class ScriptModel {

    /** One executable item: either a line to emit, or a block to choose a way through. */
    public sealed interface Node permits Line, Block {
    }

    public record Line(String text, int sourceLine, Set<String> aliases) implements Node {
    }

    public record Branch(String label, List<Node> body) {
    }

    public record Block(String kind, List<Branch> branches, int sourceLine) implements Node {
    }

    /**
     * Participants as declared, keeping any surrounding box. A box is only drawn when
     * one of its members has appeared, and then only with the members that have.
     */
    public record Group(String boxHeader, String boxEnd, List<Member> members) {
    }

    public record Member(String alias, String declaration) {
    }

    private static final Set<String> CHOICE_KINDS = Set.of("alt", "opt");
    private static final Set<String> LOOP_KINDS = Set.of("loop");
    private static final Set<String> PLAIN_BLOCK_KINDS = Set.of("par", "group", "critical", "break");
    private static final Set<String> NON_BLOCK_ENDINGS = Set.of("box", "note", "title", "header", "footer", "legend");
    private static final Set<String> NOTE_KEYWORDS = Set.of("note", "hnote", "rnote");
    private static final Set<String> NOTE_POSITIONS = Set.of("left", "right", "over", "top", "bottom", "across");
    private static final Set<String> SETUP_KEYWORDS = Set.of(
            "skinparam", "autonumber", "title", "header", "footer", "hide", "show", "scale", "!pragma");
    private static final Pattern DECLARATION = Pattern.compile(
            "^(participant|actor|boundary|control|entity|database|collections|queue)\\s+"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|[\\w.]+)(\\s+as\\s+([\\w.]+))?",
            Pattern.CASE_INSENSITIVE);

    private final List<String> setup = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<Node> body = new ArrayList<>();

    private ScriptModel() {
    }

    public static ScriptModel parse(String source) {
        ScriptModel model = new ScriptModel();
        model.scan(source.split("\n", -1));
        return model;
    }

    private void scan(String[] lines) {
        Set<String> aliases = new LinkedHashSet<>();
        Group openBox = null;
        boolean insideStyle = false;
        boolean insideNote = false;
        List<String> noteBuffer = null;

        Deque<List<Node>> target = new ArrayDeque<>();
        Deque<Block> openBlocks = new ArrayDeque<>();
        target.push(body);
        boolean insideComment = false;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            String first = firstWord(lower);

            // PlantUML's /' ... '/ comments out whole passages, and what is in there is
            // often a block of real-looking diagram text kept for later. Left unhandled,
            // it is drawn as though it were part of the flow, and its "end" lines close
            // blocks that were never opened.
            if (insideComment) {
                if (trimmed.contains("'/")) insideComment = false;
                continue;
            }
            if (trimmed.startsWith("/'")) {
                insideComment = !trimmed.contains("'/");
                continue;
            }

            // A <style> block belongs to the setup wholesale: its rules are named after
            // diagram keywords and would otherwise read as statements.
            if (insideStyle) {
                setup.add(raw);
                if (lower.contains("</style>")) insideStyle = false;
                continue;
            }
            if (lower.startsWith("<style")) {
                setup.add(raw);
                insideStyle = !lower.contains("</style>");
                continue;
            }

            // A note's body is free text and has to travel with its opening line.
            if (insideNote) {
                noteBuffer.add(raw);
                if (first.equals("end")) {
                    target.peek().add(lineNode(String.join("\n", noteBuffer), i, aliases));
                    insideNote = false;
                    noteBuffer = null;
                }
                continue;
            }

            if (trimmed.isEmpty() || trimmed.startsWith("@")) continue;
            if (trimmed.startsWith("'")) continue;

            if (NOTE_KEYWORDS.contains(first)) {
                if (opensMultiLineNote(lower)) {
                    insideNote = true;
                    noteBuffer = new ArrayList<>(List.of(raw));
                } else {
                    target.peek().add(lineNode(raw, i, aliases));
                }
                continue;
            }

            if (first.equals("box")) {
                openBox = new Group(raw, null, new ArrayList<>());
                continue;
            }
            if (first.equals("end") && firstWord(lower.substring(3).strip()).equals("box")) {
                if (openBox != null) {
                    groups.add(new Group(openBox.boxHeader(), raw, openBox.members()));
                    openBox = null;
                }
                continue;
            }

            Matcher decl = DECLARATION.matcher(trimmed);
            if (decl.find()) {
                String alias = decl.group(4) != null ? decl.group(4) : stripQuotes(decl.group(2));
                aliases.add(alias);
                if (openBox != null) {
                    openBox.members().add(new Member(alias, raw));
                } else {
                    groups.add(new Group(null, null, new ArrayList<>(List.of(new Member(alias, raw)))));
                }
                continue;
            }

            if (SETUP_KEYWORDS.contains(first) || trimmed.startsWith("!")) {
                setup.add(raw);
                continue;
            }

            if (CHOICE_KINDS.contains(first) || LOOP_KINDS.contains(first) || PLAIN_BLOCK_KINDS.contains(first)) {
                Branch branch = new Branch(labelAfter(trimmed, first), new ArrayList<>());
                Block block = new Block(first, new ArrayList<>(List.of(branch)), i);
                target.peek().add(block);
                openBlocks.push(block);
                target.push(branch.body());
                continue;
            }
            if (first.equals("else") && !openBlocks.isEmpty()) {
                Branch branch = new Branch(labelAfter(trimmed, first), new ArrayList<>());
                openBlocks.peek().branches().add(branch);
                target.pop();
                target.push(branch.body());
                continue;
            }
            if (first.equals("end")) {
                String second = firstWord(lower.substring(3).strip());
                if (!NON_BLOCK_ENDINGS.contains(second) && !openBlocks.isEmpty()) {
                    openBlocks.pop();
                    target.pop();
                }
                continue;
            }

            target.peek().add(lineNode(raw, i, aliases));
        }
    }

    /**
     * Which participants a statement brings on stage. Only the part before the first
     * colon is considered - an alias mentioned inside a message's text is being talked
     * about, not talked to, and would otherwise drag a lifeline in early.
     */
    private Line lineNode(String text, int sourceLine, Set<String> knownAliases) {
        String head = text;
        int colon = head.indexOf(':');
        if (colon >= 0) head = head.substring(0, colon);

        Set<String> used = new LinkedHashSet<>();
        for (String alias : knownAliases) {
            if (containsWord(head, alias)) used.add(alias);
        }
        return new Line(text, sourceLine, used);
    }

    private static boolean containsWord(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) return false;
            boolean leftOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
            int after = at + word.length();
            boolean rightOk = after >= haystack.length() || !isWordChar(haystack.charAt(after));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
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

    public List<String> setup() {
        return setup;
    }

    public List<Group> groups() {
        return groups;
    }

    public List<Node> body() {
        return body;
    }

    /** Renders a trace: the setup, the participants reached so far, then the statements run. */
    public String toSource(String body, Set<String> aliasesOnStage) {
        StringBuilder out = new StringBuilder("@startuml\n");
        for (String line : setup) out.append(line).append('\n');

        for (Group group : groups) {
            List<Member> present = group.members().stream()
                    .filter(m -> aliasesOnStage.contains(m.alias()))
                    .toList();
            if (present.isEmpty()) continue;
            if (group.boxHeader() != null) out.append(group.boxHeader()).append('\n');
            for (Member member : present) out.append(member.declaration()).append('\n');
            if (group.boxEnd() != null) out.append(group.boxEnd()).append('\n');
        }

        out.append(body);
        out.append("@enduml\n");
        return out.toString();
    }
}
