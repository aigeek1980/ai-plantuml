package com.aiplantuml.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

public class AppIcons {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256, 512};

    public static void applyTo(Stage stage) {
        for (int size : SIZES) {
            stage.getIcons().add(new Image(AppIcons.class.getResourceAsStream("/icons/app-icon-" + size + ".png")));
        }
    }
}
