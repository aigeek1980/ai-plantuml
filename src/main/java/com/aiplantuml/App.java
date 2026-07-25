package com.aiplantuml;

import com.aiplantuml.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class App extends Application {

    private static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256, 512};

    @Override
    public void start(Stage stage) {
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/app-icon-" + size + ".png")));
        }

        MainView root = new MainView(stage);
        stage.setScene(new Scene(root, 1200, 800));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
