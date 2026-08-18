package com.aiplantuml.ui;

import com.aiplantuml.config.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

public class SettingsDialog extends Dialog<Boolean> {

    public SettingsDialog(AppConfig config) {
        setTitle("Settings");
        setHeaderText("Stored locally in ~/.aiplantuml/config.properties");

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(config.getApiKey());
        apiKeyField.setPrefWidth(320);

        TextField baseUrlField = new TextField(config.getBaseUrl());
        TextField modelField = new TextField(config.getModel());
        TextField timeoutField = new TextField(String.valueOf(config.getAiTimeoutSeconds()));
        timeoutField.setPrefWidth(80);
        timeoutField.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,4}") ? change : null));

        ColorPicker editorColorPicker = new ColorPicker(Color.web(config.getEditorBackground()));
        ColorPicker diagramColorPicker = new ColorPicker(Color.web(config.getDiagramBackground()));
        ColorPicker chatColorPicker = new ColorPicker(Color.web(config.getChatBackground()));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        grid.addRow(row++, new Label("API Key:"), apiKeyField);
        grid.addRow(row++, new Label("Base URL:"), baseUrlField);
        grid.addRow(row++, new Label("Model:"), modelField);
        grid.addRow(row++, new Label("AI request timeout (seconds):"), timeoutField);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(new Label("Pane background colors"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Editor:"), editorColorPicker);
        grid.addRow(row++, new Label("Diagram:"), diagramColorPicker);
        grid.addRow(row++, new Label("AI chat:"), chatColorPicker);

        TextArea tablePromptField = promptArea(config.getExportTablePrompt());
        TextArea detailedPromptField = promptArea(config.getExportDetailedPrompt());
        TextArea summaryPromptField = promptArea(config.getExportSummaryPrompt());

        VBox content = new VBox(14,
                grid,
                new Separator(),
                new Label("AI export prompts"),
                promptSection("Steps table (MD):", tablePromptField,
                        () -> tablePromptField.setText(AppConfig.DEFAULT_EXPORT_TABLE_PROMPT)),
                promptSection("Detailed overview (MD):", detailedPromptField,
                        () -> detailedPromptField.setText(AppConfig.DEFAULT_EXPORT_DETAILED_PROMPT)),
                promptSection("Summary (MD):", summaryPromptField,
                        () -> summaryPromptField.setText(AppConfig.DEFAULT_EXPORT_SUMMARY_PROMPT))
        );
        content.setPadding(new Insets(20, 20, 10, 20));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(520);

        getDialogPane().setContent(scrollPane);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                config.setApiKey(apiKeyField.getText());
                config.setBaseUrl(baseUrlField.getText());
                config.setModel(modelField.getText());
                config.setAiTimeoutSeconds(parseTimeoutOrDefault(timeoutField.getText()));
                config.setEditorBackground(toHex(editorColorPicker.getValue()));
                config.setDiagramBackground(toHex(diagramColorPicker.getValue()));
                config.setChatBackground(toHex(chatColorPicker.getValue()));
                config.setExportTablePrompt(tablePromptField.getText());
                config.setExportDetailedPrompt(detailedPromptField.getText());
                config.setExportSummaryPrompt(summaryPromptField.getText());
                config.save();
                return true;
            }
            return false;
        });
    }

    private static int parseTimeoutOrDefault(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : AppConfig.DEFAULT_AI_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return AppConfig.DEFAULT_AI_TIMEOUT_SECONDS;
        }
    }

    private TextArea promptArea(String text) {
        TextArea area = new TextArea(text);
        area.setWrapText(true);
        area.setPrefRowCount(4);
        return area;
    }

    private VBox promptSection(String label, TextArea area, Runnable onReset) {
        Button resetButton = new Button("Reset to default");
        resetButton.setOnAction(e -> onReset.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(new Label(label), spacer, resetButton);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox section = new VBox(4, header, area);
        return section;
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
