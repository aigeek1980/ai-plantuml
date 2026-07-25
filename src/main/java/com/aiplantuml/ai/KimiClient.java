package com.aiplantuml.ai;

import com.aiplantuml.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;

public class KimiClient {

    private static final String SYSTEM_PROMPT = """
            You are an assistant that edits PlantUML diagrams.
            You will be given the current PlantUML source code and an instruction describing a change.
            Respond with ONLY the complete, updated PlantUML source code.
            Preserve the same @start.../@end... tag pair used in the current diagram
            (for example @startuml/@enduml, @startmindmap/@endmindmap, @startwbs/@endwbs,
            @startsalt/@endsalt, @startgantt/@endgantt) - do not change the diagram type
            unless the instruction explicitly asks for a different kind of diagram.
            Do not include explanations, comments about the change, or markdown code fences.
            """;

    private final AppConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public KimiClient(AppConfig config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        buildTrustingSslContext().ifPresent(builder::sslContext);
        this.httpClient = builder.build();
    }

    /**
     * Builds an SSLContext that trusts the JVM's default CAs plus (on Windows) whatever
     * root CAs the OS itself trusts - the same store the browser uses. This is what lets
     * the app work behind a TLS-intercepting corporate proxy without bundling any
     * company-specific certificate: if IT has already pushed a corporate CA into Windows'
     * trust store (as is standard practice), the app picks it up automatically, on any
     * machine, with no per-company file to maintain.
     * Falls back to the JVM default (empty Optional) if anything goes wrong.
     */
    private java.util.Optional<SSLContext> buildTrustingSslContext() {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            String cacertsPath = System.getProperty("java.home") + "/lib/security/cacerts";
            try (InputStream in = new FileInputStream(cacertsPath)) {
                trustStore.load(in, "changeit".toCharArray());
            }

            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                mergeWindowsRootCertificates(trustStore);
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return java.util.Optional.of(sslContext);
        } catch (IOException | GeneralSecurityException e) {
            return java.util.Optional.empty();
        }
    }

    private void mergeWindowsRootCertificates(KeyStore trustStore) {
        try {
            KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
            windowsRoot.load(null, null);
            Enumeration<String> aliases = windowsRoot.aliases();
            int i = 0;
            while (aliases.hasMoreElements()) {
                Certificate cert = windowsRoot.getCertificate(aliases.nextElement());
                if (cert != null) {
                    trustStore.setCertificateEntry("windows-root-" + (i++), cert);
                }
            }
        } catch (GeneralSecurityException | IOException e) {
            // Windows-ROOT unavailable - silently fall back to the JVM's default CAs only.
        }
    }

    public String requestEdit(String currentCode, String instruction) throws IOException, InterruptedException {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("No API key configured. Open Settings and add your Kimi API key.");
        }

        String userPrompt = "Current PlantUML diagram:\n" + currentCode
                + "\n\nInstruction: " + instruction;

        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel());
        root.put("temperature", 0.3);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        String requestBody = mapper.writeValueAsString(root);
        String baseUrl = config.getBaseUrl().endsWith("/")
                ? config.getBaseUrl().substring(0, config.getBaseUrl().length() - 1)
                : config.getBaseUrl();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Kimi API error (HTTP " + response.statusCode() + "): " + response.body());
        }

        JsonNode responseJson = mapper.readTree(response.body());
        JsonNode content = responseJson.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IOException("Unexpected response from Kimi API: " + response.body());
        }

        return stripMarkdownFence(content.asText().trim());
    }

    private String stripMarkdownFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }
}
