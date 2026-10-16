# Video Inject: LSPosed module (test aid for your own app)

Plays a video of your choice in place of the camera **preview** inside one app you scope the module to,
similar to what OBS Virtual Camera does on desktop. Your app does **not** need any change.

## Build with Android Studio (PC)
1. Unzip `VideoInjectModule.zip` and in Android Studio choose File > Open, then pick the `VideoInjectModule` folder.
2. Wait for "Gradle sync" to finish (first time it downloads a lot). If asked to install Android SDK 34, accept.
   If it offers to upgrade the Android Gradle Plugin, choose "Don't remind me again" / skip.
3. Build > Build Bundle(s) / APK(s) > Build APK(s). Click "locate" in the popup.
   The file is `app/build/outputs/apk/debug/app-debug.apk`.
4. Copy `app-debug.apk` to the phone and install it.

Command line alternative (JDK 17 + Android SDK installed): `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
There is also a GitHub workflow in `.github/workflows/build.yml` that builds the same APK online.

## Use (one-time setup)
1. Install the APK. In LSPosed: Modules > Video Inject > enable, and tick **only the app you want to test** in the scope.
2. Open Video Inject (it has its own launcher icon). Card 1: **Choose app** and tap the app in the list.
   Card 2: **Choose video**. Card 4: **Apply to app** and tap **Allow** on the root (Magisk/KernelSU) popup.
3. Swipe the chosen app away from recent apps (first time only), open it once, then open its camera screen.
4. Tap **Check status** in Video Inject. It shows five lines: root, video chosen, video inside the app,
   LSPosed hook loaded, and what the camera preview did last. Green = good, amber = do the hint, red = problem.

## Fit in the circle (new)
- After you choose a video, the module looks for the face automatically (Android's built-in face finder) and turns
  the picture upright if needed. Card 3 **Open simulation** shows the video inside a round preview, exactly the way the
  chosen app draws it (same renderer). Sliders: zoom out/in, left/right, up/down; buttons: rotate 90, mirror,
  frame shape, Auto-fit face, Reset. **Save as final output** stores it; if the video was already applied it is re-applied.
- The face fills about 70 percent of the circle by default ("a little zoomed out").
- If the smart renderer cannot start in the app, the module falls back to plain video playback (Check status says so).

## Making the face fit exactly (v3)
- Simulation now has: Zoom 10%-300% (fine, with - / + buttons), Face size in circle, Left/Right and Up/Down (+-2 circle radii),
  and **Match the app** (use it when the app itself zooms the preview in).
- Auto size is limited to a sane range, so a badly measured face can no longer zoom in 4x.
- **Test pattern**: Card 3 switch. Apply, open the app camera, and read which ring touches the circle edge
  (red 100%, green 75%, blue 50%, yellow 25%). Set Match the app to that value. Magenta bar = top, cyan bar = right.

## v4 changes
- Face finder is now Google ML Kit (bundled, offline), much more reliable than Android's old one. The first build downloads it.
- Empty space around the video is filled with the video's own background colour (smoothly blended), never black.
- Auto size keeps the circle inside the picture, so no empty edges appear unless you zoom out yourself.
- Simulation: drag the picture with a finger and pinch to zoom, like a real camera app.

## v5: making the app match the simulation
- The app's round view can be bigger or smaller than the surface the camera draws into, so the app may show more zoom than the
  simulation. Fix it in two ways: **Calibrate** (card 3: switch Test pattern on, Apply, read which ring touches the circle edge, tap Calibrate and pick it),
  or turn on **live** in the simulation and move the sliders while watching the real app (settings are pushed with root and picked up within a second).
- The home screen title shows the installed version (v5).

## v6: floating control button
- After Apply, a round button appears on top of every screen of the chosen app (drag it anywhere). Tap it for arrows (hold to repeat),
  Zoom -/+, Face size -/+, Rotate, Mirror and Reset. Changes show instantly in the video and are remembered in the app.
- It lives inside the app's own window (made by the LSPosed hook), so it needs no overlay permission. It is not a Zygisk module.
- Limits were widened (zoom 2% to 1200%, move up to 6 circle-radii). Switch it off with "Floating control button" in Options.
- The panel also shows a small round preview (simulation) that follows every change.
- Pressing Apply on the module home screen overwrites the saved position with the module's own values.

## v8: sharper picture, stretch fix, button off by default
- The module asks the app for a bigger camera-preview surface (short side 1080) while it is active, so the video is not soft (`hq=0` in the settings turns this off).
- **Stretch fix** (simulation slider, and Wider / Narrower / Auto stretch in the in-app panel) corrects a picture that looks squashed or too tall.
  Tip: with the Test pattern the rings must look like perfect circles; if they look like ovals, fix the stretch until they are round.
- The floating button is OFF by default (switch it on in Options). The panel has "Hide button" to remove it.

## v10: automatic match by measuring the screen (replaces the v9 guess)
- Auto-calibration now measures instead of guessing: the preview briefly shows a colour map, the app window is copied with PixelCopy (twice,
  normal and inverted), and the changed pixels give the exact visible part, stretch, size, centre and mirroring (`AutoCal`).
  It runs 1 s after the camera starts (the picture flashes for about a second) and via "Auto-calibrate" in the in-app panel.
- Needs an app window that allows screenshots (not FLAG_SECURE). Sideways or upside-down previews are reported, not changed.
- Face guide marks: preset marks (face oval, centre line, eye line with two pupil dots, nose, mouth) in the simulation, in the in-app mini preview,
  and over the app's own circle ("Face guide marks" button in the in-app panel). Line the face up with them using the arrows.

## Later
- Same video plays again every time the app opens the camera.
- New video: Choose video > Apply to app > open the camera screen again.
- Switches (Inject / Loop) re-apply automatically. "Remove video from app" cleans up.
- Closing or force-stopping your app deletes nothing. Never use "Clear data".

## How it works
- **Apply to app** uses root to copy the video and a tiny settings file into the target app's own
  `files/` folder (`vinject.mp4`, `vinject.cfg`). Nothing inside the app's code or manifest is touched.
  The app's folder is taken from Android itself (the app picker), and root is started with `su -M`
  (global mount namespace) because on Android 11+ a root shell started from an app often cannot see
  other apps' data folders otherwise. Several folder paths are tried; if none exists the screen shows the details.
- When LSPosed loads the module into the chosen app, the hook writes `files/vinject.loaded`; **Check status**
  reads it, which proves the LSPosed scope is correct.
- `HookEntry` hooks Camera1 (`setPreviewTexture` / `setPreviewDisplay`) and Camera2/CameraX
  (`createCaptureSession*`, plus `CaptureRequest.Builder.addTarget`) inside the scoped app.
- The real preview surface is drawn by an OpenGL renderer (`GlVideoRenderer`: MediaPlayer into an OES texture, then crop/zoom/rotate/mirror) with plain `MediaPlayer` as fallback; the camera is pointed at a throw-away dummy surface.
- The hook writes a one-line `vinject.status` file in the app folder; **Check setup** reads it back.
- If the video or settings file is missing, the hook does nothing and the real camera is used.

## Limitations
- Needs a rooted phone (Magisk or KernelSU) with LSPosed. First user (profile) only.
- Preview only: photo capture, `ImageReader`/frame analysis and `Camera.PreviewCallback` still get real camera data.
- The first valid surface passed to the capture session is treated as the preview.
- Front-camera mirroring and sensor rotation are not emulated. Audio is muted.
- If the app is uninstalled or its data is cleared, tap Apply again.
- Not compiled or run on a device by the author: if the GitHub build or a feature fails, send the error text.

## v11: premium look and the "I still see v9" fix
- New dark premium screens (gradient header, numbered sections, pill buttons). The header shows `v11`; if you do not see it, you installed an old APK.
- The GitHub workflow now always builds the newest zip. Before, it could build an old unzipped project left in the repo, which is why v9 kept appearing. The build log prints `BUILDING VERSION 11.0` and the artifact is named `VideoInject-v11-APK`.

## v12: no more colour flash, no freeze when the camera closes
- The colour-map measuring now runs once per phone/app/preview size and is saved (`calkey` in the settings file). Later camera openings reuse it silently. After you tap Apply it measures once again.
- Releasing the old picture now happens in the background, so the app no longer freezes while the camera closes or restarts.
