package com.aiplantuml.ui;

import com.aiplantuml.config.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
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

        ColorPicker editorColorPicker = new ColorPicker(Color.web(config.getEditorBackground()));
        ColorPicker diagramColorPicker = new ColorPicker(Color.web(config.getDiagramBackground()));
        ColorPicker chatColorPicker = new ColorPicker(Color.web(config.getChatBackground()));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));
        int row = 0;
        grid.addRow(row++, new Label("API Key:"), apiKeyField);
        grid.addRow(row++, new Label("Base URL:"), baseUrlField);
        grid.addRow(row++, new Label("Model:"), modelField);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(new Label("Pane background colors"), 0, row++, 2, 1);
        grid.addRow(row++, new Label("Editor:"), editorColorPicker);
        grid.addRow(row++, new Label("Diagram:"), diagramColorPicker);
        grid.addRow(row++, new Label("AI chat:"), chatColorPicker);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                config.setApiKey(apiKeyField.getText());
                config.setBaseUrl(baseUrlField.getText());
                config.setModel(modelField.getText());
                config.setEditorBackground(toHex(editorColorPicker.getValue()));
                config.setDiagramBackground(toHex(diagramColorPicker.getValue()));
                config.setChatBackground(toHex(chatColorPicker.getValue()));
                config.save();
                return true;
            }
            return false;
        });
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
