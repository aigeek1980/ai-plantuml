package com.aiplantuml.play;

import com.aiplantuml.render.DiagramNodeIndexer;
import com.aiplantuml.render.PlantUmlRenderer;
import com.aiplantuml.ui.AppIcons;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

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

    private static final double MIN_ZOOM = 0.2;
    private static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_STEP = 1.25;
    /** Leaves a little room either side rather than fitting the span exactly to the edges. */
    private static final double FIT_MARGIN = 1.12;

    private final Stage stage = new Stage();
    private final WebView diagramView = new WebView();
    private final Label positionLabel = new Label();
    private final Label endpointsLabel = new Label();
    private final Button playButton = new Button("▶  Play");
    private final Button stepBackButton = new Button("◀");
    private final Button stepForwardButton = new Button("▶");
    private final Button resetButton = new Button("⏮");
    private final Slider speedSlider = new Slider(MIN_STEP_SECONDS, MAX_STEP_SECONDS, DEFAULT_STEP_SECONDS);
    private final CheckBox followEditor = new CheckBox("Follow in editor");
    private final CheckBox autoFit = new CheckBox("Fit to call");
    private final Button zoomInButton = new Button("+");
    private final Button zoomOutButton = new Button("−");
    private final Button zoomResetButton = new Button("100%");
    private final Label zoomLabel = new Label();

    private final Map<String, Integer> nodeLineNumbers;
    private final IntConsumer onStepLine;
    private final Timeline timeline = new Timeline();
    private final BlockModel blocks;

    /** Steps actually run, which excludes the branches not chosen. */
    private final Set<Integer> executed = new LinkedHashSet<>();
    /** Branch picked for each alt, remembered for the rest of the run. */
    private final Map<Integer, Integer> decisions = new HashMap<>();

    private int[] stepLines = new int[0];
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
        this.blocks = BlockModel.parse(source);

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

        resetButton.setOnAction(e -> { pause(); restart(); });
        stepBackButton.setOnAction(e -> { pause(); stepBack(); });
        stepForwardButton.setOnAction(e -> { pause(); advance(); });
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

        // The participants are named in the bar as well as highlighted in the diagram,
        // since on a long diagram their boxes can be scrolled far off screen.
        endpointsLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #B3261E;");

        autoFit.setSelected(true);
        autoFit.setTooltip(new Tooltip("Zoom out when a call's two lifelines don't both fit on screen"));
        zoomInButton.setOnAction(e -> setZoom(diagramView.getZoom() * ZOOM_STEP));
        zoomOutButton.setOnAction(e -> setZoom(diagramView.getZoom() / ZOOM_STEP));
        zoomResetButton.setOnAction(e -> setZoom(1.0));
        updateZoomLabel();

        HBox controls = new HBox(8, resetButton, stepBackButton, playButton, stepForwardButton,
                new Label("Speed:"), speedSlider,
                new Separator(Orientation.VERTICAL),
                zoomOutButton, zoomResetButton, zoomInButton, zoomLabel, autoFit,
                spacer, endpointsLabel, followEditor, positionLabel);
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
            resolveStepLines(engine);
            setControlsEnabled(stepCount > 0);
            restart();
            if (stepCount == 0) {
                positionLabel.setText("No messages to play");
            }
        });
        engine.loadContent(wrap(svg, background));
    }

    /** Each step's source line, so its place in the alt/else structure is known up front. */
    private void resolveStepLines(WebEngine engine) {
        stepLines = new int[stepCount];
        Object targets = engine.executeScript("window.player.targets()");
        String[] hrefs = targets instanceof String s && !s.isBlank()
                ? s.split("\u0001", -1) : new String[0];
        for (int i = 0; i < stepCount; i++) {
            stepLines[i] = i < hrefs.length ? lineFor(hrefs[i]).orElse(-1) : -1;
        }
    }

    private void togglePlay() {
        if (timeline.getStatus() == Animation.Status.RUNNING) {
            pause();
        } else {
            if (current >= stepCount - 1) {
                restart();
            }
            startTimeline();
            playButton.setText("⏸  Pause");
        }
    }

    private void startTimeline() {
        timeline.stop();
        timeline.getKeyFrames().setAll(new KeyFrame(Duration.seconds(speedSlider.getValue()), e -> {
            if (!advance()) {
                pause();
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    /** Rewinds to the previously executed step, giving up any branch it steps back out of. */
    private void stepBack() {
        List<Integer> played = new ArrayList<>(executed);
        if (played.isEmpty()) return;
        played.remove(played.size() - 1);
        executed.clear();
        executed.addAll(played);
        current = played.isEmpty() ? -1 : played.get(played.size() - 1);
        forgetUnenteredDecisions();
        renderCurrent();
    }

    /**
     * A branch choice only stands while some step inside that block has actually run.
     * Rewinding past the point where an alt was entered therefore un-asks the question,
     * so going forward again offers it afresh - and a choice made inside a nested alt is
     * given up without disturbing the enclosing one, which is still being run through.
     */
    private void forgetUnenteredDecisions() {
        Set<Integer> stillEntered = new HashSet<>();
        for (int step : executed) {
            for (BlockModel.Choice choice : pathOf(step)) {
                stillEntered.add(choice.blockId());
            }
        }
        decisions.keySet().retainAll(stillEntered);
    }

    private void pause() {
        timeline.stop();
        playButton.setText("▶  Play");
    }

    /** Back to the untouched diagram, with every branch decision forgotten. */
    private void restart() {
        executed.clear();
        decisions.clear();
        current = -1;
        renderCurrent();
    }

    /**
     * Advances to the next step the chosen path actually reaches, asking which branch to
     * follow the first time an alt is entered and skipping the branches not taken.
     * Returns false once the run is over.
     */
    private boolean advance() {
        int candidate = current + 1;
        while (candidate < stepCount) {
            List<BlockModel.Choice> path = pathOf(candidate);

            BlockModel.Choice undecided = null;
            for (BlockModel.Choice choice : path) {
                if (!decisions.containsKey(choice.blockId())) { undecided = choice; break; }
            }
            if (undecided != null) {
                Integer picked = askBranch(undecided.blockId());
                if (picked == null) return false;           // cancelled - stay put
                decisions.put(undecided.blockId(), picked);
                continue;                                    // re-test with the choice applied
            }

            boolean onChosenPath = true;
            for (BlockModel.Choice choice : path) {
                if (!Objects.equals(decisions.get(choice.blockId()), choice.branchIndex())) {
                    onChosenPath = false;
                    break;
                }
            }
            if (onChosenPath) {
                current = candidate;
                executed.add(candidate);
                renderCurrent();
                return true;
            }
            candidate++;
        }
        return false;
    }

    private List<BlockModel.Choice> pathOf(int step) {
        int line = step < stepLines.length ? stepLines[step] : -1;
        return line < 0 ? List.of() : blocks.pathFor(line);
    }

    private Integer askBranch(int blockId) {
        List<String> branches = blocks.branchesOf(blockId);
        if (branches.size() < 2) return 0;

        boolean wasRunning = timeline.getStatus() == Animation.Status.RUNNING;
        pause();

        ChoiceDialog<String> dialog = new ChoiceDialog<>(branches.get(0), branches);
        dialog.initOwner(stage);
        dialog.setTitle("Which branch?");
        dialog.setHeaderText("This " + blocks.kindOf(blockId) + " has more than one path. Which one runs?");
        dialog.setContentText("Branch:");
        Optional<String> answer = dialog.showAndWait();
        if (answer.isEmpty()) return null;

        if (wasRunning) {
            startTimeline();
            playButton.setText("⏸  Pause");
        }
        return branches.indexOf(answer.get());
    }

    /** Steps already run stay visible; the branches not taken were never run, so stay hidden. */
    private void renderCurrent() {
        String csv = executed.stream().map(String::valueOf).collect(Collectors.joining(","));
        Object result = diagramView.getEngine()
                .executeScript("window.player.render('" + csv + "'," + current + ")");
        updatePosition();

        // "href | from | to | spanPx | viewportPx" - the participants and the span are
        // resolved in the page, where the rendered geometry says which lifelines the
        // arrow actually runs between and how far apart they are.
        String[] parts = result instanceof String s && !s.isBlank() ? s.split("\u0001", -1) : new String[0];
        if (parts.length >= 3) {
            endpointsLabel.setText(parts[1].isBlank() && parts[2].isBlank()
                    ? "" : parts[1] + "  →  " + parts[2]);
        } else {
            endpointsLabel.setText("");
        }
        if (parts.length >= 5) {
            fitToCall(parseOrZero(parts[3]), parseOrZero(parts[4]));
        }
        if (followEditor.isSelected() && parts.length > 0 && !parts[0].isBlank()) {
            lineFor(parts[0]).ifPresent(onStepLine::accept);
        }
    }

    /**
     * Zooms out far enough that the call's two lifelines are both on screen, then
     * centres on it. Only ever zooms out: zooming back in after every short call would
     * make the diagram lurch between steps.
     */
    private void fitToCall(double spanPx, double viewportPx) {
        if (autoFit.isSelected() && spanPx > 0 && viewportPx > 0 && spanPx * FIT_MARGIN > viewportPx) {
            setZoom(diagramView.getZoom() * viewportPx / (spanPx * FIT_MARGIN));
        }
        diagramView.getEngine().executeScript("window.player.centre(" + current + ")");
    }

    private void setZoom(double zoom) {
        diagramView.setZoom(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom)));
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        zoomLabel.setText(Math.round(diagramView.getZoom() * 100) + "%");
    }

    private static double parseOrZero(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private java.util.Optional<Integer> lineFor(String href) {
        if (!href.startsWith(DiagramNodeIndexer.LINK_PREFIX)) return java.util.Optional.empty();
        String name = URLDecoder.decode(href.substring(DiagramNodeIndexer.LINK_PREFIX.length()),
                StandardCharsets.UTF_8);
        return java.util.Optional.ofNullable(nodeLineNumbers.get(name));
    }

    private void updatePosition() {
        // Counts what has run rather than how far down the diagram we are: with branching,
        // the steps skipped in paths not taken never happen, so the total is only a ceiling.
        positionLabel.setText(stepCount == 0 ? "" : executed.size() + " / " + stepCount);
        stepBackButton.setDisable(executed.isEmpty());
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
                  html, body { margin: 0; padding: 0; background: %s; cursor: grab; }
                  html, body, svg, svg * { -webkit-user-select: none; user-select: none; }
                  body.panning { cursor: grabbing; }
                  svg text { white-space: pre; }

                  /* The diagram reads as an execution trace: what has already happened
                     stays as drawn, what hasn't happened yet is not there at all, and the
                     step being executed is picked out in colour. */
                  .step-future,
                  .step-future * { opacity: 0 !important; }

                  .step-current text { fill: #B3261E !important; font-weight: bold; }
                  line.step-current,
                  path.step-current { stroke: #B3261E !important; stroke-width: 2.5 !important; }
                  polygon.step-current { stroke: #B3261E !important; fill: #B3261E !important; }

                  /* The two participants this step runs between. */
                  .participant-current rect { stroke: #B3261E !important; stroke-width: 2.5 !important; }
                  .participant-current text { fill: #B3261E !important; font-weight: bold; }
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
