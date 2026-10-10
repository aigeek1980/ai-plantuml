package com.aiplantuml.play;

import com.aiplantuml.render.DiagramNodeIndexer;
import com.aiplantuml.render.PlantUmlRenderer;
import com.aiplantuml.ui.AppIcons;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Plays a sequence diagram one message at a time, in its own window.
 * <p>
 * Deliberately self-contained: it takes a snapshot of the source when opened and owns
 * its own WebView, so nothing about the editor's own rendering, zoom, panning or export
 * is involved. Its only reach back into the editor is an optional callback that moves
 * the caret - it never modifies the document.
 * <p>
 * The step list comes from the rendered SVG rather than from parsing the source a second
 * time (see sequence-player.js), which keeps the diagram-parsing heuristics out of this
 * feature entirely.
 */
public class SequencePlayerWindow {

    private static final double MIN_STEP_SECONDS = 0.2;
    private static final double MAX_STEP_SECONDS = 3.0;
    private static final double DEFAULT_STEP_SECONDS = 1.0;

    private final Stage stage = new Stage();
    private final WebView diagramView = new WebView();
    private final Label positionLabel = new Label();
    private final Button playButton = new Button("▶  Play");
    private final Button stepBackButton = new Button("◀");
    private final Button stepForwardButton = new Button("▶");
    private final Button resetButton = new Button("⏮");
    private final Slider speedSlider = new Slider(MIN_STEP_SECONDS, MAX_STEP_SECONDS, DEFAULT_STEP_SECONDS);
    private final CheckBox followEditor = new CheckBox("Follow in editor");

    private final Map<String, Integer> nodeLineNumbers;
    private final IntConsumer onStepLine;
    private final Timeline timeline = new Timeline();

    private int stepCount = 0;
    private int current = -1;

    /**
     * @param owner          window to sit above; the player is non-modal so the editor stays usable
     * @param source         snapshot of the diagram to play
     * @param background     diagram pane background, so the player matches the editor's look
     * @param onStepLine     called with the 0-based source line of each step, for editor follow
     */
    public SequencePlayerWindow(Stage owner, String source, String background, IntConsumer onStepLine) {
        this.onStepLine = onStepLine;

        PlantUmlRenderer.SvgRenderResult result = new PlantUmlRenderer().renderSvg(source);
        if (result.isError()) {
            this.nodeLineNumbers = Map.of();
            showError("The diagram could not be rendered", result.errorText());
            return;
        }
        this.nodeLineNumbers = result.nodeLineNumbers();

        buildUi(owner, background);
        loadDiagram(result.svg(), background);
    }

    private void buildUi(Stage owner, String background) {
        stage.initOwner(owner);
        stage.setTitle("Play diagram");
        AppIcons.applyTo(stage);

        resetButton.setOnAction(e -> { pause(); goTo(-1); });
        stepBackButton.setOnAction(e -> { pause(); goTo(current - 1); });
        stepForwardButton.setOnAction(e -> { pause(); goTo(current + 1); });
        playButton.setOnAction(e -> togglePlay());

        speedSlider.setPrefWidth(120);
        speedSlider.valueProperty().addListener((obs, old, now) -> {
            if (timeline.getStatus() == Animation.Status.RUNNING) {
                startTimeline();
            }
        });
        followEditor.setSelected(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8, resetButton, stepBackButton, playButton, stepForwardButton,
                new Label("Speed:"), speedSlider, spacer, followEditor, positionLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8));
        controls.setStyle("-fx-background-color: #F2F3F5; "
                + "-fx-border-color: #D8DCE1 transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        BorderPane root = new BorderPane();
        root.setCenter(diagramView);
        root.setBottom(controls);

        stage.setScene(new Scene(root, 1100, 780));
        stage.setOnHidden(e -> timeline.stop());
        setControlsEnabled(false);
        stage.show();
    }

    private void loadDiagram(String svg, String background) {
        WebEngine engine = diagramView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state != Worker.State.SUCCEEDED) return;
            Object count = engine.executeScript("window.player.build()");
            stepCount = count instanceof Number n ? n.intValue() : 0;
            setControlsEnabled(stepCount > 0);
            goTo(-1);
            if (stepCount == 0) {
                positionLabel.setText("No messages to play");
            }
        });
        engine.loadContent(wrap(svg, background));
    }

    private void togglePlay() {
        if (timeline.getStatus() == Animation.Status.RUNNING) {
            pause();
        } else {
            if (current >= stepCount - 1) {
                goTo(-1);
            }
            startTimeline();
            playButton.setText("⏸  Pause");
        }
    }

    private void startTimeline() {
        timeline.stop();
        timeline.getKeyFrames().setAll(new KeyFrame(Duration.seconds(speedSlider.getValue()), e -> {
            if (current >= stepCount - 1) {
                pause();
                return;
            }
            goTo(current + 1);
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void pause() {
        timeline.stop();
        playButton.setText("▶  Play");
    }

    private void goTo(int index) {
        current = Math.max(-1, Math.min(index, stepCount - 1));
        Object href = diagramView.getEngine().executeScript("window.player.show(" + current + ")");
        updatePosition();
        if (followEditor.isSelected() && href instanceof String s && !s.isBlank()) {
            lineFor(s).ifPresent(onStepLine::accept);
        }
    }

    private java.util.Optional<Integer> lineFor(String href) {
        if (!href.startsWith(DiagramNodeIndexer.LINK_PREFIX)) return java.util.Optional.empty();
        String name = URLDecoder.decode(href.substring(DiagramNodeIndexer.LINK_PREFIX.length()),
                StandardCharsets.UTF_8);
        return java.util.Optional.ofNullable(nodeLineNumbers.get(name));
    }

    private void updatePosition() {
        positionLabel.setText(stepCount == 0 ? "" : (current + 1) + " / " + stepCount);
        stepBackButton.setDisable(current < 0);
        stepForwardButton.setDisable(current >= stepCount - 1);
    }

    private void setControlsEnabled(boolean enabled) {
        playButton.setDisable(!enabled);
        resetButton.setDisable(!enabled);
        stepBackButton.setDisable(!enabled);
        stepForwardButton.setDisable(!enabled);
        speedSlider.setDisable(!enabled);
    }

    private String wrap(String svg, String background) {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><style>
                  html, body { margin: 0; padding: 0; background: %s; }
                  html, body, svg, svg * { -webkit-user-select: none; user-select: none; }
                  svg text { white-space: pre; }

                  /* Everything recedes while playing; the active message is lifted back out.
                     The dimming rules are more specific than a bare class would be, so the
                     highlight has to override them explicitly. */
                  body.playing text,
                  body.playing line,
                  body.playing path,
                  body.playing polygon { opacity: 0.16; transition: opacity 120ms linear; }

                  body.playing .step-current,
                  body.playing .step-current *,
                  body.playing text.step-current,
                  body.playing line.step-current,
                  body.playing path.step-current,
                  body.playing polygon.step-current { opacity: 1 !important; }
                </style></head><body>%s<script>%s</script></body></html>
                """.formatted(background, svg, playerScript());
    }

    private String playerScript() {
        try (InputStream in = getClass().getResourceAsStream("/sequence-player.js")) {
            if (in == null) throw new IOException("sequence-player.js not found on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load the sequence player script", e);
        }
    }

    private void showError(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
