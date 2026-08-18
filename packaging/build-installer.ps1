# Builds a standalone Windows app-image for AI PlantUML using jpackage.
# Output: target\installer\AI PlantUML\AI PlantUML.exe (+ bundled runtime),
# zipped as target\installer\AI-PlantUML-<version>-win.zip.
#
# Requires: JDK 17+ with jpackage on PATH (bundled with the JDK itself, no extra install).
# Produces a self-contained app-image (no installer, no WiX Toolset required) -
# recipients just unzip and run the .exe, no Java install needed on their machine.

$ErrorActionPreference = "Stop"
$version = "1.6.1"

$root = Resolve-Path "$PSScriptRoot\.."
Set-Location $root

Write-Host "Building application jar and collecting dependencies..."
& "$root\mvnw.cmd" package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$dest = "$root\target\installer"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }

Write-Host "Running jpackage..."
# --add-modules ALL-MODULE-PATH: bundle every JDK module rather than jpackage/jlink's
# static classpath-analysis default. That auto-detection misses modules only referenced
# via runtime string lookups (e.g. KimiClient's KeyStore.getInstance("Windows-ROOT"),
# which needs jdk.crypto.mscapi) - and passing --add-modules with a specific module name
# REPLACES the auto-detected set rather than adding to it, which previously produced a
# runtime with only java.base + jdk.crypto.mscapi and nothing else, so the JVM couldn't
# even start. Bundling everything trades some installer size for not having this class of
# bug resurface every time some library does a reflective/service-loader/JCA lookup.
# --java-options MinHeapFreeRatio/MaxHeapFreeRatio: by default the JVM barely shrinks the
# heap once it's grown (it tolerates up to ~70% free space before giving any back to the
# OS), so after rendering one large diagram the process just sits there holding that
# memory for the rest of the session. These make the GC hand committed-but-unused heap
# back to the OS once free space exceeds 20%, instead of hoarding it.
jpackage `
    --type app-image `
    --input "$root\target\jpackage-input" `
    --dest $dest `
    --name "AI PlantUML" `
    --main-jar ai-plantuml-1.0-SNAPSHOT.jar `
    --main-class com.aiplantuml.Launcher `
    --icon "$root\packaging\windows\app-icon.ico" `
    --app-version $version `
    --vendor "AI PlantUML" `
    --description "PlantUML editor with AI-assisted diagram editing" `
    --add-modules ALL-MODULE-PATH `
    --java-options "-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$zipPath = "$dest\AI-PlantUML-$version-win.zip"
Write-Host "Zipping to $zipPath ..."
Compress-Archive -Path "$dest\AI PlantUML" -DestinationPath $zipPath -Force

Write-Host ""
Write-Host "Done. App-image: $dest\AI PlantUML\AI PlantUML.exe"
Write-Host "      Zipped:    $zipPath"
