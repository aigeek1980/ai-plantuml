package com.aiplantuml.ui;

import com.aiplantuml.ai.KimiClient;
import com.aiplantuml.config.AppConfig;
import com.aiplantuml.config.WindowState;
import com.aiplantuml.render.DiagramNodeIndexer;
import com.aiplantuml.render.PlantUmlRenderer;
import javafx.animation.PauseTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
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
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.w3c.dom.Document;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private final WebView diagramView = new WebView();
    private final Label statusLabel = new Label();
    private final Label zoomLabel = new Label("100%");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(1000));
    private final SplitPane splitPane = new SplitPane();
    private final VirtualizedScrollPane<CodeArea> editorScrollPane = new VirtualizedScrollPane<>(editor);
    private ChatPane chatPane;
    private QuestionPane questionPane;

    private File currentFile;
    private boolean dirty = false;
    private AppConfig appConfig = AppConfig.load();
    private final WindowState windowState = WindowState.load();
    private double zoomFactor = 1.0;
    private Map<String, Integer> nodeLineNumbers = Map.of();
    private CollapseToggle editorToggle;
    private CollapseToggle aiToggle;
    private final DoubleProperty aiPromptHeight = new SimpleDoubleProperty();

    public MainView(Stage stage) {
        this.stage = stage;
        applyWindowState();
        aiPromptHeight.set(windowState.getAiPromptHeight());
        getStylesheets().add(getClass().getResource("/app-theme.css").toExternalForm());

        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.getStylesheets().add(getClass().getResource("/plantuml-highlighting.css").toExternalForm());
        editor.setStyleSpans(0, PlantUmlHighlighter.computeHighlighting(DEFAULT_SOURCE));
        editor.setContextMenu(buildEditorContextMenu());

        diagramView.setContextMenuEnabled(false);
        diagramView.addEventFilter(ScrollEvent.SCROLL, this::onDiagramScroll);
        installNodeLinkHandler();

        Menu contextExportMenu = new Menu("Export", null, buildExportMenuItems());
        ContextMenu diagramContextMenu = new ContextMenu(contextExportMenu);
        diagramView.setOnContextMenuRequested(e ->
                diagramContextMenu.show(diagramView, e.getScreenX(), e.getScreenY()));
        // A popup's own auto-hide never fires for clicks landing inside the WebView,
        // since the embedded page consumes them - so the menu would stay open while
        // panning underneath it. An event filter sees the press before the page does.
        diagramView.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (diagramContextMenu.isShowing()) {
                diagramContextMenu.hide();
            }
        });

        chatPane = new ChatPane(appConfig, editor::getText, code -> {
            editor.replaceText(code);
            renderNow();
        }, aiPromptHeight);
        questionPane = new QuestionPane(appConfig, editor::getText, aiPromptHeight);

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

        editorToggle = new CollapseToggle(0, 0.0, windowState.getEditorDivider(),
                "‹", "›", Pos.CENTER_LEFT, "Collapse/expand the editor");
        aiToggle = new CollapseToggle(1, 1.0, windowState.getDiagramDivider(),
                "›", "‹", Pos.CENTER_RIGHT, "Collapse/expand the AI assistant");

        StackPane diagramPane = new StackPane(diagramView, editorToggle.node(), aiToggle.node());

        // A SplitPane won't drag a divider past an item's minimum width, and these panes
        // derive a non-zero one from their content - so "collapsed" would otherwise leave
        // a visible sliver rather than hiding the pane.
        editorScrollPane.setMinWidth(0);
        aiPanel.setMinWidth(0);

        splitPane.getItems().addAll(editorScrollPane, diagramPane, aiPanel);
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
        chatPane.applyBackground(appConfig.getChatBackground());
        questionPane.applyBackground(appConfig.getChatBackground());
        // The diagram pane's background lives inside the WebView's own HTML document
        // rather than on a JavaFX Region, so it's applied by re-rendering.
        renderNow();
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private ContextMenu buildEditorContextMenu() {
        MenuItem cutItem = new MenuItem("Cut");
        cutItem.setOnAction(e -> editor.cut());

        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> editor.copy());

        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setOnAction(e -> editor.paste());

        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setOnAction(e -> editor.selectAll());

        ContextMenu menu = new ContextMenu(cutItem, copyItem, new SeparatorMenuItem(), pasteItem,
                new SeparatorMenuItem(), selectAllItem);
        menu.setOnShowing(e -> {
            boolean hasSelection = !editor.getSelectedText().isEmpty();
            cutItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSelection);
            pasteItem.setDisable(!Clipboard.getSystemClipboard().hasString());
        });
        return menu;
    }

    /**
     * Collapses one of the outer split panes flush against its divider, and restores it
     * to the width it had beforehand.
     * <p>
     * Both toggles are overlaid on the middle (diagram) pane rather than on the pane
     * each one collapses. A button placed on its own pane disappears along with it once
     * that pane is collapsed to zero width, leaving no way to expand it again; the
     * diagram pane is never collapsible, so buttons there always stay reachable. They
     * also aren't injected into the SplitPane divider's own skin node, which isn't
     * guaranteed to exist or keep a usable size across skin implementations.
     */
    private final class CollapseToggle {

        private final Button button = new Button();
        private final int dividerIndex;
        private final double collapsedPosition;
        private final String collapseGlyph;
        private final String expandGlyph;
        private double restorePosition;
        private boolean collapsed;

        CollapseToggle(int dividerIndex, double collapsedPosition, double initialRestorePosition,
                       String collapseGlyph, String expandGlyph, Pos side, String tooltip) {
            this.dividerIndex = dividerIndex;
            this.collapsedPosition = collapsedPosition;
            this.restorePosition = initialRestorePosition;
            this.collapseGlyph = collapseGlyph;
            this.expandGlyph = expandGlyph;

            button.setText(collapseGlyph);
            button.getStyleClass().add("split-collapse-button");
            button.setFocusTraversable(false);
            button.setTooltip(new Tooltip(tooltip));
            button.setOnAction(e -> toggle());
            StackPane.setAlignment(button, side);
        }

        private void toggle() {
            SplitPane.Divider divider = splitPane.getDividers().get(dividerIndex);
            if (collapsed) {
                divider.setPosition(restorePosition);
                button.setText(collapseGlyph);
            } else {
                restorePosition = divider.getPosition();
                divider.setPosition(collapsedPosition);
                button.setText(expandGlyph);
            }
            collapsed = !collapsed;
        }

        /**
         * The width to remember across restarts. While collapsed the divider itself sits
         * at the collapsed position, and persisting that would reopen the app with the
         * pane missing - and, since the restore width would have been lost, no way to
         * get it back.
         */
        double positionToPersist() {
            return collapsed ? restorePosition : splitPane.getDividers().get(dividerIndex).getPosition();
        }

        Button node() {
            return button;
        }
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
        if (splitPane.getDividers().size() >= 2) {
            windowState.setEditorDivider(editorToggle.positionToPersist());
            windowState.setDiagramDivider(aiToggle.positionToPersist());
        }
        windowState.setAiPromptHeight(aiPromptHeight.get());
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

    /**
     * Hooks node clicks straight off the rendered SVG's DOM: PlantUML wraps each
     * linked element in an {@code <a xlink:href="node://name">} around its real vector
     * shape, so the clickable region is the element itself - no pixel coordinates, no
     * zoom math, and nothing that can drift out of sync with what's on screen.
     * <p>
     * The listener is re-attached on every page load because loading new content
     * replaces the whole document (and with it every node the old listener was on).
     */
    private void installNodeLinkHandler() {
        WebEngine engine = diagramView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState != Worker.State.SUCCEEDED) return;
            Document doc = engine.getDocument();
            // One delegated listener on the document rather than one per anchor: each
            // registration bridges a Java object into the DOM, and a large diagram has
            // hundreds of linked elements to re-register on every single render.
            if (doc instanceof EventTarget target) {
                target.addEventListener("click", nodeClickListener, false);
            }
        });
    }

    private final EventListener nodeClickListener = new EventListener() {
        @Override
        public void handleEvent(Event event) {
            if (!(event.getTarget() instanceof org.w3c.dom.Node clicked)) return;

            // The click lands on whatever shape or text sits inside the link, so walk
            // outwards to find the anchor that encloses it - or, for a node whose box was
            // tagged because its link covers only the label, the tag itself.
            String href = null;
            for (org.w3c.dom.Node n = clicked; n != null && href == null; n = n.getParentNode()) {
                if (!(n instanceof org.w3c.dom.Element element)) continue;
                if ("a".equalsIgnoreCase(element.getTagName())) {
                    href = element.getAttribute("xlink:href");
                    if (href == null || href.isBlank()) {
                        href = element.getAttribute("href");
                    }
                } else if (element.hasAttribute("data-node-href")) {
                    href = element.getAttribute("data-node-href");
                }
            }
            if (href == null || !href.startsWith(DiagramNodeIndexer.LINK_PREFIX)) return;

            event.preventDefault();
            event.stopPropagation();

            String name = URLDecoder.decode(href.substring(DiagramNodeIndexer.LINK_PREFIX.length()),
                    StandardCharsets.UTF_8);
            copyToClipboard(name);
            Integer lineNumber = nodeLineNumbers.get(name);
            if (lineNumber != null) {
                moveCaretToLine(lineNumber);
            }
            statusLabel.setTextFill(Color.DARKGREEN);
            statusLabel.setText("Copied \"" + name + "\" to clipboard");
        }
    };

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
        // Selecting a range highlights it but leaves the viewport where it was, so a
        // jump to an off-screen line would silently do nothing visible. Center the
        // target line instead of merely nudging it into view, so the surrounding
        // context is visible too.
        editor.showParagraphAtCenter(lineIndex);
        editor.requestFollowCaret();
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
        diagramView.setZoom(zoomFactor);
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
        PlantUmlRenderer.PngRenderResult rendered = renderer.renderPng(editor.getText());
        if (rendered.isError()) {
            showError("Nothing to export", "The diagram has errors: " + rendered.errorText());
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
            Files.write(file.toPath(), rendered.png());
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
        PlantUmlRenderer.SvgRenderResult result = renderer.renderSvg(editor.getText());
        if (result.isError()) {
            statusLabel.setTextFill(Color.FIREBRICK);
            statusLabel.setText("Error: " + result.errorText());
            return;
        }
        nodeLineNumbers = result.nodeLineNumbers();
        diagramView.getEngine().loadContent(wrapSvg(result.svg()), "text/html");
        applyZoom();
        statusLabel.setTextFill(Color.DARKGREEN);
        statusLabel.setText("Rendered OK");
    }

    /**
     * Wraps the raw SVG in a minimal HTML document. The margin/padding reset stops the
     * browser's default body margin from offsetting the diagram, and cursor:pointer on
     * linked elements makes clickable nodes discoverable on hover.
     * <p>
     * The script restores click-drag panning, which came free from the old ScrollPane
     * but has no WebView equivalent - important for large diagrams where scrollbars
     * alone are awkward. A drag past a few pixels is treated as a pan rather than a
     * click, and the click that follows it is swallowed in the capture phase so panning
     * over a node doesn't also navigate to it.
     */
    private String wrapSvg(String svg) {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><style>
                  html, body { margin: 0; padding: 0; background: %s; cursor: grab; }
                  html, body, svg, svg * { -webkit-user-select: none; user-select: none; }
                  /* PlantUML sizes each label with textLength + lengthAdjust="spacing",
                     measured against the literal source text. HTML collapses runs of
                     whitespace, so a label written with two consecutive spaces renders
                     narrower than measured and the glyphs get splayed across the declared
                     width to compensate. Keeping the spacing intact keeps the two in step. */
                  svg text { white-space: pre; }
                  body.panning { cursor: grabbing; }
                  a { cursor: pointer; -webkit-user-drag: none; }
                </style></head><body>%s<script>
                // PlantUML wraps a sequence participant's box and label together in one
                // link, but a mindmap node's link covers only its text - so clicking the
                // node's coloured box, which is what you actually aim at, hits nothing.
                // Tag each such node's background with its link so the whole box works.
                window.addEventListener('load', function () {
                  var LINK = 'node://';
                  var shapes = document.querySelectorAll('rect, path, polygon, ellipse');
                  var boxes = [];
                  for (var s = 0; s < shapes.length; s++) {
                    boxes.push({ el: shapes[s], r: shapes[s].getBoundingClientRect() });
                  }
                  var anchors = document.querySelectorAll('a');
                  for (var i = 0; i < anchors.length; i++) {
                    var a = anchors[i];
                    var href = a.getAttribute('href')
                            || a.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
                    if (!href || href.indexOf(LINK) !== 0) continue;
                    if (a.querySelector('rect, path, polygon, ellipse')) continue;
                    var t = a.getBoundingClientRect();
                    if (!t.width) continue;
                    // The smallest shape enclosing the label is its own box; anything
                    // larger is an ancestor grouping several nodes together.
                    var best = null, bestArea = Infinity;
                    for (var b = 0; b < boxes.length; b++) {
                      var r = boxes[b].r;
                      if (r.left > t.left + 1 || r.top > t.top + 1) continue;
                      if (r.right < t.right - 1 || r.bottom < t.bottom - 1) continue;
                      var area = r.width * r.height;
                      if (area < bestArea) { bestArea = area; best = boxes[b].el; }
                    }
                    if (best) { best.setAttribute('data-node-href', href); }
                  }
                });

                (function () {
                  var PAN_THRESHOLD = 4;
                  var down = false, panned = false, lastX = 0, lastY = 0;
                  var pendingX = 0, pendingY = 0, frameQueued = false;

                  // Scrolling on every mousemove repaints faster than a large diagram can
                  // be redrawn, so events pile up and the drag stalls. Accumulate the
                  // deltas and apply them once per frame instead.
                  function flush() {
                    frameQueued = false;
                    if (pendingX || pendingY) {
                      window.scrollBy(pendingX, pendingY);
                      pendingX = 0; pendingY = 0;
                    }
                  }

                  function endPan() {
                    if (!down) return;
                    down = false;
                    document.body.classList.remove('panning');
                  }

                  // Dragging from an <a> otherwise starts WebKit's native link
                  // drag-and-drop, which fights the pan for control of the gesture.
                  document.addEventListener('dragstart', function (e) { e.preventDefault(); });

                  document.addEventListener('mousedown', function (e) {
                    if (e.button !== 0) return;
                    down = true; panned = false;
                    lastX = e.clientX; lastY = e.clientY;
                    // Suppresses the browser's own drag gestures (text selection, link
                    // dragging) without suppressing the click that follows.
                    e.preventDefault();
                  });

                  document.addEventListener('mousemove', function (e) {
                    if (!down) return;
                    // Releasing the button outside the view never delivers a mouseup here,
                    // which would otherwise leave the pan stuck on until the next click.
                    if (e.buttons === 0) { endPan(); return; }
                    var dx = e.clientX - lastX, dy = e.clientY - lastY;
                    if (!panned && Math.abs(dx) + Math.abs(dy) < PAN_THRESHOLD) return;
                    if (!panned) { panned = true; document.body.classList.add('panning'); }
                    pendingX -= dx; pendingY -= dy;
                    lastX = e.clientX; lastY = e.clientY;
                    if (!frameQueued) { frameQueued = true; requestAnimationFrame(flush); }
                    e.preventDefault();
                  });

                  document.addEventListener('mouseup', endPan);

                  document.addEventListener('click', function (e) {
                    if (!panned) return;
                    panned = false;
                    e.preventDefault();
                    e.stopPropagation();
                  }, true);
                })();
                </script></body></html>
                """.formatted(appConfig.getDiagramBackground(), svg);
    }
}
