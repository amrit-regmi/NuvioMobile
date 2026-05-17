# Nuvio Qt6 Desktop

This is a thin Qt6 desktop shell for the existing CMP web build. It does not add another UI layer; it loads the generated web app in `QWebEngineView` through a local `nuvio://app` scheme so Wasm and browser storage work as desktop app resources.

## Build

```bash
cmake -S desktopQt -B build/desktopQt
cmake --build build/desktopQt --target NuvioQtDesktop --parallel
```

The CMake target runs:

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentWebpack
```

and copies the generated bundle into the Qt app bundle.

## Run

macOS:

```bash
./build/desktopQt/Nuvio.app/Contents/MacOS/Nuvio
```

Linux/Windows builds place a `web` folder next to the executable.

## Optional macOS deploy

```bash
cmake --build build/desktopQt --target NuvioQtDesktopDeploy --parallel
```
