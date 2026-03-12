package io.github.berstanio.pymobiledevice3.venv;

import com.badlogic.gdx.jnigen.commons.HostDetection;
import com.badlogic.gdx.jnigen.commons.Os;
import io.github.berstanio.pymobiledevice3.ipc.PyMobileDevice3IPC;
import org.semver4j.Semver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class PyInstallationHandler {

    private static final boolean DEBUG = System.getProperty("java.pymobiledevice3.debug") != null;

    private static final String VENV_NAME = ".venv-" + PyMobileDevice3IPC.PROTOCOL_VERSION;
    private static final String HANDLER_NAME = "handler.py";
    private static final String REQUIREMENTS_NAME = "requirements.txt";
    private static final String PYTHON_PATH = "bin/python3";
    private static final String BASE_URL = "https://raw.githubusercontent.com/multi-os-engine/IPCPyMobileDevice3/refs/tags/v" + PyMobileDevice3IPC.PROTOCOL_VERSION;
    private static final String HANDLER_DEFAULT_URL = BASE_URL + "/" + HANDLER_NAME;
    private static final String REQUIREMENTS_DEFAULT_URL = BASE_URL + "/" + REQUIREMENTS_NAME;

    public static PyInstallation install(File directory) {
        try {
            return PyInstallationHandler.install(directory, new URL(HANDLER_DEFAULT_URL), new URL(REQUIREMENTS_DEFAULT_URL));
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to resolve IPC URL", e);
        }
    }

    public static PyInstallation install(File directory, File ipcBaseDirectory) {
        directory.mkdirs();
        if (!directory.exists() || !directory.isDirectory())
            throw new IllegalArgumentException(directory.getAbsolutePath() + " does not exist or is not a directory");

        try {
            Files.copy(ipcBaseDirectory.toPath().resolve(HANDLER_NAME), directory.toPath().resolve(HANDLER_NAME), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(ipcBaseDirectory.toPath().resolve(REQUIREMENTS_NAME), directory.toPath().resolve(REQUIREMENTS_NAME), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy files", e);
        }

        return installInternal(directory);
    }

    public static PyInstallation install(File directory, URL handlerFile, URL requirementsFile) {
        directory.mkdirs();
        if (!directory.exists() || !directory.isDirectory())
            throw new IllegalArgumentException(directory.getAbsolutePath() + " does not exist or is not a directory");

        try {
            try (InputStream in = handlerFile.openStream()) {
                Files.copy(in, directory.toPath().resolve(HANDLER_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
            try (InputStream in = requirementsFile.openStream()) {
                Files.copy(in, directory.toPath().resolve(REQUIREMENTS_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to download files", e);
        }

        return installInternal(directory);
    }

    private static PyInstallation installInternal(File directory) {
        File venv = new File(directory, VENV_NAME);
        if (venv.exists()) {
            if (checkVEnv(venv)) {
                installRequirements(directory);

                return finalizeInstallation(directory);
            }
            if (!deleteRecursively(venv))
                throw new IllegalStateException(venv.getAbsolutePath() + " exists, is invalid and cannot be deleted");
        }

        // We have no env. Create it
        if (!checkPythonVersion(resolveCommand("python3")))
            throw new IllegalStateException("Unable to find python 3.10.0+ installation");

        ProcessBuilder pb = new ProcessBuilder(resolveCommand("python3"), "-m", "venv", venv.getAbsolutePath());

        if (DEBUG) {
            pb.inheritIO();
        } else {
            pb.redirectErrorStream(true);
        }

        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Failed to install venv: " + stdout);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to install venv " + venv.getAbsolutePath(), e);
        }

        if (!checkVEnv(venv)) {
            throw new IllegalStateException("Installed venv at " + venv.getAbsolutePath() + ", but it's invalid?");
        }

        installRequirements(directory);

        return finalizeInstallation(directory);
    }

    private static PyInstallation finalizeInstallation(File directory) {
        if (HostDetection.os == Os.MacOsX) {
            // MacOS is dumb and launching tunneld with osascript looses file perm chain.
            // So if we install venv on external hard drive, it will fail
            // So we copy to temp dir, so everything has perms on it
            File temp = copyToTempDir(directory);
            File tmpVEnv = new File(temp, VENV_NAME);
            return new PyInstallation(tmpVEnv, new File(tmpVEnv, PYTHON_PATH), new File(temp, HANDLER_NAME));
        } else {
            File venv = new File(directory, VENV_NAME);
            return new PyInstallation(venv, new File(venv, PYTHON_PATH), new File(directory, HANDLER_NAME));
        }
    }

    private static File copyToTempDir(File directory) {
        try {
            Path tempDir = Files.createTempDirectory("javapymobiledevice3");
            Path sourceDir = directory.toPath();
            int code = new ProcessBuilder("rsync", "-a", sourceDir + "/", tempDir + "/")
                    .inheritIO()
                    .start()
                    .waitFor();
            if (code != 0)
                throw new IllegalStateException("Failed to copy " + directory.getAbsolutePath() + " to " + tempDir.toAbsolutePath());

            return tempDir.toFile();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean checkVEnv(File venv) {
        File pythonExecutable = new File(venv, PYTHON_PATH);
        if (!pythonExecutable.exists()) {
            return false;
        }

        return checkPythonVersion(pythonExecutable.getAbsolutePath());
    }

    private static void installRequirements(File directory) {
        File venv = new File(directory, VENV_NAME);
        File requirements = new File(directory, REQUIREMENTS_NAME);
        File pythonExecutable = new File(venv, PYTHON_PATH);
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable.getAbsolutePath(), "-m", "pip", "install", "-r", requirements.getAbsolutePath());
            if (DEBUG) {
                pb.inheritIO();
            } else {
                pb.redirectErrorStream(true);
            }
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0)
                throw new IllegalStateException("Failed to install dependencies " + requirements.getAbsolutePath() + ": " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException | IOException e) {
            throw new IllegalStateException("Failed to install dependencies " + requirements.getAbsolutePath(), e);
        }
    }

    private static boolean checkPythonVersion(String executable)
    {
        try {
            Process process = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0)
                return false;

            String versionStr = output.replace("Python ", "");
            Semver installed = new Semver(versionStr);

            if (installed.isLowerThan("3.10.0"))
                return false;
        } catch (InterruptedException | IOException e) {
            return false;
        }

        return true;
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursively(child);
            }
        }
        return file.delete();
    }

    private static String resolveCommand(String command) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("where", command);
            } else {
                String shell = System.getenv("SHELL");
                if (shell == null || shell.isEmpty()) {
                    shell = "/bin/sh";
                }
                pb = new ProcessBuilder(shell, "-l", "-c", "which " + command);
            }

            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();

            // 'where' on Windows can return multiple lines, take the first
            int newline = result.indexOf('\n');
            if (newline > 0) {
                result = result.substring(0, newline).trim();
            }

            if (result.isEmpty()) {
                throw new IOException("Command not found: " + command);
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve command: " + command, e);
        }
    }
}
