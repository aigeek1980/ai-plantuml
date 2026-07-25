package com.aiplantuml.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class AboutDialog extends Dialog<Void> {

    public AboutDialog() {
        setTitle("About AI PlantUML");
        setHeaderText(null);

        Label titleLabel = new Label("AI PlantUML");
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 18));

        Label taglineLabel = new Label("A PlantUML editor with AI-assisted diagram editing.");
        Label devLabel = new Label("Developed by I. Von. Musashi");
        Label copyrightLabel = new Label("Copyright © 2026 I. Von. Musashi. All rights reserved.");
        Label licenseLabel = new Label("Licensed under the GNU General Public License (GPLv3).");

        VBox content = new VBox(8, titleLabel, taglineLabel, devLabel, copyrightLabel, licenseLabel);
        content.setPadding(new Insets(20));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }
}
