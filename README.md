## Overview

This is a java interop library for [pymobiledevice3](https://github.com/doronz88/pymobiledevice3).  
It uses an [IPC protocol](https://github.com/multi-os-engine/IPCPyMobileDevice3), to communicate with a python3 daemon running in the background.  
All request/responses are implemented asyncronous.

## How to use

### The Daemon
- Check if the Daemon is currently running with `DaemonHandler.isDaemonRunning()`.
- If not, launch a new instance.
  - Create a python env fist `PyInstallation installation = PyInstallationHandler.install(new File(</path/to/install/dir>));`
    - You don't need to check for directory existence beforehand, the code is safe to use on every run.
    - You should version the pathes using `PyMobileDevice3IPC.PROTOCOL_VERSION`, to avoid version collisions.  
  - Then launch the daemon with `DaemonHandler.startDaemon(installation);`
- Connect to the Daemon using `PyMobileDevice3IPC ipc = new PyMobileDevice3IPC()`. This object should be closed if not used anymore.


### The IPC
You can now use the `PyMobileDevice3IPC` created. All IPC methods return a `CompletableFuture` and are non-blocking.  
The `DebugServer` methods require tunneld to be running. You can check the status with `PyMobileDevice3IPC#isTunneldRunning`.  
You can launch tunneld with `PyMobileDevice3IPC#ensureTunneldRunning`, however this will ask for elevated priviliges on macos.

## Debugging
If you run into issues, you can: 
- check the log file under `~/AppData/Local/Temp/javapymobiledevice3/` on windows and `/tmp/javapymobiledevice3` on UNIX.  
- force shutdown a daemon using `PyMobileDevice3IPC#forceKillDaemon` and clear everything in the mentioned directories above.  
- Launch a Daemon with `java.pymobiledevice3.debug` set. This will redirect the daemon stdout to your console.