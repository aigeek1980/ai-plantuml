# Builds a standalone Windows app-image for AI PlantUML using jpackage.
# Output: target\installer\AI PlantUML\AI PlantUML.exe (+ bundled runtime),
# zipped as target\installer\AI-PlantUML-<version>-win.zip.
#
# Requires: JDK 17+ with jpackage on PATH (bundled with the JDK itself, no extra install).
# Produces a self-contained app-image (no installer, no WiX Toolset required) -
# recipients just unzip and run the .exe, no Java install needed on their machine.

$ErrorActionPreference = "Stop"
$version = "1.0.0"

$root = Resolve-Path "$PSScriptRoot\.."
Set-Location $root

Write-Host "Building application jar and collecting dependencies..."
& "$root\mvnw.cmd" package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$dest = "$root\target\installer"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }

Write-Host "Running jpackage..."
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
    --description "PlantUML editor with AI-assisted diagram editing"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$zipPath = "$dest\AI-PlantUML-$version-win.zip"
Write-Host "Zipping to $zipPath ..."
Compress-Archive -Path "$dest\AI PlantUML" -DestinationPath $zipPath -Force

Write-Host ""
Write-Host "Done. App-image: $dest\AI PlantUML\AI PlantUML.exe"
Write-Host "      Zipped:    $zipPath"
