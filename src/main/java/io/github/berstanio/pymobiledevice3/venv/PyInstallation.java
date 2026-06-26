package io.github.berstanio.pymobiledevice3.venv;

import java.io.File;

public class PyInstallation {
    private final File pythonHome;
    private final File pythonExecutable;
    private final File handler;

    public PyInstallation(File pythonHome, File pythonExecutable, File handler) {
        this.pythonHome = pythonHome;
        this.pythonExecutable = pythonExecutable;
        this.handler = handler;
    }

    public File getHandler() {
        return handler;
    }

    public File getPythonExecutable() {
        return pythonExecutable;
    }

    public File getPythonHome() {
        return pythonHome;
    }
}
