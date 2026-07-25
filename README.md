# AI PlantUML

A desktop PlantUML editor with AI-assisted diagram editing. Write PlantUML by hand, render it live, and ask an AI model to create or modify diagrams for you in natural language.

## Features

- Live PlantUML rendering (sequence, class, mindmap, and other PlantUML diagram types) with syntax highlighting and line numbers
- AI chat panel: describe a change in plain English and the current diagram is rewritten accordingly (works against any OpenAI-compatible chat completions API - Moonshot AI's Kimi models, OpenRouter, a self-hosted endpoint, etc.)
- Zoom, pan, and double-click a diagram node to copy its name and jump the editor cursor to its source line
- Open/save `.puml` files, remembers the last-used folder
- Configurable pane background colors, remembered window size/position/layout
- Runs entirely locally: rendering happens in-process (PlantUML is used as a Java library), no external rendering service

## Requirements

- Java 21 or newer (JDK, not just a JRE)
- No Maven installation needed - this project uses the Maven Wrapper (`mvnw`/`mvnw.cmd`), which downloads Maven automatically on first run

## Running from source

```
# Windows
mvnw.cmd javafx:run

# macOS/Linux
./mvnw javafx:run
```

## Configuring the AI

Open **File > Settings...** in the app and enter:
- **API Key** - your key for the AI provider
- **Base URL** - the provider's OpenAI-compatible base URL (e.g. `https://api.moonshot.ai/v1`)
- **Model** - the model name to use

Settings are stored locally in `~/.aiplantuml/config.properties` and are never committed to source control.

On Windows, HTTPS calls also trust whatever root certificates the OS itself trusts (via the `Windows-ROOT` keystore) in addition to the JVM's default CAs - useful if you're behind a TLS-intercepting corporate proxy, with no extra configuration needed.

## Building a standalone distribution

```
powershell -ExecutionPolicy Bypass -File packaging\build-installer.ps1
```

This produces a self-contained Windows app-image (bundled JRE, no Java install required on the target machine) at `target\installer\AI-PlantUML-<version>-win.zip`. Requires `jpackage` (bundled with the JDK, JDK 17+) on `PATH`.

## Project structure

```
src/main/java/com/aiplantuml/
  App.java, Launcher.java   - JavaFX application entry point
  ai/                       - AI provider client
  config/                   - local settings persistence
  render/                   - PlantUML rendering + diagram-node hit-testing
  ui/                       - JavaFX UI components
```
