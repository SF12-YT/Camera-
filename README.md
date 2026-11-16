# Camera

A native Android camera app built around a clean, Samsung Camera / One UI-inspired shooting experience.

The project uses the Android Camera2 API directly. It does not require root and does not depend on Samsung-only libraries or JSQZ access.

## Interface structure

The top-level navigation has three shooting workspaces:

- **Basic** — everyday photography controls and automatic shooting.
- **Pro** — manual exposure and focus controls.
- **Advanced** — deeper camera and capture-pipeline controls.

The first milestone focuses on Basic.

## Basic mode plan

- Photo, Video, Portrait and Night shooting modes.
- Flash: Auto, On and Off.
- Zoom presets based on the active camera's reported capabilities, plus pinch-to-zoom.
- Aspect ratios: 3:4, 9:16, 1:1 and Full, where supported by the camera.
- Timer: Off, 3 seconds and 10 seconds.
- Exposure compensation.
- Front/rear camera switching.
- Last-photo thumbnail and access to the gallery.

The first implementation starts with live preview and still-photo capture. Modes and controls are added incrementally; unfinished modes are labelled as such in the UI.

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main` and on pull requests.

For a local build, use Android Studio with JDK 17 and Android SDK 35, then run:

```bash
gradle assembleDebug
```

## Current implementation

Version: **0.1.0**

- Native Camera2 rear-camera preview.
- JPEG still capture saved to Pictures/Camera.
- Front/rear camera switching.
- Flash Auto/On/Off, when the selected camera has a flash.
- Timer Off/3s/10s.
- Camera-reported zoom range with selectable zoom presets.
- Basic / Pro / Advanced navigation shell.
- Video, Portrait, Night, Pro and Advanced features are planned, not yet implemented.

## Design direction

The interface takes inspiration from Samsung's dark camera UI and One UI visual language: restrained typography, rounded quick-control surfaces, a large viewfinder, and a prominent shutter button. It is an original implementation, not a copy of Samsung's proprietary application.


## Release publishing

Debug APKs are published directly to GitHub Releases; CI artifact storage is not required.
