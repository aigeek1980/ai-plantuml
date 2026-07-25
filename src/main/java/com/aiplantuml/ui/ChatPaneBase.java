package com.aiplantuml.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Shared scaffolding for a simple chat-style panel: a scrolling read-only history,
 * a single-line input, a send button, and a busy indicator. Subclasses implement
 * {@link #onSend(String)} to decide what happens with the submitted text.
 */
public abstract class ChatPaneBase extends BorderPane {

    private final TextArea history = new TextArea();
    private final TextField input = new TextField();
    private final Button sendButton;
    private final ProgressIndicator progress = new ProgressIndicator();

    protected ChatPaneBase(String buttonLabel, String promptText, String greeting) {
        history.setEditable(false);
        history.setWrapText(true);
        VBox.setVgrow(history, Priority.ALWAYS);
        setCenter(history);

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

        appendHistory(greeting);
    }

    public void applyBackground(String hex) {
        BackgroundUtil.applyBackground(this, hex);
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;

        appendHistory("You: " + text);
        input.clear();
        setBusy(true);
        onSend(text);
    }

    /**
     * Called with the trimmed, non-empty text the user submitted. Implementations
     * are responsible for calling {@link #setBusy(boolean)} and {@link #appendHistory(String)}
     * once their (presumably async) work completes.
     */
    protected abstract void onSend(String text);

    protected void setBusy(boolean busy) {
        progress.setVisible(busy);
        sendButton.setDisable(busy);
        input.setDisable(busy);
    }

    protected void appendHistory(String line) {
        history.appendText((history.getText().isEmpty() ? "" : "\n\n") + line);
    }
}
