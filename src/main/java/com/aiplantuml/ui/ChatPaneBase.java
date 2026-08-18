package com.aiplantuml.ui;

import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared scaffolding for a simple chat-style panel: a scrolling, selectable/copyable
 * history of sender-labeled messages, a multi-line input (Enter to send, Shift+Enter
 * for a newline) with a height slider, a send button, and a busy indicator. Subclasses
 * implement {@link #onSend(String)} to decide what happens with the submitted text.
 * <p>
 * The history is an InlineCssTextArea (read-only) rather than plain Text/TextFlow
 * nodes, since RichTextFX's styled text areas support both per-range inline styling
 * (bold sender labels, markdown) and normal click-drag text selection + Ctrl+C, which
 * plain TextFlow does not.
 */
public abstract class ChatPaneBase extends BorderPane {

    private static final Pattern INLINE_MARKDOWN = Pattern.compile("\\*\\*(.+?)\\*\\*|`(.+?)`");

    private static final String YOU_COLOR = "#2B6CB0";
    private static final String AI_COLOR = "#6B46C1";
    private static final String ERROR_COLOR = "#C0392B";
    private static final String SYSTEM_COLOR = "#6B7280";

    private static final double MIN_PROMPT_HEIGHT = 50;
    private static final double MAX_PROMPT_HEIGHT = 300;

    private final InlineCssTextArea history = new InlineCssTextArea();
    private final TextArea input = new TextArea();
    private final Button sendButton;
    private final ProgressIndicator progress = new ProgressIndicator();

    /**
     * @param promptHeight shared, persisted input height - bound bidirectionally to
     *                      this pane's slider and its input's height, so dragging the
     *                      slider in either AI tab keeps both in sync and survives restarts
     *                      (the caller is responsible for loading/saving its value).
     */
    protected ChatPaneBase(String buttonLabel, String promptText, String greeting, DoubleProperty promptHeight) {
        history.setEditable(false);
        history.setWrapText(true);
        history.setStyle("-fx-font-family: 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        VirtualizedScrollPane<InlineCssTextArea> historyScroll = new VirtualizedScrollPane<>(history);
        setCenter(historyScroll);

        input.setPromptText(promptText);
        input.setWrapText(true);
        input.prefHeightProperty().bind(promptHeight);
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            }
        });

        sendButton = new Button(buttonLabel);
        sendButton.setOnAction(e -> send());

        progress.setVisible(false);
        progress.setPrefSize(20, 20);

        Slider heightSlider = new Slider(MIN_PROMPT_HEIGHT, MAX_PROMPT_HEIGHT, promptHeight.get());
        heightSlider.setPrefWidth(120);
        heightSlider.valueProperty().bindBidirectional(promptHeight);

        HBox sliderRow = new HBox(6, new Label("Prompt height:"), heightSlider);
        sliderRow.setAlignment(Pos.CENTER_LEFT);
        sliderRow.setPadding(new Insets(4, 6, 0, 6));

        HBox inputRow = new HBox(6, input, sendButton, progress);
        inputRow.setAlignment(Pos.BOTTOM_LEFT);
        inputRow.setPadding(new Insets(4, 6, 6, 6));
        HBox.setHgrow(input, Priority.ALWAYS);

        setBottom(new VBox(sliderRow, inputRow));

        appendSystemMessage(greeting);
    }

    public void applyBackground(String hex) {
        BackgroundUtil.applyBackground(this, hex);
        BackgroundUtil.applyBackground(history, hex);
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;

        appendMessage("You", YOU_COLOR, text);
        input.clear();
        setBusy(true);
        onSend(text);
    }

    /**
     * Called with the trimmed, non-empty text the user submitted. Implementations
     * are responsible for calling {@link #setBusy(boolean)} and appending a reply
     * (via {@link #appendAiMessage}/{@link #appendErrorMessage}) once their
     * (presumably async) work completes.
     */
    protected abstract void onSend(String text);

    protected void setBusy(boolean busy) {
        progress.setVisible(busy);
        sendButton.setDisable(busy);
        input.setDisable(busy);
    }

    protected void appendAiMessage(String text) {
        appendMessage("AI", AI_COLOR, text);
    }

    protected void appendErrorMessage(String text) {
        appendMessage("Error", ERROR_COLOR, text);
    }

    protected void appendSystemMessage(String text) {
        appendStyledText(text + "\n\n", "-fx-fill: " + SYSTEM_COLOR + "; -fx-font-style: italic;");
        scrollToEnd();
    }

    private void appendMessage(String sender, String color, String text) {
        if (!history.getText().isEmpty()) {
            appendStyledText("\n", "");
        }
        appendStyledText(sender + "\n", "-fx-fill: " + color + "; -fx-font-weight: bold;");
        appendInlineMarkdown(text);
        appendStyledText("\n", "");
        scrollToEnd();
    }

    private void appendInlineMarkdown(String text) {
        Matcher matcher = INLINE_MARKDOWN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                appendStyledText(text.substring(lastEnd, matcher.start()), "");
            }
            if (matcher.group(1) != null) {
                appendStyledText(matcher.group(1), "-fx-font-weight: bold;");
            } else if (matcher.group(2) != null) {
                appendStyledText(matcher.group(2), "-fx-font-family: 'Consolas', monospace; -fx-fill: #A31515;");
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            appendStyledText(text.substring(lastEnd), "");
        }
    }

    private void appendStyledText(String text, String css) {
        int start = history.getLength();
        history.appendText(text);
        if (!css.isEmpty()) {
            history.setStyle(start, history.getLength(), css);
        }
    }

    private void scrollToEnd() {
        history.moveTo(history.getLength());
        history.requestFollowCaret();
    }
}
