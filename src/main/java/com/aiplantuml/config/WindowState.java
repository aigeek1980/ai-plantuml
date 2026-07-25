package com.aiplantuml.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class WindowState {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".aiplantuml");
    private static final Path STATE_FILE = CONFIG_DIR.resolve("window-state.properties");

    private Double x;
    private Double y;
    private double width = 1200;
    private double height = 800;
    private boolean maximized = false;
    private double editorDivider = 0.4;
    private double diagramDivider = 0.75;
    private String lastDirectory;

    public static WindowState load() {
        WindowState state = new WindowState();
        if (Files.exists(STATE_FILE)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(STATE_FILE)) {
                props.load(in);
                state.x = parseNullableDouble(props.getProperty("x"));
                state.y = parseNullableDouble(props.getProperty("y"));
                state.width = parseDouble(props.getProperty("width"), state.width);
                state.height = parseDouble(props.getProperty("height"), state.height);
                state.maximized = Boolean.parseBoolean(props.getProperty("maximized", "false"));
                state.editorDivider = parseDouble(props.getProperty("editorDivider"), state.editorDivider);
                state.diagramDivider = parseDouble(props.getProperty("diagramDivider"), state.diagramDivider);
                state.lastDirectory = props.getProperty("lastDirectory");
            } catch (IOException e) {
                throw new RuntimeException("Failed to read window state file: " + STATE_FILE, e);
            }
        }
        return state;
    }

    public void save() {
        Properties props = new Properties();
        if (x != null) props.setProperty("x", String.valueOf(x));
        if (y != null) props.setProperty("y", String.valueOf(y));
        props.setProperty("width", String.valueOf(width));
        props.setProperty("height", String.valueOf(height));
        props.setProperty("maximized", String.valueOf(maximized));
        props.setProperty("editorDivider", String.valueOf(editorDivider));
        props.setProperty("diagramDivider", String.valueOf(diagramDivider));
        if (lastDirectory != null) props.setProperty("lastDirectory", lastDirectory);
        try {
            Files.createDirectories(CONFIG_DIR);
            try (OutputStream out = Files.newOutputStream(STATE_FILE)) {
                props.store(out, "AI PlantUML window/layout state - local only, not synced");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write window state file: " + STATE_FILE, e);
        }
    }

    private static Double parseNullableDouble(String s) {
        return s == null ? null : Double.parseDouble(s);
    }

    private static double parseDouble(String s, double fallback) {
        return s == null ? fallback : Double.parseDouble(s);
    }

    public Double getX() {
        return x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public boolean isMaximized() {
        return maximized;
    }

    public void setMaximized(boolean maximized) {
        this.maximized = maximized;
    }

    public double getEditorDivider() {
        return editorDivider;
    }

    public void setEditorDivider(double editorDivider) {
        this.editorDivider = editorDivider;
    }

    public double getDiagramDivider() {
        return diagramDivider;
    }

    public void setDiagramDivider(double diagramDivider) {
        this.diagramDivider = diagramDivider;
    }

    public String getLastDirectory() {
        return lastDirectory;
    }

    public void setLastDirectory(String lastDirectory) {
        this.lastDirectory = lastDirectory;
    }
}
