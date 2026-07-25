package com.aiplantuml.ui;

import com.aiplantuml.ai.KimiClient;
import com.aiplantuml.config.AppConfig;
import javafx.concurrent.Task;

import java.util.function.Supplier;

/**
 * Chat tab for asking questions about the current diagram (e.g. "what happens after
 * the payment call?"). Answers are informational only and never modify the diagram.
 */
public class QuestionPane extends ChatPaneBase {

    private final KimiClient kimiClient;
    private final Supplier<String> currentCodeSupplier;

    public QuestionPane(AppConfig config, Supplier<String> currentCodeSupplier) {
        super("Ask", "Ask about the diagram, e.g. \"what happens after the payment call?\"",
                "Ask questions about the current diagram. This won't change your diagram.");
        this.kimiClient = new KimiClient(config);
        this.currentCodeSupplier = currentCodeSupplier;
    }

    @Override
    protected void onSend(String question) {
        String currentCode = currentCodeSupplier.get();

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return kimiClient.askQuestion(currentCode, question);
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            appendAiMessage(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = task.getException();
            appendErrorMessage(ex != null ? ex.getMessage() : "unknown error");
        });
        new Thread(task, "kimi-question").start();
    }
}
