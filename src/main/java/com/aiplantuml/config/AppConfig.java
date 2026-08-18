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
    private static final String KEY_EXPORT_TABLE_PROMPT = "export.tablePrompt";
    private static final String KEY_EXPORT_DETAILED_PROMPT = "export.detailedPrompt";
    private static final String KEY_EXPORT_SUMMARY_PROMPT = "export.summaryPrompt";
    private static final String KEY_AI_TIMEOUT_SECONDS = "kimi.timeoutSeconds";

    public static final int DEFAULT_AI_TIMEOUT_SECONDS = 60;

    public static final String DEFAULT_EXPORT_TABLE_PROMPT = """
            Analyze this PlantUML diagram and output a markdown table listing each \
            interaction/step in order, with these exact columns: Step, From, To, Call. \
            "Step" is the sequential step number starting at 1, "From" is the sender/source \
            participant, "To" is the receiver/target participant, "Call" is the message or \
            action name. Include only the table (a header row, a separator row, and the data \
            rows) and nothing else.""";

    public static final String DEFAULT_EXPORT_DETAILED_PROMPT = """
            Analyze this PlantUML diagram and write a detailed overview of it in markdown \
            format. Include: a short introduction describing what the diagram represents, a \
            list of all participants/elements with a brief description of each, and a \
            thorough walkthrough of the interactions/flow shown in the diagram. Use markdown \
            headings and bullet points where appropriate.""";

    public static final String DEFAULT_EXPORT_SUMMARY_PROMPT = """
            Analyze this PlantUML diagram and write a short summary of it in markdown format \
            - no more than 3-5 sentences describing what the diagram shows and its overall \
            purpose. Do not list every individual step.""";

    private String apiKey = "";
    private String baseUrl = "https://api.moonshot.ai/v1";
    private String model = "kimi-k2.6";
    private String editorBackground = "#F7F9FB";
    private String diagramBackground = "#FFFFFF";
    private String chatBackground = "#F7F5FB";
    private String exportTablePrompt = DEFAULT_EXPORT_TABLE_PROMPT;
    private String exportDetailedPrompt = DEFAULT_EXPORT_DETAILED_PROMPT;
    private String exportSummaryPrompt = DEFAULT_EXPORT_SUMMARY_PROMPT;
    private int aiTimeoutSeconds = DEFAULT_AI_TIMEOUT_SECONDS;

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
                config.exportTablePrompt = props.getProperty(KEY_EXPORT_TABLE_PROMPT, config.exportTablePrompt);
                config.exportDetailedPrompt = props.getProperty(KEY_EXPORT_DETAILED_PROMPT, config.exportDetailedPrompt);
                config.exportSummaryPrompt = props.getProperty(KEY_EXPORT_SUMMARY_PROMPT, config.exportSummaryPrompt);
                config.aiTimeoutSeconds = parseTimeout(props.getProperty(KEY_AI_TIMEOUT_SECONDS), config.aiTimeoutSeconds);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read config file: " + CONFIG_FILE, e);
            }
        }
        return config;
    }

    private static int parseTimeout(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty(KEY_API_KEY, apiKey);
        props.setProperty(KEY_BASE_URL, baseUrl);
        props.setProperty(KEY_MODEL, model);
        props.setProperty(KEY_EDITOR_BG, editorBackground);
        props.setProperty(KEY_DIAGRAM_BG, diagramBackground);
        props.setProperty(KEY_CHAT_BG, chatBackground);
        props.setProperty(KEY_EXPORT_TABLE_PROMPT, exportTablePrompt);
        props.setProperty(KEY_EXPORT_DETAILED_PROMPT, exportDetailedPrompt);
        props.setProperty(KEY_EXPORT_SUMMARY_PROMPT, exportSummaryPrompt);
        props.setProperty(KEY_AI_TIMEOUT_SECONDS, String.valueOf(aiTimeoutSeconds));
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

    public String getExportTablePrompt() {
        return exportTablePrompt;
    }

    public void setExportTablePrompt(String exportTablePrompt) {
        this.exportTablePrompt = exportTablePrompt;
    }

    public String getExportDetailedPrompt() {
        return exportDetailedPrompt;
    }

    public void setExportDetailedPrompt(String exportDetailedPrompt) {
        this.exportDetailedPrompt = exportDetailedPrompt;
    }

    public String getExportSummaryPrompt() {
        return exportSummaryPrompt;
    }

    public void setExportSummaryPrompt(String exportSummaryPrompt) {
        this.exportSummaryPrompt = exportSummaryPrompt;
    }

    public int getAiTimeoutSeconds() {
        return aiTimeoutSeconds;
    }

    public void setAiTimeoutSeconds(int aiTimeoutSeconds) {
        this.aiTimeoutSeconds = aiTimeoutSeconds;
    }
}
