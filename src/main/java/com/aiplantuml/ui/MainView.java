package com.aiplantuml.ui;

import com.aiplantuml.ai.KimiClient;
import com.aiplantuml.config.AppConfig;
import com.aiplantuml.config.WindowState;
import com.aiplantuml.render.DiagramNodeIndexer;
import com.aiplantuml.render.PlantUmlRenderer;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MainView extends BorderPane {

    private static final String DEFAULT_SOURCE = "@startuml\nAlice -> Bob: hello\nBob --> Alice: hi\n@enduml";

    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_STEP = 1.25;

    private final Stage stage;
    private final PlantUmlRenderer renderer = new PlantUmlRenderer();
    private final CodeArea editor = new CodeArea(DEFAULT_SOURCE);
    private final ImageView diagramView = new ImageView();
    private final ScrollPane diagramScroll = new ScrollPane(diagramView);
    private final Label statusLabel = new Label();
    private final Label zoomLabel = new Label("100%");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(400));
    private final SplitPane splitPane = new SplitPane();
    private final VirtualizedScrollPane<CodeArea> editorScrollPane = new VirtualizedScrollPane<>(editor);
    private ChatPane chatPane;
    private QuestionPane questionPane;

    private File currentFile;
    private boolean dirty = false;
    private AppConfig appConfig = AppConfig.load();
    private final WindowState windowState = WindowState.load();
    private double zoomFactor = 1.0;
    private List<DiagramNodeIndexer.NodeArea> nodeAreas = List.of();
    private Map<String, Integer> nodeLineNumbers = Map.of();
    private byte[] lastRenderedPng;

    public MainView(Stage stage) {
        this.stage = stage;
        applyWindowState();
        getStylesheets().add(getClass().getResource("/app-theme.css").toExternalForm());

        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.getStylesheets().add(getClass().getResource("/plantuml-highlighting.css").toExternalForm());
        editor.setStyleSpans(0, PlantUmlHighlighter.computeHighlighting(DEFAULT_SOURCE));

        diagramScroll.setPannable(true);
        diagramView.setPreserveRatio(true);
        diagramScroll.addEventFilter(ScrollEvent.SCROLL, this::onDiagramScroll);
        diagramView.setOnMouseClicked(this::onDiagramClicked);

        Menu contextExportMenu = new Menu("Export", null, buildExportMenuItems());
        diagramScroll.setContextMenu(new ContextMenu(contextExportMenu));

        chatPane = new ChatPane(appConfig, editor::getText, code -> {
            editor.replaceText(code);
            renderNow();
        });
        questionPane = new QuestionPane(appConfig, editor::getText);

        TabPane aiTabPane = new TabPane();
        Tab editTab = new Tab("Edit Diagram", chatPane);
        editTab.setClosable(false);
        Tab askTab = new Tab("Ask Questions", questionPane);
        askTab.setClosable(false);
        aiTabPane.getTabs().addAll(editTab, askTab);

        Label aiCaption = new Label("🤖 AI Assistant");
        aiCaption.setMaxWidth(Double.MAX_VALUE);
        aiCaption.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 8 10 8 10; "
                + "-fx-background-color: #F0EEFA; "
                + "-fx-border-color: transparent transparent #DCD6F0 transparent; -fx-border-width: 0 0 1 0;");
        BorderPane aiPanel = new BorderPane();
        aiPanel.setTop(aiCaption);
        aiPanel.setCenter(aiTabPane);

        splitPane.getItems().addAll(editorScrollPane, diagramScroll, aiPanel);
        splitPane.setDividerPositions(windowState.getEditorDivider(), windowState.getDiagramDivider());
        setCenter(splitPane);

        setTop(buildMenuBar());

        HBox statusBar = new HBox(statusLabel, spacer(), zoomLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        setBottom(statusBar);

        debounce.setOnFinished(e -> renderNow());
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            dirty = true;
            updateTitle();
            editor.setStyleSpans(0, PlantUmlHighlighter.computeHighlighting(newVal));
            debounce.playFromStart();
        });

        stage.setOnCloseRequest(e -> saveWindowState());

        applyPaneBackgrounds();
        renderNow();
        updateTitle();
    }

    private void applyPaneBackgrounds() {
        BackgroundUtil.applyBackground(editor, appConfig.getEditorBackground());
        BackgroundUtil.applyBackground(diagramScroll, appConfig.getDiagramBackground());
        chatPane.applyBackground(appConfig.getChatBackground());
        questionPane.applyBackground(appConfig.getChatBackground());
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void applyWindowState() {
        if (windowState.getX() != null) stage.setX(windowState.getX());
        if (windowState.getY() != null) stage.setY(windowState.getY());
        stage.setWidth(windowState.getWidth());
        stage.setHeight(windowState.getHeight());
        stage.setMaximized(windowState.isMaximized());
    }

    private void saveWindowState() {
        if (!stage.isMaximized()) {
            windowState.setX(stage.getX());
            windowState.setY(stage.getY());
            windowState.setWidth(stage.getWidth());
            windowState.setHeight(stage.getHeight());
        }
        windowState.setMaximized(stage.isMaximized());
        double[] dividers = splitPane.getDividerPositions();
        if (dividers.length >= 2) {
            windowState.setEditorDivider(dividers[0]);
            windowState.setDiagramDivider(dividers[1]);
        }
        windowState.save();
    }

    private MenuBar buildMenuBar() {
        MenuItem newItem = new MenuItem("New");
        newItem.setOnAction(e -> newFile());

        MenuItem openItem = new MenuItem("Open...");
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        openItem.setOnAction(e -> openFile());

        MenuItem saveItem = new MenuItem("Save");
        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        saveItem.setOnAction(e -> saveFile());

        MenuItem saveAsItem = new MenuItem("Save As...");
        saveAsItem.setOnAction(e -> saveFileAs());

        Menu exportMenu = new Menu("Export", null, buildExportMenuItems());

        MenuItem settingsItem = new MenuItem("Settings...");
        settingsItem.setOnAction(e -> new SettingsDialog(appConfig).showAndWait()
                .filter(Boolean.TRUE::equals)
                .ifPresent(saved -> applyPaneBackgrounds()));

        Menu fileMenu = new Menu("File", null, newItem, openItem, saveItem, saveAsItem,
                new SeparatorMenuItem(), exportMenu,
                new SeparatorMenuItem(), settingsItem);

        MenuItem renderItem = new MenuItem("Render Now");
        renderItem.setOnAction(e -> renderNow());
        Menu diagramMenu = new Menu("Diagram", null, renderItem);

        MenuItem zoomInItem = new MenuItem("Zoom In");
        zoomInItem.setOnAction(e -> zoomIn());

        MenuItem zoomOutItem = new MenuItem("Zoom Out");
        zoomOutItem.setOnAction(e -> zoomOut());

        MenuItem zoomResetItem = new MenuItem("Reset Zoom");
        zoomResetItem.setOnAction(e -> resetZoom());

        Menu viewMenu = new Menu("View", null, zoomInItem, zoomOutItem, zoomResetItem);

        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> new AboutDialog().showAndWait());
        Menu helpMenu = new Menu("Help", null, aboutItem);

        return new MenuBar(fileMenu, diagramMenu, viewMenu, helpMenu);
    }

    private void onDiagramClicked(MouseEvent event) {
        if (event.getClickCount() != 2) return;

        double nativeX = event.getX() / zoomFactor;
        double nativeY = event.getY() / zoomFactor;

        for (DiagramNodeIndexer.NodeArea area : nodeAreas) {
            if (area.contains(nativeX, nativeY)) {
                copyToClipboard(area.name());
                Integer lineNumber = nodeLineNumbers.get(area.name());
                if (lineNumber != null) {
                    moveCaretToLine(lineNumber);
                }
                statusLabel.setTextFill(Color.DARKGREEN);
                statusLabel.setText("Copied \"" + area.name() + "\" to clipboard");
                return;
            }
        }
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void moveCaretToLine(int lineIndex) {
        String[] lines = editor.getText().split("\n", -1);
        if (lineIndex < 0 || lineIndex >= lines.length) return;
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset += lines[i].length() + 1;
        }
        int lineEnd = offset + lines[lineIndex].length();
        editor.requestFocus();
        editor.selectRange(offset, lineEnd);
    }

    private void onDiagramScroll(ScrollEvent event) {
        if (!event.isControlDown()) return;
        if (event.getDeltaY() > 0) {
            zoomIn();
        } else if (event.getDeltaY() < 0) {
            zoomOut();
        }
        event.consume();
    }

    private void zoomIn() {
        setZoom(zoomFactor * ZOOM_STEP);
    }

    private void zoomOut() {
        setZoom(zoomFactor / ZOOM_STEP);
    }

    private void resetZoom() {
        setZoom(1.0);
    }

    private void setZoom(double newZoom) {
        zoomFactor = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        applyZoom();
    }

    private void applyZoom() {
        Image image = diagramView.getImage();
        if (image == null) return;
        diagramView.setFitWidth(image.getWidth() * zoomFactor);
        diagramView.setFitHeight(image.getHeight() * zoomFactor);
        zoomLabel.setText(Math.round(zoomFactor * 100) + "%");
    }

    private FileChooser.ExtensionFilter pumlFilter() {
        return new FileChooser.ExtensionFilter("PlantUML files (*.puml, *.plantuml)", "*.puml", "*.plantuml");
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "You have unsaved changes. Discard them?", ButtonType.YES, ButtonType.CANCEL);
        alert.setHeaderText(null);
        alert.setTitle("Unsaved changes");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.YES;
    }

    private void newFile() {
        if (!confirmDiscardIfDirty()) return;
        editor.replaceText(DEFAULT_SOURCE);
        currentFile = null;
        dirty = false;
        updateTitle();
    }

    private void openFile() {
        if (!confirmDiscardIfDirty()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open PlantUML file");
        chooser.getExtensionFilters().add(pumlFilter());
        applyInitialDirectory(chooser);
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            editor.replaceText(content);
            currentFile = file;
            dirty = false;
            updateTitle();
            rememberDirectory(file);
        } catch (IOException ex) {
            showError("Failed to open file", ex.getMessage());
        }
    }

    private void applyInitialDirectory(FileChooser chooser) {
        String dir = windowState.getLastDirectory();
        if (dir != null) {
            File dirFile = new File(dir);
            if (dirFile.isDirectory()) {
                chooser.setInitialDirectory(dirFile);
            }
        }
    }

    private void rememberDirectory(File file) {
        File parent = file.getParentFile();
        if (parent != null) {
            windowState.setLastDirectory(parent.getAbsolutePath());
            windowState.save();
        }
    }

    private void saveFile() {
        if (currentFile == null) {
            saveFileAs();
            return;
        }
        writeToFile(currentFile);
    }

    private void saveFileAs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save PlantUML file");
        chooser.getExtensionFilters().add(pumlFilter());
        if (currentFile != null) {
            chooser.setInitialDirectory(currentFile.getParentFile());
            chooser.setInitialFileName(currentFile.getName());
        } else {
            applyInitialDirectory(chooser);
            chooser.setInitialFileName("diagram.puml");
        }
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        writeToFile(file);
        rememberDirectory(file);
    }

    private MenuItem[] buildExportMenuItems() {
        MenuItem pngItem = new MenuItem("PNG...");
        pngItem.setOnAction(e -> exportPng());

        MenuItem tableItem = new MenuItem("Steps Table (MD)...");
        tableItem.setOnAction(e -> exportMarkdown("steps table", appConfig.getExportTablePrompt(), "-steps"));

        MenuItem detailedItem = new MenuItem("Detailed Overview (MD)...");
        detailedItem.setOnAction(e -> exportMarkdown("detailed overview", appConfig.getExportDetailedPrompt(), "-overview"));

        MenuItem summaryItem = new MenuItem("Summary (MD)...");
        summaryItem.setOnAction(e -> exportMarkdown("summary", appConfig.getExportSummaryPrompt(), "-summary"));

        return new MenuItem[]{pngItem, tableItem, detailedItem, summaryItem};
    }

    private void exportMarkdown(String label, String prompt, String fileNameSuffix) {
        String currentCode = editor.getText();
        KimiClient client = new KimiClient(appConfig);

        statusLabel.setTextFill(Color.BLACK);
        statusLabel.setText("Generating " + label + "...");
        setAppBusy(true);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return client.generateMarkdown(currentCode, prompt);
            }
        };
        task.setOnSucceeded(e -> {
            setAppBusy(false);
            saveMarkdownToFile(task.getValue(), fileNameSuffix);
        });
        task.setOnFailed(e -> {
            setAppBusy(false);
            Throwable ex = task.getException();
            statusLabel.setTextFill(Color.FIREBRICK);
            statusLabel.setText("Export failed");
            showError("Failed to generate " + label, ex != null ? ex.getMessage() : "unknown error");
        });
        new Thread(task, "export-markdown").start();
    }

    /**
     * Disables the whole window (menu, editor, diagram, AI tabs) and shows a wait
     * cursor while a blocking async operation (like an export AI call) is in flight,
     * so it's clear something is happening rather than the app looking unresponsive.
     */
    private void setAppBusy(boolean busy) {
        setDisable(busy);
        if (getScene() != null) {
            getScene().setCursor(busy ? Cursor.WAIT : Cursor.DEFAULT);
        }
    }

    private void saveMarkdownToFile(String markdown, String fileNameSuffix) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export as Markdown");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md"));
        String baseName = currentFile != null ? stripExtension(currentFile.getName()) : "diagram";
        if (currentFile != null) {
            chooser.setInitialDirectory(currentFile.getParentFile());
        } else {
            applyInitialDirectory(chooser);
        }
        chooser.setInitialFileName(baseName + fileNameSuffix + ".md");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            statusLabel.setText("Rendered OK");
            return;
        }
        try {
            Files.writeString(file.toPath(), markdown, StandardCharsets.UTF_8);
            rememberDirectory(file);
            statusLabel.setTextFill(Color.DARKGREEN);
            statusLabel.setText("Exported to " + file.getName());
        } catch (IOException ex) {
            showError("Failed to save markdown file", ex.getMessage());
        }
    }

    private void exportPng() {
        if (lastRenderedPng == null) {
            showError("Nothing to export", "Render a diagram first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export diagram as PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image (*.png)", "*.png"));
        if (currentFile != null) {
            chooser.setInitialDirectory(currentFile.getParentFile());
            chooser.setInitialFileName(stripExtension(currentFile.getName()) + ".png");
        } else {
            applyInitialDirectory(chooser);
            chooser.setInitialFileName("diagram.png");
        }
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        try {
            Files.write(file.toPath(), lastRenderedPng);
            rememberDirectory(file);
            statusLabel.setTextFill(Color.DARKGREEN);
            statusLabel.setText("Exported to " + file.getName());
        } catch (IOException ex) {
            showError("Failed to export PNG", ex.getMessage());
        }
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void writeToFile(File file) {
        try {
            Files.writeString(file.toPath(), editor.getText(), StandardCharsets.UTF_8);
            currentFile = file;
            dirty = false;
            updateTitle();
        } catch (IOException ex) {
            showError("Failed to save file", ex.getMessage());
        }
    }

    private void showError(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private void updateTitle() {
        String name = currentFile != null ? currentFile.getName() : "Untitled";
        stage.setTitle("AI PlantUML - " + name + (dirty ? " *" : ""));
    }

    public CodeArea getEditor() {
        return editor;
    }

    public void renderNow() {
        PlantUmlRenderer.RenderResult result = renderer.render(editor.getText());
        if (result.isError()) {
            statusLabel.setTextFill(Color.FIREBRICK);
            statusLabel.setText("Error: " + result.errorText());
            return;
        }
        diagramView.setImage(new Image(new ByteArrayInputStream(result.png())));
        applyZoom();
        lastRenderedPng = result.png();
        nodeAreas = result.nodeAreas();
        nodeLineNumbers = result.nodeLineNumbers();
        statusLabel.setTextFill(Color.DARKGREEN);
        statusLabel.setText("Rendered OK");
    }
}
