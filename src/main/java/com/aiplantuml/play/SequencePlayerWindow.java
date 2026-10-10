package com.aiplantuml.play;

import com.aiplantuml.render.DiagramNodeIndexer;
import com.aiplantuml.render.PlantUmlRenderer;
import com.aiplantuml.ui.AppIcons;
import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Relative zoom change below which the view is left alone. */
    private static final double ZOOM_DEADBAND = 0.05;

    /** GIF delays are hundredths of a second, and players race through anything shorter. */
    private static final double MIN_GIF_DELAY_SECONDS = 0.05;
    private static final double MAX_GIF_DELAY_SECONDS = 30.0;
    /** How long a recorded step is given to finish painting before its picture is taken. */
    private static final double CAPTURE_SETTLE_MS = 120;

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

    /** Draws only what runs, rebuilding the diagram each step, instead of revealing it. */
    private final CheckBox buildUpMode = new CheckBox("Build up only what runs");
    private final CheckBox recordFrames = new CheckBox("Record frames");
    private final Button exportButton = new Button("Export...");

    /** Pictures of the player, one per step, taken while recording was on. */
    private final List<Path> recorded = new ArrayList<>();
    private Path recordingFolder;
    /** Frames are written off the UI thread, in order, so playing stays responsive. */
    private final ExecutorService frameWriter =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "frame-writer");
                thread.setDaemon(true);
                return thread;
            });
    /** Set when a step has just been taken, so only steps forward are recorded. */
    private boolean captureThisStep = false;
    private final ScriptModel script;
    private final String background;
    private TraceRun trace;
    /** Answers given so far, replayed when rewinding so nothing already passed is re-asked. */
    private final List<Integer> answers = new ArrayList<>();
    /** How many statements had run when each answer was given, so rewinding can drop it. */
    private final List<Integer> answerAtStep = new ArrayList<>();
    private int answerCursor = 0;
    private int traceSteps = 0;
    private Runnable afterLoad = () -> { };

    /** Steps actually run, which excludes the branches not chosen. */
    private final Set<Integer> executed = new LinkedHashSet<>();
    /** Branch picked for each alt, remembered for the rest of the run. */
    private final Map<Integer, Integer> decisions = new HashMap<>();

    /** The zoom last set by hand; auto-fit works down from it and never past it. */
    private double manualZoom = 1.0;
    private int[] stepLines = new int[0];
    private int stepCount = 0;
    private int current = -1;

    /** Whether starting at the caret actually leaves anything out, worth saying in the title. */
    private final boolean skipsStart;
    /** Kept so the whole diagram can be put back when build-up mode is turned off. */
    private String originalSvg = "";
    /** First step at or after the caret; the ones before it are never played. */
    private int firstStep = 0;
    /** The opening load builds the step list even when the run itself is a build-up. */
    private boolean built = false;

    /**
     * @param owner          window to sit above; the player is non-modal so the editor stays usable
     * @param source         snapshot of the diagram to play
     * @param startLine      0-based line the caret was on, where the run begins
     * @param background     diagram pane background, so the player matches the editor's look
     * @param onStepLine     called with the 0-based source line of each step, for editor follow
     */
    public SequencePlayerWindow(Stage owner, String source, int startLine, String background,
                                IntConsumer onStepLine) {
        this.onStepLine = onStepLine;
        this.blocks = BlockModel.parse(source);

        ScriptModel whole = ScriptModel.parse(source);
        this.script = whole.startingAt(Math.max(0, startLine));
        this.skipsStart = script.firstSourceLine() > whole.firstSourceLine();
        this.background = background;

        PlantUmlRenderer.SvgRenderResult result = new PlantUmlRenderer().renderSvg(source);
        if (result.isError()) {
            this.nodeLineNumbers = Map.of();
            showError("The diagram could not be rendered", result.errorText());
            return;
        }
        this.nodeLineNumbers = result.nodeLineNumbers();
        this.originalSvg = result.svg();

        buildUi(owner);
        installLoadHandler();
        afterLoad = this::onDiagramPageReady;
        diagramView.getEngine().loadContent(wrap(originalSvg, background));
    }

    private void buildUi(Stage owner) {
        stage.initOwner(owner);
        stage.setTitle(skipsStart
                ? "Play diagram - from line " + (script.firstSourceLine() + 1)
                : "Play diagram");
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

        buildUpMode.setTooltip(new Tooltip(
                "Draw only the calls that run, as they run - unchosen branches never appear, "
                        + "a loop asks at the end of each pass whether to go round again, "
                        + "and lifelines arrive when first used"));
        buildUpMode.setSelected(true);
        buildUpMode.selectedProperty().addListener((obs, was, now) -> {
            pause();
            if (now) {
                restart();
            } else {
                // The trace page is what is on screen, so the whole diagram has to be
                // put back before it can be revealed a step at a time again.
                afterLoad = this::onDiagramPageReady;
                diagramView.getEngine().loadContent(wrap(originalSvg, background));
            }
        });

        // An export is made of pictures of the player, so there has to be a run to take
        // pictures of: recording is turned on first, and then the run is played.
        recordFrames.setTooltip(new Tooltip(
                "Keep a picture of the player at every step, ready to export as a GIF"));
        recordFrames.selectedProperty().addListener((obs, was, now) -> updateExportButton());

        exportButton.setTooltip(new Tooltip(
                "Save the recorded steps as an animated GIF, or as one image each"));
        exportButton.setOnAction(e -> { pause(); exportRun(); });
        updateExportButton();

        HBox controls = new HBox(8, resetButton, stepBackButton, playButton, stepForwardButton,
                new Label("Speed:"), speedSlider,
                new Separator(Orientation.VERTICAL),
                zoomOutButton, zoomResetButton, zoomInButton, zoomLabel, autoFit,
                new Separator(Orientation.VERTICAL), buildUpMode,
                new Separator(Orientation.VERTICAL), recordFrames, exportButton,
                spacer, endpointsLabel, followEditor, positionLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8));
        controls.setStyle("-fx-background-color: #F2F3F5; "
                + "-fx-border-color: #D8DCE1 transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        BorderPane root = new BorderPane();
        root.setCenter(diagramView);
        root.setBottom(controls);

        stage.setScene(new Scene(root, 1100, 780));
        stage.setOnHidden(e -> {
            timeline.stop();
            frameWriter.shutdown();
            if (recordingFolder != null) RunExporter.deleteTree(recordingFolder);
        });
        setControlsEnabled(false);
        stage.show();
    }

    /**
     * Every page this window loads hands over to whatever asked for it: build-up mode
     * reloads on each step, and the whole diagram is reloaded when it is switched off.
     */
    private void installLoadHandler() {
        diagramView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state != Worker.State.SUCCEEDED) return;
            Runnable pending = afterLoad;
            afterLoad = () -> { };
            pending.run();
        });
    }

    /** The whole diagram is on screen: index its steps and start the run over. */
    private void onDiagramPageReady() {
        WebEngine engine = diagramView.getEngine();
        Object count = engine.executeScript("window.player.build()");
        stepCount = count instanceof Number n ? n.intValue() : 0;
        resolveStepLines(engine);
        if (!built) {
            built = true;
            setControlsEnabled(stepCount > 0);
            if (stepCount == 0) positionLabel.setText("No messages to play");
        }
        restart();
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
        firstStep = firstStepAtOrAfterCaret();
    }

    /**
     * Where a revealed run begins. The anchor is the first statement the script itself
     * will run, so both modes start at the same place even though one works from the
     * source and the other from what was drawn.
     */
    private int firstStepAtOrAfterCaret() {
        int anchor = script.firstSourceLine();
        if (anchor <= 0) return 0;
        int step = 0;
        while (step < stepCount && (stepLines[step] < 0 || stepLines[step] < anchor)) step++;
        // Nothing recognisable at or after the caret: better to play it all than nothing.
        return step >= stepCount ? 0 : step;
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
        captureThisStep = false;
        if (buildUpMode.isSelected()) {
            if (traceSteps == 0) return;
            dropLastFrame();
            // The trace is only ever appended to, so going back means running it again
            // from the start - cheap, since nothing is drawn until the end.
            traceSteps--;
            // Answers given beyond where we have rewound to are given up, so a branch
            // stepped back out of is offered again rather than silently reused.
            while (!answerAtStep.isEmpty() && answerAtStep.get(answerAtStep.size() - 1) >= traceSteps) {
                answerAtStep.remove(answerAtStep.size() - 1);
                answers.remove(answers.size() - 1);
            }
            answerCursor = 0;
            trace.reset();
            for (int i = 0; i < traceSteps; i++) trace.step();
            showTrace();
            return;
        }
        List<Integer> played = new ArrayList<>(executed);
        if (played.isEmpty()) return;
        dropLastFrame();
        played.remove(played.size() - 1);
        executed.clear();
        executed.addAll(played);
        current = played.isEmpty() ? firstStep - 1 : played.get(played.size() - 1);
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
        captureThisStep = false;
        clearRecording();
        executed.clear();
        decisions.clear();
        // Back to where the caret was, not to the top: that is where this run begins.
        current = firstStep - 1;
        if (buildUpMode.isSelected()) {
            answers.clear();
            answerAtStep.clear();
            answerCursor = 0;
            traceSteps = 0;
            trace = new TraceRun(script, askingDecider());
            showTrace();
        } else {
            renderCurrent();
        }
    }

    /**
     * Replays answers already given before putting anything to the user, so rewinding
     * and going forward again does not re-ask what was decided earlier in the run.
     */
    private TraceRun.Decider askingDecider() {
        return new TraceRun.Decider() {
            @Override
            public int chooseBranch(ScriptModel.Block block) {
                Integer replayed = nextRecordedAnswer();
                if (replayed != null) return replayed;
                List<String> labels = block.branches().stream().map(ScriptModel.Branch::label).toList();
                if (labels.size() < 2) return record(0);
                Integer picked = ask("Which branch?",
                        "This " + block.kind() + " has more than one path. Which one runs?", labels);
                return picked == null ? -1 : record(picked);
            }

            @Override
            public boolean repeatLoop(ScriptModel.Block block, int passesDone) {
                Integer replayed = nextRecordedAnswer();
                if (replayed != null) return replayed != 0;
                String label = block.branches().get(0).label();
                String name = ScriptModel.UNLABELLED.equals(label) ? "this loop" : "\"" + label + "\"";
                boolean again = confirm("One more time?", "Repeat " + name + "?",
                        passesDone == 1
                                ? "It has run once. Go round again?"
                                : "It has run " + passesDone + " times. Go round again?");
                return record(again ? 1 : 0) != 0;
            }
        };
    }

    private Integer nextRecordedAnswer() {
        return answerCursor < answers.size() ? answers.get(answerCursor++) : null;
    }

    private int record(int answer) {
        answers.add(answer);
        // The question is asked while working out the next statement, so this is the
        // count it follows - which is what lets a rewind past it throw it away.
        answerAtStep.add(trace.stepsRun());
        answerCursor = answers.size();
        return answer;
    }

    /** @return index of the option chosen, or null if the user cancelled */
    private Integer ask(String title, String header, List<String> options) {
        boolean wasRunning = timeline.getStatus() == Animation.Status.RUNNING;
        pause();

        ChoiceDialog<String> dialog = new ChoiceDialog<>(options.get(0), options);
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("Choose:");
        Optional<String> answer = dialog.showAndWait();
        if (answer.isEmpty()) return null;

        if (wasRunning) {
            startTimeline();
            playButton.setText("⏸  Pause");
        }
        return options.indexOf(answer.get());
    }

    /**
     * A yes-or-no question. Closing it without answering means carrying on rather than
     * repeating, since a question about going round again is only ever opening up more
     * work - declining it is the safe reading.
     */
    private boolean confirm(String title, String header, String detail) {
        boolean wasRunning = timeline.getStatus() == Animation.Status.RUNNING;
        pause();

        ButtonType again = new ButtonType("Repeat", ButtonBar.ButtonData.YES);
        ButtonType carryOn = new ButtonType("Carry on", ButtonBar.ButtonData.NO);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, detail, again, carryOn);
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        boolean yes = dialog.showAndWait().orElse(carryOn) == again;

        if (wasRunning) {
            startTimeline();
            playButton.setText("⏸  Pause");
        }
        return yes;
    }

    /**
     * Takes a picture of the player as it stands, if this step is one to record.
     * <p>
     * The page paints on its own schedule, so the shot is taken a pulse after the step
     * was drawn rather than in the same breath - otherwise the frame can be of the step
     * before. Writing it out is handed off, so a long run does not stutter on encoding.
     */
    private void captureFrame() {
        if (!captureThisStep) return;
        captureThisStep = false;
        if (!recordFrames.isSelected()) return;

        // Long enough for the page to have painted the step, short enough to keep up
        // with the fastest the player can be stepped.
        PauseTransition settle = new PauseTransition(Duration.millis(CAPTURE_SETTLE_MS));
        settle.setOnFinished(e -> diagramView.snapshot(shot -> {
            keepFrame(shot.getImage());
            return null;
        }, null, null));
        settle.play();
    }

    private void keepFrame(Image image) {
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        if (width <= 0 || height <= 0) return;

        // Pixels are read here, on the UI thread, because that is the only place the
        // image belongs to us; everything after that is just bytes.
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);

        Path file;
        try {
            file = recordingFolder().resolve(String.format("frame-%04d.png", recorded.size() + 1));
        } catch (IOException e) {
            recordFrames.setSelected(false);
            showError("Recording stopped", "Nowhere to keep the frames: " + e.getMessage());
            return;
        }
        recorded.add(file);
        updateExportButton();

        frameWriter.execute(() -> {
            BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            buffer.setRGB(0, 0, width, height, pixels, 0, width);
            try {
                ImageIO.write(buffer, "png", file.toFile());
            } catch (IOException e) {
                Platform.runLater(() -> {
                    recorded.remove(file);
                    updateExportButton();
                });
            }
        });
    }

    private Path recordingFolder() throws IOException {
        if (recordingFolder == null) {
            recordingFolder = Files.createTempDirectory("aiplantuml-recording");
        }
        return recordingFolder;
    }

    /** Frames of steps that have been rewound past are no longer part of the run. */
    private void dropLastFrame() {
        if (recorded.isEmpty()) return;
        Path file = recorded.remove(recorded.size() - 1);
        frameWriter.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // It will go with the folder when the window closes.
            }
        });
        updateExportButton();
    }

    private void clearRecording() {
        recorded.clear();
        updateExportButton();
        Path folder = recordingFolder;
        if (folder == null) return;
        recordingFolder = null;
        frameWriter.execute(() -> RunExporter.deleteTree(folder));
    }

    private void updateExportButton() {
        exportButton.setDisable(recorded.isEmpty());
        exportButton.setText(recorded.isEmpty() ? "Export..." : "Export (" + recorded.size() + ")...");
    }

    /** Saves the frames recorded while the run was played. */
    private void exportRun() {
        List<Path> frames = List.copyOf(recorded);
        if (frames.isEmpty()) {
            info("Nothing recorded yet",
                    "Tick \"Record frames\", then play or step through the run. Each step is"
                            + " kept as it is drawn, and those are what gets exported.");
            return;
        }
        RunExporter.Options options = askExportOptions(frames.size());
        if (options == null) return;

        Path target = chooseTarget(options.format());
        if (target == null) return;
        startExport(frames, target, options);
    }

    /** @return what to export, or null if the user backed out */
    private RunExporter.Options askExportOptions(int frames) {
        ComboBox<String> format = new ComboBox<>();
        format.getItems().addAll("Animated GIF", "One PNG per step");
        format.getSelectionModel().select(0);

        ComboBox<String> scale = new ComboBox<>();
        scale.getItems().addAll("100%", "75%", "50%", "25%");
        scale.getSelectionModel().select(0);

        // The playing speed is a reasonable guess at the right pace to watch it back,
        // but only a guess: a GIF is read, not stepped through, and usually wants longer.
        Spinner<Double> delay = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                MIN_GIF_DELAY_SECONDS, MAX_GIF_DELAY_SECONDS, speedSlider.getValue(), 0.1));
        delay.setEditable(true);
        delay.setPrefWidth(100);
        HBox delayRow = new HBox(8, delay, new Label("seconds"));
        delayRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 10, 0, 10));
        grid.addRow(0, new Label("Save as:"), format);
        grid.addRow(1, new Label("Size:"), scale);
        grid.addRow(2, new Label("Hold each step:"), delayRow);

        Label note = new Label(frames + (frames == 1 ? " frame" : " frames")
                + " recorded. The last one is held longer so the run does not snap back,"
                + "\nand a long run makes a big file - the smaller sizes are there for sharing.");
        note.setStyle("-fx-text-fill: #6B7280;");
        GridPane.setColumnSpan(note, 2);
        grid.add(note, 0, 3);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Export run");
        dialog.setHeaderText("Save this run as a picture of itself");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;

        // A spinner being edited by hand still holds its last committed value, so the
        // text is what the user meant if they typed a figure and went straight to OK.
        delay.increment(0);

        return new RunExporter.Options(
                format.getSelectionModel().getSelectedIndex() == 0
                        ? RunExporter.Format.GIF : RunExporter.Format.PNG_FRAMES,
                switch (scale.getSelectionModel().getSelectedIndex()) {
                    case 1 -> 0.75;
                    case 2 -> 0.5;
                    case 3 -> 0.25;
                    default -> 1.0;
                },
                delay.getValue());
    }

    private Path chooseTarget(RunExporter.Format format) {
        if (format == RunExporter.Format.GIF) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save the run as a GIF");
            chooser.setInitialFileName("diagram-run.gif");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Animated GIF", "*.gif"));
            File file = chooser.showSaveDialog(stage);
            return file == null ? null : file.toPath();
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a folder for the frames");
        File directory = chooser.showDialog(stage);
        if (directory == null) return null;

        // A save dialog warns about replacing a file; nothing warns about replacing the
        // frames of someone's last export, so this does.
        Path folder = directory.toPath();
        long existing = countFrames(folder);
        if (existing > 0) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "That folder already holds " + existing + " exported frames.\n"
                            + "Writing this run there will replace them.",
                    ButtonType.OK, ButtonType.CANCEL);
            alert.initOwner(stage);
            alert.setHeaderText("Replace the frames already there?");
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        }
        return folder;
    }

    private long countFrames(Path folder) {
        try (var files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().matches("step-\\d+\\.png")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Runs the export off the UI thread: every frame is decoded, scaled and encoded. */
    private void startExport(List<Path> frames, Path target, RunExporter.Options options) {
        Stage progressStage = new Stage();
        progressStage.initOwner(stage);
        progressStage.initModality(Modality.WINDOW_MODAL);
        progressStage.setTitle("Exporting");
        AppIcons.applyTo(progressStage);

        Label message = new Label("Writing frame 1 of " + frames.size() + "...");
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(320);
        Button cancel = new Button("Cancel");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancel.setOnAction(e -> {
            cancelled.set(true);
            cancel.setDisable(true);
            message.setText("Stopping...");
        });

        VBox box = new VBox(12, message, bar, cancel);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.CENTER_LEFT);
        progressStage.setScene(new Scene(box));
        progressStage.setOnCloseRequest(e -> cancelled.set(true));
        progressStage.show();

        RunExporter.Progress progress = new RunExporter.Progress() {
            @Override
            public void rendered(int done, int total) {
                Platform.runLater(() -> {
                    bar.setProgress((double) done / total);
                    message.setText("Writing frame " + Math.min(done + 1, total) + " of " + total + "...");
                });
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };

        Thread worker = new Thread(() -> {
            try {
                RunExporter.Result result = RunExporter.export(frames, target, options, progress);
                Platform.runLater(() -> {
                    progressStage.close();
                    if (result.frames() == 0) return;             // cancelled; nothing to say
                    info("Export finished", describe(result, target, options));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressStage.close();
                    showError("The run could not be exported",
                            e.getMessage() != null ? e.getMessage() : e.toString());
                });
            }
        }, "run-export");
        worker.setDaemon(true);
        worker.start();
    }

    private String describe(RunExporter.Result result, Path target, RunExporter.Options options) {
        StringBuilder text = new StringBuilder(result.frames() + " steps at "
                + result.width() + " x " + result.height() + " pixels.\n\n" + target);
        if (options.format() == RunExporter.Format.GIF) {
            try {
                text.append("\n\n").append(String.format("%.1f MB", Files.size(target) / 1048576.0));
            } catch (IOException ignored) {
                // The file is written; not being able to measure it is not worth reporting.
            }
        } else {
            text.append("\n\nREADME.txt beside the frames has the ffmpeg command that turns"
                    + "\nthem into a video.");
        }
        return text.toString();
    }

    private void info(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    /** Renders the trace as it now stands and shows it. */
    private void showTrace() {
        // A trace can begin with statements that draw nothing on their own - a separator,
        // or a note before any call. PlantUML cannot tell such a diagram is a sequence at
        // all and answers with its syntax-error picture, so rather than guess which
        // statements count as drawable, render first and fall back if it could not.
        PlantUmlRenderer.SvgRenderResult result = trace.hasRunAnything()
                ? new PlantUmlRenderer().renderSvg(trace.currentSource())
                : null;
        boolean drawable = result != null && !result.isError() && !result.svg().contains("Syntax Error");

        if (!drawable) {
            // A step that draws nothing - an opening separator, say - is no use in a
            // film of the run, so it is passed over rather than recorded as a blank.
            captureThisStep = false;
            afterLoad = () -> { };
            diagramView.getEngine().loadContent(emptyTracePage(trace.stepsRun()));
            endpointsLabel.setText("");
            positionLabel.setText(traceSteps + " run");
            stepBackButton.setDisable(traceSteps == 0);
            stepForwardButton.setDisable(false);
            if (followEditor.isSelected() && trace.lastSourceLine() >= 0) {
                onStepLine.accept(trace.lastSourceLine());
            }
            return;
        }
        afterLoad = () -> {
            WebEngine engine = diagramView.getEngine();
            Object count = engine.executeScript("window.player.build()");
            int drawn = count instanceof Number n ? n.intValue() : 0;
            // Everything drawn has by definition run; the newest call is the current one.
            String csv = java.util.stream.IntStream.range(0, drawn)
                    .mapToObj(String::valueOf).collect(Collectors.joining(","));
            current = drawn - 1;
            Object payload = engine.executeScript(
                    "window.player.render('" + csv + "'," + current + ")");
            applyStepPayload(payload);
            positionLabel.setText(traceSteps + " run");
            stepBackButton.setDisable(traceSteps == 0);
            stepForwardButton.setDisable(false);
            captureFrame();
        };
        diagramView.getEngine().loadContent(wrap(result.svg(), background));

        if (followEditor.isSelected() && trace.lastSourceLine() >= 0) {
            onStepLine.accept(trace.lastSourceLine());
        }
    }

    /**
     * Advances to the next step the chosen path actually reaches, asking which branch to
     * follow the first time an alt is entered and skipping the branches not taken.
     * Returns false once the run is over.
     */
    private boolean advance() {
        captureThisStep = true;
        if (buildUpMode.isSelected()) {
            if (!trace.step()) {
                captureThisStep = false;
                return false;
            }
            traceSteps++;
            showTrace();
            return true;
        }
        int candidate = current + 1;
        while (candidate < stepCount) {
            List<BlockModel.Choice> path = pathOf(candidate);

            BlockModel.Choice undecided = null;
            for (BlockModel.Choice choice : path) {
                if (!decisions.containsKey(choice.blockId())) { undecided = choice; break; }
            }
            if (undecided != null) {
                Integer picked = askBranch(undecided.blockId());
                if (picked == null) {                       // cancelled - stay put
                    captureThisStep = false;
                    return false;
                }
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
                captureFrame();
                return true;
            }
            candidate++;
        }
        captureThisStep = false;
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
        applyStepPayload(result);
    }

    /**
     * Reads back what the page worked out about the step just drawn:
     * "href | from | to | spanPx | viewportPx". The participants and the span are
     * resolved there, where the rendered geometry says which lifelines the arrow
     * actually runs between and how far apart they are.
     */
    private void applyStepPayload(Object result) {
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
     * Zooms so the call's two lifelines are both on screen, then centres on it. Zooms
     * back in as well as out, so a wide call does not leave the rest of the run shrunk,
     * but never past the zoom last chosen by hand - fitting a self-call to the window
     * would magnify it absurdly.
     */
    private void fitToCall(double spanPx, double viewportPx) {
        if (autoFit.isSelected() && spanPx > 0 && viewportPx > 0) {
            double current = diagramView.getZoom();
            double target = Math.min(manualZoom, current * viewportPx / (spanPx * FIT_MARGIN));
            target = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, target));
            // A deadband keeps near-identical calls from nudging the zoom every step.
            if (Math.abs(target - current) / current > ZOOM_DEADBAND) {
                diagramView.setZoom(target);
                updateZoomLabel();
                // The page only learns how much is visible on the next layout pass, so
                // centring has to wait for it.
                Platform.runLater(this::centreOnCurrent);
                return;
            }
        }
        centreOnCurrent();
    }

    private void centreOnCurrent() {
        diagramView.getEngine().executeScript("window.player.centre(" + current + ")");
    }

    /** Zoom chosen from the buttons, which is the ceiling auto-fit works down from. */
    private void setZoom(double zoom) {
        manualZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        diagramView.setZoom(manualZoom);
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
        // the steps skipped in paths not taken never happen, so the total is only a ceiling -
        // and the ones above the caret are not part of this run at all.
        positionLabel.setText(stepCount == 0 ? "" : executed.size() + " / " + (stepCount - firstStep));
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

    private String emptyTracePage(int statementsRun) {
        String message = statementsRun == 0
                ? "Nothing has run yet - press Play or Step."
                : "Nothing to draw yet - the run has not reached a call.";
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><style>
                  html, body { margin: 0; height: 100%%; background: %s;
                               font-family: 'Segoe UI', sans-serif; color: #6B7280; }
                  div { height: 100%%; display: flex; align-items: center; justify-content: center; }
                </style></head><body><div>%s</div></body></html>
                """.formatted(background, message);
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
