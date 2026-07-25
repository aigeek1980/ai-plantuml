package com.aiplantuml.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AppConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".aiplantuml");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");

    private static final String KEY_API_KEY = "kimi.apiKey";
    private static final String KEY_BASE_URL = "kimi.baseUrl";
    private static final String KEY_MODEL = "kimi.model";
    private static final String KEY_EDITOR_BG = "ui.editorBackground";
    private static final String KEY_DIAGRAM_BG = "ui.diagramBackground";
    private static final String KEY_CHAT_BG = "ui.chatBackground";

    private String apiKey = "";
    private String baseUrl = "https://api.moonshot.ai/v1";
    private String model = "kimi-k2.6";
    private String editorBackground = "#F7F9FB";
    private String diagramBackground = "#FFFFFF";
    private String chatBackground = "#F7F5FB";

    public static AppConfig load() {
        AppConfig config = new AppConfig();
        if (Files.exists(CONFIG_FILE)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
                props.load(in);
                config.apiKey = props.getProperty(KEY_API_KEY, "");
                config.baseUrl = props.getProperty(KEY_BASE_URL, config.baseUrl);
                config.model = props.getProperty(KEY_MODEL, config.model);
                config.editorBackground = props.getProperty(KEY_EDITOR_BG, config.editorBackground);
                config.diagramBackground = props.getProperty(KEY_DIAGRAM_BG, config.diagramBackground);
                config.chatBackground = props.getProperty(KEY_CHAT_BG, config.chatBackground);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read config file: " + CONFIG_FILE, e);
            }
        }
        return config;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty(KEY_API_KEY, apiKey);
        props.setProperty(KEY_BASE_URL, baseUrl);
        props.setProperty(KEY_MODEL, model);
        props.setProperty(KEY_EDITOR_BG, editorBackground);
        props.setProperty(KEY_DIAGRAM_BG, diagramBackground);
        props.setProperty(KEY_CHAT_BG, chatBackground);
        try {
            Files.createDirectories(CONFIG_DIR);
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "AI PlantUML configuration - local only, not synced");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config file: " + CONFIG_FILE, e);
        }
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEditorBackground() {
        return editorBackground;
    }

    public void setEditorBackground(String editorBackground) {
        this.editorBackground = editorBackground;
    }

    public String getDiagramBackground() {
        return diagramBackground;
    }

    public void setDiagramBackground(String diagramBackground) {
        this.diagramBackground = diagramBackground;
    }

    public String getChatBackground() {
        return chatBackground;
    }

    public void setChatBackground(String chatBackground) {
        this.chatBackground = chatBackground;
    }
}
