# AI PlantUML

A desktop PlantUML editor with AI-assisted diagram editing **and a diagram player that executes a sequence diagram instead of just drawing it**.

Write PlantUML by hand and render it live. Ask an AI model to create or modify diagrams in natural language, or to answer questions about a diagram without touching it. Then **play** the diagram: the player treats it as a script of a flow and emulates the calls running one at a time, asking you which way each `alt` goes and how many times each `loop` repeats, and drawing only what actually happened. Export the result as PNG, as an animated GIF of the run, or as AI-generated Markdown documentation.

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
- **Diagram player** - emulates a sequence diagram as a running flow, one call at a time, with branches and loops decided by you as the run reaches them. See [Diagram player](#diagram-player) below
- Diagrams render as SVG, so zooming stays crisp at any level
- Click any participant *or any individual call* in the diagram to copy its name and jump the editor cursor straight to its source line; drag anywhere to pan
- Open/save `.puml` files, remembers the last-used folder
- Configurable pane background colors, remembered window size/position/layout
- Runs entirely locally: rendering happens in-process (PlantUML is used as a Java library), no external rendering service

## Diagram player

**Diagram > Play Diagram...** opens a sequence diagram in its own window and *runs* it. The editor is not involved and the document is never modified.

The idea is that a sequence diagram describes how a flow executes, not how it should be drawn. So the player reads it as a script and emulates the calls happening: it starts with nothing on screen and draws each call as it is made, asking you to decide anything the diagram leaves open. What you end up with is one particular execution - a trace of a real run - rather than the diagram itself.

### How each construct is executed

| In the diagram | What the player does |
| --- | --- |
| a call (`A -> B : ...`) | Draws it as the step runs. The lifelines it needs appear the first time they are used, so the picture only ever contains participants that have actually taken part. |
| `alt` / `opt` | Asks which path is taken, the moment the run reaches it. **Only the chosen path is played and drawn** - the path not taken is absent entirely, not an empty frame or a gap. Rewind past the question and it is asked again, so you can try the other way without restarting. |
| `loop` | Runs the body once, then asks at the end of the pass whether to go round again - you are not made to commit to a count before seeing what repeats. **Every "yes" draws the body's calls again**, in sequence, so a loop you run three times appears as three consecutive copies of its calls, the way it really happens. |
| `par` | Advances every lane together: one step takes the next call from each lane at once, so the lanes are genuinely side by side rather than one after another. A `par` written without any `else` is split by lifeline, so work described as simultaneous on several participants is shown that way. |
| `group` / `critical` | Run through; there is nothing to decide. |
| notes, separators, colours, arrow styles | Carried through untouched - each statement is re-emitted exactly as you wrote it. |

### Playing

- **Start where you are.** The run begins at the line your cursor is on, so a long diagram can be picked up at the part you are working on. A block your cursor sits inside keeps only the branch it is in and stops asking about itself. Leave the cursor alone and the run starts from the beginning.
- **Step, play, rewind.** Step forward or back one call at a time, or play continuously at a speed you set. Stepping back past a decision gives it up, so going forward again offers it afresh.
- **Follow the current call.** It is drawn in red, its two participants are picked out and named in the toolbar, and the view zooms so both ends of the call are on screen. **Follow in editor** moves the editor's cursor to the line being run.
- **Or reveal instead of build up.** Untick **Build up only what runs** and the player shows the whole diagram, revealing it step by step rather than drawing it as it goes.

### Recording and export

Tick **Record frames** before playing and every step is kept as a picture of the player exactly as drawn, highlighting included. **Export** then turns those frames into either:

- an **animated GIF**, with the frame delay you choose (the run loops, and the last frame is held a little longer)
- **one PNG per step**, with the `ffmpeg` command for turning them into a video written alongside them

Exports are named after the diagram and the time they were taken, e.g. `order-flow - play - 2026-10-10_22-15-00.gif`.

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
  play/                     - the diagram player and its GIF/frame export
  render/                   - PlantUML rendering + diagram-node hit-testing
  ui/                       - JavaFX UI components
```
