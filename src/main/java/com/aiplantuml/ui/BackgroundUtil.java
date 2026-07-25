package com.aiplantuml.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * Applies a background color to a Region and to its internal ".viewport"/".content"
 * skin parts (ScrollPane, TextArea, etc. paint those separately from the control's
 * own background, so setting -fx-background-color on the control alone isn't enough).
 */
public class BackgroundUtil {

    public static void applyBackground(Region region, String hex) {
        region.setStyle("-fx-background-color: " + hex + ";");
        applyInnerBackground(region, hex);
        region.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(() -> applyInnerBackground(region, hex));
            }
        });
    }

    private static void applyInnerBackground(Region region, String hex) {
        for (String selector : new String[]{".viewport", ".content"}) {
            Node inner = region.lookup(selector);
            if (inner != null) {
                inner.setStyle("-fx-background-color: " + hex + ";");
            }
        }
    }
}
