package com.aiplantuml.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlantUmlHighlighter {

    private static final String[] KEYWORDS = {
            "participant", "actor", "boundary", "control", "entity", "database",
            "collections", "queue", "class", "interface", "object", "enum", "usecase",
            "abstract", "static", "extends", "implements", "package", "namespace",
            "box", "end", "endbox", "as", "loop", "alt", "else", "opt", "group",
            "break", "critical", "par", "ref", "note", "endnote", "left", "right",
            "over", "of", "activate", "deactivate", "destroy", "create",
            "autonumber", "title", "skinparam", "style", "hide", "show",
            "newpage", "header", "footer", "legend", "endlegend", "top", "bottom", "center"
    };
    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String COMMENT_PATTERN = "(?m)^\\s*'.*$";
    private static final String STEREOTYPE_PATTERN = "<<[^>]*>>";
    private static final String DIRECTIVE_PATTERN = "@\\w+";
    private static final String COLOR_PATTERN = "#(?:[A-Fa-f0-9]{3,8}|[A-Za-z]+)\\b";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>" + COMMENT_PATTERN + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<STEREOTYPE>" + STEREOTYPE_PATTERN + ")"
                    + "|(?<DIRECTIVE>" + DIRECTIVE_PATTERN + ")"
                    + "|(?<COLOR>" + COLOR_PATTERN + ")"
                    + "|(?<KEYWORD>" + KEYWORD_PATTERN + ")"
    );

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass =
                    matcher.group("COMMENT") != null ? "puml-comment" :
                    matcher.group("STRING") != null ? "puml-string" :
                    matcher.group("STEREOTYPE") != null ? "puml-stereotype" :
                    matcher.group("DIRECTIVE") != null ? "puml-directive" :
                    matcher.group("COLOR") != null ? "puml-color" :
                    matcher.group("KEYWORD") != null ? "puml-keyword" :
                    null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastEnd);
        return spansBuilder.create();
    }
}
