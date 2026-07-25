package com.aiplantuml.ui;

import com.aiplantuml.ai.KimiClient;
import com.aiplantuml.config.AppConfig;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class ChatPane extends BorderPane {

    private final KimiClient kimiClient;
    private final Supplier<String> currentCodeSupplier;
    private final Consumer<String> onCodeUpdated;

    private final TextArea history = new TextArea();
    private final TextField input = new TextField();
    private final Button sendButton = new Button("Send");
    private final ProgressIndicator progress = new ProgressIndicator();

    public ChatPane(AppConfig config, Supplier<String> currentCodeSupplier, Consumer<String> onCodeUpdated) {
        this.kimiClient = new KimiClient(config);
        this.currentCodeSupplier = currentCodeSupplier;
        this.onCodeUpdated = onCodeUpdated;

        history.setEditable(false);
        history.setWrapText(true);
        VBox.setVgrow(history, Priority.ALWAYS);
        setCenter(history);

        input.setPromptText("Describe a change, e.g. \"add a third participant Carol\"");
        input.setOnAction(e -> send());
        sendButton.setOnAction(e -> send());

        progress.setVisible(false);
        progress.setPrefSize(20, 20);

        HBox inputRow = new HBox(6, input, sendButton, progress);
        inputRow.setPadding(new Insets(6));
        HBox.setHgrow(input, Priority.ALWAYS);
        setBottom(inputRow);

        appendHistory("Ask the AI to create or modify the diagram. Its replies replace the editor content (use Ctrl+Z to undo).");
    }

    public void applyBackground(String hex) {
        BackgroundUtil.applyBackground(this, hex);
    }

    private void send() {
        String instruction = input.getText().trim();
        if (instruction.isEmpty()) return;

        appendHistory("You: " + instruction);
        input.clear();
        setBusy(true);

        String currentCode = currentCodeSupplier.get();

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return kimiClient.requestEdit(currentCode, instruction);
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            String updatedCode = task.getValue();
            onCodeUpdated.accept(updatedCode);
            appendHistory("AI: diagram updated.");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = task.getException();
            appendHistory("Error: " + (ex != null ? ex.getMessage() : "unknown error"));
        });
        new Thread(task, "kimi-request").start();
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        sendButton.setDisable(busy);
        input.setDisable(busy);
    }

    private void appendHistory(String line) {
        history.appendText((history.getText().isEmpty() ? "" : "\n\n") + line);
    }
}
