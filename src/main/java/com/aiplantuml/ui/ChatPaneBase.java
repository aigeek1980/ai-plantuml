package com.aiplantuml.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared scaffolding for a simple chat-style panel: a scrolling list of sender-labeled
 * message entries, a single-line input, a send button, and a busy indicator. Subclasses
 * implement {@link #onSend(String)} to decide what happens with the submitted text.
 */
public abstract class ChatPaneBase extends BorderPane {

    private static final Pattern INLINE_MARKDOWN = Pattern.compile("\\*\\*(.+?)\\*\\*|`(.+?)`");

    private static final String YOU_COLOR = "#2B6CB0";
    private static final String AI_COLOR = "#6B46C1";
    private static final String ERROR_COLOR = "#C0392B";
    private static final String SYSTEM_COLOR = "#6B7280";

    private final VBox messageList = new VBox(10);
    private final ScrollPane historyScroll = new ScrollPane(messageList);
    private final TextField input = new TextField();
    private final Button sendButton;
    private final ProgressIndicator progress = new ProgressIndicator();

    protected ChatPaneBase(String buttonLabel, String promptText, String greeting) {
        messageList.setPadding(new Insets(8));
        historyScroll.setFitToWidth(true);
        VBox.setVgrow(historyScroll, Priority.ALWAYS);
        setCenter(historyScroll);

        input.setPromptText(promptText);
        input.setOnAction(e -> send());

        sendButton = new Button(buttonLabel);
        sendButton.setOnAction(e -> send());

        progress.setVisible(false);
        progress.setPrefSize(20, 20);

        HBox inputRow = new HBox(6, input, sendButton, progress);
        inputRow.setPadding(new Insets(6));
        HBox.setHgrow(input, Priority.ALWAYS);
        setBottom(inputRow);

        appendSystemMessage(greeting);
    }

    public void applyBackground(String hex) {
        BackgroundUtil.applyBackground(this, hex);
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
        Text note = new Text(text);
        note.setStyle("-fx-fill: " + SYSTEM_COLOR + "; -fx-font-style: italic;");
        TextFlow flow = new TextFlow(note);
        flow.setMaxWidth(Double.MAX_VALUE);
        addEntry(flow);
    }

    private void appendMessage(String sender, String color, String text) {
        Label senderLabel = new Label(sender);
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + "; -fx-font-size: 12px;");

        TextFlow contentFlow = renderInlineMarkdown(text);
        contentFlow.setMaxWidth(Double.MAX_VALUE);

        VBox entry = new VBox(3, senderLabel, contentFlow);
        addEntry(entry);
    }

    private void addEntry(javafx.scene.Node node) {
        messageList.getChildren().add(node);
        Platform.runLater(() -> historyScroll.setVvalue(1.0));
    }

    private TextFlow renderInlineMarkdown(String text) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        Matcher matcher = INLINE_MARKDOWN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                flow.getChildren().add(new Text(text.substring(lastEnd, matcher.start())));
            }
            if (matcher.group(1) != null) {
                Text bold = new Text(matcher.group(1));
                bold.setStyle("-fx-font-weight: bold;");
                flow.getChildren().add(bold);
            } else if (matcher.group(2) != null) {
                Text code = new Text(matcher.group(2));
                code.setStyle("-fx-font-family: 'Consolas', monospace; -fx-fill: #A31515;");
                flow.getChildren().add(code);
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            flow.getChildren().add(new Text(text.substring(lastEnd)));
        }
        return flow;
    }
}
