package io.github.berstanio.pymobiledevice3.ipc;

import io.github.berstanio.pymobiledevice3.daemon.DaemonHandler;
import io.github.berstanio.pymobiledevice3.data.DebugServerConnection;
import io.github.berstanio.pymobiledevice3.data.DeviceInfo;
import io.github.berstanio.pymobiledevice3.data.InstallMode;
import io.github.berstanio.pymobiledevice3.data.PyMobileDevice3Error;
import io.github.berstanio.pymobiledevice3.data.USBMuxForwarder;
import io.github.berstanio.pymobiledevice3.venv.PyInstallation;
import io.github.berstanio.pymobiledevice3.venv.PyInstallationHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PyMobileDevice3IPC implements Closeable {

    private static final boolean DEBUG = System.getProperty("java.pymobiledevice3.debug") != null;

    public static final int PROTOCOL_VERSION = 3;

    private final int daemonProtocolVersion;
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    private final AtomicInteger commandId = new AtomicInteger(0);
    private final Thread writeThread;
    private final Thread readThread;
    private final ArrayBlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(16);
    private final ConcurrentHashMap<Integer, Consumer<JSONObject>> commandResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<?>> futures = new ConcurrentHashMap<>();

    private boolean destroyed = false;

    public PyMobileDevice3IPC() throws IOException {
        Socket daemonSocket = DaemonHandler.getDaemonSocket();
        if (daemonSocket == null)
            throw new IllegalStateException("Daemon is not running");

        socket = daemonSocket;

        socket.getOutputStream().write(PROTOCOL_VERSION);
        socket.getOutputStream().flush();

        daemonProtocolVersion = readVersion();

        if (daemonProtocolVersion != PROTOCOL_VERSION)
            throw new IllegalStateException("Protocol version is not supported");

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);

        writeThread = new Thread(() -> {
            while (true) {
                try {
                    String toWrite = writeQueue.take();
                    writer.println(toWrite);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "PyMobileDevice3IPC-WriteDispatcher");
        writeThread.setDaemon(true);
        writeThread.start();

        readThread = new Thread(() -> {
            while (true) {
                try {
                    String line = reader.readLine();
                    if (line == null)
                        throw new IOException("End of stream");
                    JSONObject jsonObject = new JSONObject(line);
                    if (jsonObject.get("state").equals("failed")) {
                        String request = jsonObject.getString("request");
                        CompletableFuture<?> future = futures.remove(request);
                        future.completeExceptionally(new PyMobileDevice3Error(jsonObject.getString("error"), jsonObject.getString("backtrace")));
                        continue;
                    }
                    int id = jsonObject.getInt("id");
                    Consumer<JSONObject> handler = commandResults.get(id);
                    if (handler == null) {
                        System.out.println("Unable to handle: " + line);
                        continue;
                    }

                    handler.accept(jsonObject);
                } catch (IOException e) {
                    closePythonProcessDied();
                    break;
                }
            }
        }, "PyMobileDevice3IPC-ReadDispatcher");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void closePythonProcessDied()
    {
        for (CompletableFuture<?> future : futures.values())
            future.completeExceptionally(new PyMobileDevice3Error("Python process died"));

        close();
    }

    private int readVersion() throws IOException {
        try {
            socket.setSoTimeout(2000);
            return socket.getInputStream().read();
        } finally {
            socket.setSoTimeout(0);
        }
    }

    private <U> CompletableFuture<U> createRequest(JSONObject request, BiConsumer<CompletableFuture<U>, JSONObject> handler) {
        if (destroyed)
            return CompletableFuture.failedFuture(new PyMobileDevice3Error("Python process has been destroyed"));
        int id = commandId.getAndIncrement();
        if (request.has("id"))
            throw new IllegalArgumentException("Request must not contain id field");
        request.put("id", id);

        String message = request.toString();
        if (!writeQueue.offer(message))
            return CompletableFuture.failedFuture(new RuntimeException("Write queue overflow"));

        CompletableFuture<U> future = new CompletableFuture<>();

        commandResults.put(id, jsonObject -> {
            handler.accept(future, jsonObject);
            if (future.isDone()) {
                commandResults.remove(id);
                futures.remove(message);
            }
        });

        futures.put(message, future);

        return future;
    }

    /**
     * @return A list of currently connected devices
     */
    public CompletableFuture<DeviceInfo[]> listDevices() {
        JSONObject object = new JSONObject();
        object.put("command", "list_devices");

        return createRequest(object, (future, jsonObject) -> {
            JSONArray jsonArray = jsonObject.getJSONArray("result");
            DeviceInfo[] deviceInfos = new DeviceInfo[jsonArray.length()];
            for (int i = 0; i < deviceInfos.length; i++) {
                JSONObject deviceInfo = jsonArray.getJSONObject(i);

                deviceInfos[i] = DeviceInfo.fromJson(deviceInfo);
            }

            future.complete(deviceInfos);
        });
    }

    /**
     * @return A list of UDID of currently connected devices. Does not involve pairing.
     */
    public CompletableFuture<String[]> listDevicesUDID() {
        JSONObject object = new JSONObject();
        object.put("command", "list_devices_udid");

        return createRequest(object, (future, jsonObject) -> {
            JSONArray jsonArray = jsonObject.getJSONArray("result");
            String[] deviceInfos = new String[jsonArray.length()];
            for (int i = 0; i < deviceInfos.length; i++) {
                deviceInfos[i] = jsonArray.getString(i);
            }

            future.complete(deviceInfos);
        });
    }

    /**
     * @return The first available device
     */
    public CompletableFuture<DeviceInfo> getDevice()
    {
        return getDevice(null);
    }

    /**
     * @param uuid The uuid of the requested device - or null for the first available device
     * @return A future that will provide the result - null if the device is not connected
     */
    public CompletableFuture<DeviceInfo> getDevice(String uuid) {
        JSONObject object = new JSONObject();
        object.put("command", "get_device");
        if (uuid != null)
            object.put("device_id", uuid);

        return createRequest(object, (future, jsonObject) -> {
            if (jsonObject.get("state").equals("failed_no_device")) {
                future.complete(null);
            } else {
                DeviceInfo deviceInfo = DeviceInfo.fromJson(jsonObject.getJSONObject("result"));
                future.complete(deviceInfo);
            }
        });
    }

    /**
     * Gets the bundle identifier for an app path
     *
     * @param appPath The path to the app bundle, to retrieve the identifier from
     * @return The bundle identifier. Fails exceptionally, if no bundle identifier can be found
     */
    public CompletableFuture<String> getBundleIdentifier(File appPath)
    {
        JSONObject object = new JSONObject();
        object.put("command", "get_bundle_identifier");
        object.put("app_path", appPath.getAbsolutePath());

        return createRequest(object, (future, jsonObject) -> {
            future.complete(jsonObject.getString("result"));
        });
    }

    /**
     * Gets the installed path of an app on a specific device
     *
     * @param info The device to query
     * @param bundleIdentifier The bundle identifier to search for
     * @return The on-device path or null, if the bundle is not installed
     */
    public CompletableFuture<String> getInstalledPath(DeviceInfo info, String bundleIdentifier)
    {
        JSONObject object = new JSONObject();
        object.put("command", "get_installed_path");
        object.put("device_id", info.getUniqueDeviceId());
        object.put("bundle_identifier", bundleIdentifier);

        return createRequest(object, (future, jsonObject) -> {
            if (jsonObject.get("state").equals("failed_not_installed")) {
                future.complete(null);
            } else {
                future.complete(jsonObject.getString("result"));
            }
        });
    }

    /**
     * Installs an app onto the device
     *
     * @param deviceInfo The device to install to.
     * @param path .app bundle path
     * @param installMode The installation mode. INSTALL/UPGRADE
     * @param progressCallback A callback that will be run during installation. No thread guarantees made. May be null
     */
    public CompletableFuture<String> installApp(DeviceInfo deviceInfo, File path, InstallMode installMode, IntConsumer progressCallback) {
        if (installMode == InstallMode.NONE)
            throw new IllegalArgumentException("InstallMode cannot be NONE");
        if (!path.exists() || !path.isDirectory())
            throw new IllegalArgumentException("Path " + path.getAbsolutePath() + " does not point to directory");

        JSONObject object = new JSONObject();
        object.put("command", "install_app");
        object.put("install_mode", installMode.name());
        object.put("app_path", path.getAbsolutePath());
        object.put("device_id", deviceInfo.getUniqueDeviceId());

        return createRequest(object, (future, jsonObject) -> {
            if (jsonObject.has("progress")) {
                if (progressCallback != null)
                    progressCallback.accept(jsonObject.getInt("progress"));
            } else {
                String bundlePath = jsonObject.getString("result");
                future.complete(bundlePath);
            }
        });
    }

    /**
     * Decodes a plist into an JSONObject
     * @param path The path to a plist
     * @return The decoded plist
     */
    public CompletableFuture<JSONObject> decodePList(File path) {
        if (!path.exists() || path.isDirectory())
            throw new IllegalArgumentException("Path " + path.getAbsolutePath() + " does not point to file");
        JSONObject object = new JSONObject();
        object.put("command", "decode_plist");
        object.put("plist_path", path.getAbsolutePath());
        return createRequest(object, (future, jsonObject) -> {
            future.complete(jsonObject.getJSONObject("result"));
        });
    }

    /**
     * Auto mount developer disk image for the device
     *
     * @param info The device to mount the developer disk image
     */
    public CompletableFuture<Void> autoMountImage(DeviceInfo info) {
        JSONObject object = new JSONObject();
        object.put("command", "auto_mount_image");
        object.put("device_id", info.getUniqueDeviceId());
        return createRequest(object, (future, jsonObject) -> {
            future.complete(null);
        });
    }

    /**
     * Connect to the debugserver on the device. Needs tunneld running.
     *
     * @param info The device to connect to
     * @param port The local port to open. 0 for arbitrary port
     * @return The info about the connection
     */
    public CompletableFuture<DebugServerConnection> debugServerConnect(DeviceInfo info, int port) {
        JSONObject object = new JSONObject();
        object.put("command", "debugserver_connect");
        object.put("device_id", info.getUniqueDeviceId());
        object.put("port", port);
        return createRequest(object, (future, jsonObject) -> {
            if (jsonObject.getString("state").equals("failed_no_tunneld")) {
                future.completeExceptionally(new PyMobileDevice3Error("No tunneld instance for device " + info.getUniqueDeviceId() + " found."));
                return;
            }
            JSONObject result = jsonObject.getJSONObject("result");
            future.complete(new DebugServerConnection(info, result.getString("host"), result.getInt("port")));
        });
    }

    /**
     * Closes a debug server connection.
     *
     * @param connection The connection to close
     */
    public CompletableFuture<Void> debugServerClose(DebugServerConnection connection) {
        JSONObject object = new JSONObject();
        object.put("command", "debugserver_close");
        object.put("port", connection.getPort());
        return createRequest(object, (future, jsonObject) -> {
            future.complete(null);
        });
    }

    /**
     * Opens a port forwarding to the target device.
     *
     * @param info The device to forward to
     * @param remotePort The port to open on the device
     * @param localPort The local port to forward. 0 for arbitrary port
     * @return The information about the created forwarding
     */
    public CompletableFuture<USBMuxForwarder> usbMuxForwarderCreate(DeviceInfo info, int remotePort, int localPort) {
        JSONObject object = new JSONObject();
        object.put("command", "usbmux_forwarder_open");
        object.put("device_id", info.getUniqueDeviceId());
        object.put("remote_port", remotePort);
        object.put("local_port", localPort);

        return createRequest(object, (future, jsonObject) -> {
            JSONObject result = jsonObject.getJSONObject("result");
            future.complete(new USBMuxForwarder(info, result.getInt("remote_port"), result.getInt("local_port"), result.getString("host")));
        });
    }

    /**
     * Closes a port forwarder.
     *
     * @param connection The connection to close
     */
    public CompletableFuture<Void> usbMuxForwarderClose(USBMuxForwarder connection) {
        JSONObject object = new JSONObject();
        object.put("command", "usbmux_forwarder_close");
        object.put("local_port", connection.getLocalPort());
        return createRequest(object, (future, jsonObject) -> {
            future.complete(null);
        });
    }

    /**
     * Stops the daemon. Don't use this, it's for debugging purposes.
     */
    public CompletableFuture<Void> forceKillDaemon() {
        JSONObject object = new JSONObject();
        object.put("id", commandId.getAndIncrement());
        object.put("command", "exit");
        if (!writeQueue.offer(object.toString()))
            return CompletableFuture.failedFuture(new PyMobileDevice3Error("Write queue overflow"));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Checks whether the tunneld service is running, needed for debugserver connection
     *
     * @return whether the tunneld service is running
     */
    public CompletableFuture<Boolean> isTunneldRunning() {
        JSONObject object = new JSONObject();
        object.put("command", "is_tunneld_running");
        return createRequest(object, (future, jsonObject) -> {
            future.complete(jsonObject.getBoolean("result"));
        });
    }

    /**
     * Launches the tunneld service. Will request elevated priviliges on macos.
     */
    public CompletableFuture<Void> ensureTunneldRunning() {
        JSONObject object = new JSONObject();
        object.put("command", "ensure_tunneld_running");
        return createRequest(object, (future, jsonObject) -> {
            future.complete(null);
        });
    }

    public int getDaemonProtocolVersion() {
        return daemonProtocolVersion;
    }

    public static void main(String[] args) throws IOException {
        if (!DaemonHandler.isDaemonRunning()) {
            PyInstallation installation = PyInstallationHandler.install(new File("build/pyenv/"));
            DaemonHandler.startDaemon(installation);
        }
        try (PyMobileDevice3IPC ipc = new PyMobileDevice3IPC()) {
            ipc.ensureTunneldRunning().join();

            DeviceInfo info = ipc.getDevice(null).join();
            DebugServerConnection connection = ipc.debugServerConnect(info, 0).join();

            ipc.debugServerClose(connection).join();

            ipc.forceKillDaemon().join();
        }
    }

    public boolean isAlive() {
        return !destroyed;
    }

    @Override
    public void close() {
        if (destroyed)
            return;

        tryClose(writeThread::interrupt);
        tryClose(writeThread::join);
        tryClose(socket);
        tryClose(commandResults::clear);
        tryClose(writeQueue::clear);
        tryClose(readThread::interrupt);
        tryClose(readThread::join);
        tryClose(() -> futures.values().forEach(completableFuture -> tryClose(() -> completableFuture.cancel(true))));
        tryClose(futures::clear);

        destroyed = true;
    }

    private void tryClose(AutoCloseable operation) {
        try {
            operation.close();
        } catch (Exception ignored) {}
    }
}