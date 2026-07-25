package com.aiplantuml;

/**
 * Indirection entry point so packaged (jpackage) launches don't invoke an
 * Application subclass's main() directly - that specific invocation shape can
 * trigger JavaFX's "runtime components are missing" check outside of module-path
 * launches, even when the classpath is fully populated.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
