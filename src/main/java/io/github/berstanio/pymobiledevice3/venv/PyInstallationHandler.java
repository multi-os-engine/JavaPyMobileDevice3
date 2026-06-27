package io.github.berstanio.pymobiledevice3.venv;

import com.badlogic.gdx.jnigen.commons.Architecture;
import com.badlogic.gdx.jnigen.commons.HostDetection;
import com.badlogic.gdx.jnigen.commons.Os;
import io.github.berstanio.pymobiledevice3.ipc.PyMobileDevice3IPC;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Properties;

public class PyInstallationHandler {

    private static final boolean DEBUG = System.getProperty("java.pymobiledevice3.debug") != null;

    private static final String HANDLER_NAME = "handler.py";
    private static final String REQUIREMENTS_NAME = "requirements.txt";
    private static final String PYTHON_DIR = "python";

    private static final String READY_MARKER = ".ready";
    private static final String READY_PYTHON = "python";
    private static final String READY_REQUIREMENTS = "requirements";

    private static final String PBS_RELEASE = "20260623";
    private static final String PBS_PYTHON_VERSION = "3.14.6";
    private static final String PBS_FLAVOR = "install_only_stripped";
    private static final String PBS_DOWNLOAD_BASE = "https://github.com/astral-sh/python-build-standalone/releases/download";

    private static final String BASE_URL = "https://raw.githubusercontent.com/multi-os-engine/IPCPyMobileDevice3/refs/tags/v" + PyMobileDevice3IPC.PROTOCOL_VERSION;
    private static final String HANDLER_DEFAULT_URL = BASE_URL + "/" + HANDLER_NAME;
    private static final String REQUIREMENTS_DEFAULT_URL = BASE_URL + "/" + REQUIREMENTS_NAME;

    public static PyInstallation install(File directory) {
        try {
            return install(directory, new URL(HANDLER_DEFAULT_URL), new URL(REQUIREMENTS_DEFAULT_URL));
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to resolve IPC URL", e);
        }
    }

    public static PyInstallation install(File directory, File ipcBaseDirectory) {
        try {
            return install(directory,
                    new File(ipcBaseDirectory, HANDLER_NAME).toURI().toURL(),
                    new File(ipcBaseDirectory, REQUIREMENTS_NAME).toURI().toURL());
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to resolve IPC URL", e);
        }
    }

    public static PyInstallation install(File directory, URL handlerFile, URL requirementsFile) {
        prepareDirectory(directory);
        refresh(new File(directory, HANDLER_NAME), handlerFile);
        refresh(new File(directory, REQUIREMENTS_NAME), requirementsFile);
        return installInternal(directory);
    }

    private static void refresh(File target, URL source) {
        try (InputStream in = source.openStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Source unreachable (e.g. offline restart): reuse the previously fetched copy.
            if (!target.exists())
                throw new RuntimeException("Failed to fetch " + target.getName(), e);
        }
    }

    private static void prepareDirectory(File directory) {
        directory.mkdirs();
        if (!directory.exists() || !directory.isDirectory())
            throw new IllegalArgumentException(directory.getAbsolutePath() + " does not exist or is not a directory");
    }

    private static PyInstallation installInternal(File directory) {
        String urlHash = hash(standaloneUrl().getBytes(StandardCharsets.UTF_8));
        String reqHash = hash(readBytes(new File(directory, REQUIREMENTS_NAME)));

        Properties ready = loadReady(directory);
        boolean interpreterReady = urlHash.equals(ready.getProperty(READY_PYTHON))
                && pythonExecutable(directory).exists();

        if (!interpreterReady) {
            File pythonHome = new File(directory, PYTHON_DIR);
            if (pythonHome.exists() && !deleteRecursively(pythonHome))
                throw new IllegalStateException("Failed to remove stale interpreter at " + pythonHome.getAbsolutePath());
            downloadAndExtractInterpreter(directory);
            installRequirements(directory);

            ready.setProperty(READY_PYTHON, urlHash);
            ready.setProperty(READY_REQUIREMENTS, reqHash);
            storeReady(directory, ready);
        } else if (!reqHash.equals(ready.getProperty(READY_REQUIREMENTS))) {
            installRequirements(directory);
            ready.setProperty(READY_REQUIREMENTS, reqHash);
            storeReady(directory, ready);
        }

        return toInstallation(directory);
    }

    private static Properties loadReady(File directory) {
        Properties props = new Properties();
        File f = new File(directory, READY_MARKER);
        if (f.exists()) {
            try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                return new Properties();
            }
        }
        return props;
    }

    private static void storeReady(File directory, Properties props) {
        try (Writer w = Files.newBufferedWriter(new File(directory, READY_MARKER).toPath(), StandardCharsets.UTF_8)) {
            props.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write ready marker in " + directory.getAbsolutePath(), e);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readBytes(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + f.getAbsolutePath(), e);
        }
    }

    private static void downloadAndExtractInterpreter(File directory) {
        String url = standaloneUrl();
        File archive = new File(directory, "python-standalone.tar.gz");
        try {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download python interpreter from " + url, e);
        }

        try {
            // tar preserves the executable bits of bin/python3. The archive unpacks to a top-level
            // "python/" directory inside the target dir.
            ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.getAbsolutePath(), "-C", directory.getAbsolutePath());
            if (DEBUG) {
                pb.inheritIO();
            } else {
                pb.redirectErrorStream(true);
            }
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Failed to extract python interpreter: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to extract python interpreter " + archive.getAbsolutePath(), e);
        } finally {
            archive.delete();
        }

        if (!pythonExecutable(directory).exists())
            throw new IllegalStateException("Extracted python interpreter, but " + pythonExecutable(directory).getAbsolutePath() + " is missing");
    }

    private static void installRequirements(File directory) {
        File requirements = new File(directory, REQUIREMENTS_NAME);
        File pythonExecutable = pythonExecutable(directory);
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

    private static PyInstallation toInstallation(File directory) {
        return new PyInstallation(new File(directory, PYTHON_DIR), pythonExecutable(directory), new File(directory, HANDLER_NAME));
    }

    private static File pythonExecutable(File directory) {
        File pythonHome = new File(directory, PYTHON_DIR);
        if (HostDetection.os == Os.Windows)
            return new File(pythonHome, "python.exe");
        return new File(pythonHome, "bin/python3");
    }

    private static String standaloneUrl() {
        String asset = "cpython-" + PBS_PYTHON_VERSION + "+" + PBS_RELEASE + "-" + standaloneTriple() + "-" + PBS_FLAVOR + ".tar.gz";
        return PBS_DOWNLOAD_BASE + "/" + PBS_RELEASE + "/" + asset;
    }

    private static String standaloneTriple() {
        String arch = standaloneArch();
        if (HostDetection.os == Os.MacOsX)
            return arch + "-apple-darwin";
        if (HostDetection.os == Os.Windows)
            return arch + "-pc-windows-msvc";

        return arch + "-unknown-linux-gnu";
    }

    private static String standaloneArch() {
        if (HostDetection.bitness != Architecture.Bitness._64)
            throw new IllegalStateException("python-build-standalone requires a 64-bit host, got " + HostDetection.bitness);
        switch (HostDetection.architecture) {
            case x86:
                return "x86_64";
            case ARM:
                return "aarch64";
            default:
                throw new IllegalStateException("Unsupported architecture for python-build-standalone: " + HostDetection.architecture);
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children)
                    deleteRecursively(child);
            }
        }
        return file.delete();
    }
}
