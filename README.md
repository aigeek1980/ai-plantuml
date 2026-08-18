# AI PlantUML

A desktop PlantUML editor with AI-assisted diagram editing. Write PlantUML by hand, render it live, ask an AI model to create or modify diagrams for you in natural language, ask questions about a diagram without touching it, and export to PNG or AI-generated Markdown documentation.

## Download

Grab the latest self-contained Windows build from [Releases](https://github.com/aigeek1980/ai-plantuml/releases/latest) - unzip and run `AI PlantUML.exe`. No Java installation required, nothing else to set up.

## Features

- Live PlantUML rendering (sequence, class, mindmap, and other PlantUML diagram types) with syntax highlighting and line numbers
- **AI Assistant panel**, split into two tabs:
  - **Edit Diagram** - describe a change in plain English and the current diagram is rewritten accordingly
  - **Ask Questions** - ask things like *"what happens after the payment call?"* and get an answer without your diagram being touched
  - Works against any OpenAI-compatible chat completions API (Moonshot AI's Kimi models, OpenRouter, a self-hosted endpoint, etc.)
  - Chat history renders sender labels and basic markdown (bold, inline code) instead of plain text
- **Export**, from the File menu or by right-clicking the diagram:
  - PNG image
  - Steps Table (Markdown) - a Step/From/To/Call table
  - Detailed Overview (Markdown) - a full narrative writeup
  - Summary (Markdown) - a short summary
  - Each Markdown export uses a default AI prompt that's fully editable in Settings
  - The window locks with a wait cursor while an export's AI call is in flight, so it's clear something is happening
- Diagrams render as SVG, so zooming stays crisp at any level
- Click any participant *or any individual call* in the diagram to copy its name and jump the editor cursor straight to its source line; drag anywhere to pan
- Open/save `.puml` files, remembers the last-used folder
- Configurable pane background colors, remembered window size/position/layout
- Runs entirely locally: rendering happens in-process (PlantUML is used as a Java library), no external rendering service

## Requirements

- Java 21 or newer (JDK, not just a JRE) - only needed if running from source; the packaged release above bundles its own runtime
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

The same dialog also lets you customize the pane background colors and the three Markdown export prompts (each with a "Reset to default" button).

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
