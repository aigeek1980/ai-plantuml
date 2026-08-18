package com.aiplantuml.ui;

import com.aiplantuml.ai.KimiClient;
import com.aiplantuml.config.AppConfig;
import javafx.beans.property.DoubleProperty;
import javafx.concurrent.Task;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Chat tab that asks the AI to create/modify the diagram; each reply replaces the
 * editor content.
 */
public class ChatPane extends ChatPaneBase {

    private final KimiClient kimiClient;
    private final Supplier<String> currentCodeSupplier;
    private final Consumer<String> onCodeUpdated;

    public ChatPane(AppConfig config, Supplier<String> currentCodeSupplier, Consumer<String> onCodeUpdated,
                     DoubleProperty promptHeight) {
        super("Send", "Describe a change, e.g. \"add a third participant Carol\"",
                "Ask the AI to create or modify the diagram. Its replies replace the editor content (use Ctrl+Z to undo).",
                promptHeight);
        this.kimiClient = new KimiClient(config);
        this.currentCodeSupplier = currentCodeSupplier;
        this.onCodeUpdated = onCodeUpdated;
    }

    @Override
    protected void onSend(String instruction) {
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
            appendAiMessage("Diagram updated.");
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = task.getException();
            appendErrorMessage(ex != null ? ex.getMessage() : "unknown error");
        });
        new Thread(task, "kimi-request").start();
    }
}
