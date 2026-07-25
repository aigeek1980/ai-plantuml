package com.aiplantuml;

import com.aiplantuml.ui.AppIcons;
import com.aiplantuml.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        AppIcons.applyTo(stage);

        MainView root = new MainView(stage);
        stage.setScene(new Scene(root, 1200, 800));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
