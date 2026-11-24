# BravePipe Morphe patches

Morphe patch for [BravePipe](https://github.com/bravepipeproject/BravePipe):

- **Video equalizer**: adds an **EQ** button to the player controls (the row with FIT / captions).
  It opens a dialog with brightness, contrast and saturation sliders that change the playing video
  live. Reset puts everything back. Values are saved and apply to the next video too.
- **Video equalizer (Kitkat)**: the same for the `braveLegacy` flavor (`com.github.bravenewpipe.kitkat`).

Not available in the popup player (the dialog needs an activity, so the button is hidden there).

> **Status: not compiled or tested.** This was written without access to the Morphe build
> toolchain or a BravePipe APK. Expect to fix small things on the first build or first run,
> see "If something does not work" below.

## Build

You need a JDK 17+ and a GitHub personal access token (Morphe's packages are served from
GitHub Packages, scope `read:packages`). Put it in `~/.gradle/gradle.properties`:

```
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_TOKEN
```

Then:

```
./gradlew buildAndroid
```

The patch bundle is `patches/build/libs/patches-*.mpp`. Open it in Morphe Desktop (or add it
as a local source in Morphe Manager), choose your BravePipe APK, tick **Video equalizer**
and patch. See the
[Morphe development guide](https://github.com/MorpheApp/morphe-documentation/blob/main/docs/morphe-development/README.md).

Building in CI is also possible: push this folder to a GitHub repo, and run the build with
`GITHUB_TOKEN` set (the template's `.github/workflows` already do this).

## How it works

| Piece | What it does |
|---|---|
| `InitPlayerFingerprint` hook | at the start of `VideoPlayerUi.initPlayer()`, tells the extension a new ExoPlayer is in use |
| `SetupVideoSurfaceFingerprint` hook | at the start of `VideoPlayerUi.setupVideoSurfaceIfNeeded()`, skips the SurfaceView setup while the equalizer is active |
| `VideoEqualizer` (extension) | creates a `TextureView` above the SurfaceView, mirrors its size, feeds ExoPlayer into it with a `ColorMatrix` filter, adds the EQ button |
| `EqualizerDialog` (extension) | the slider dialog, built in code (no resource changes) |
| `EqualizerSettings` (extension) | the saved values and the color matrix math |

Why a `TextureView`: BravePipe draws video into a `SurfaceView`, which can not take a color filter.
The TextureView is only used while at least one slider is off its default, so normal playback is
unchanged. Saved values use the same preference keys (`brave_video_equalizer_*`) as the
source-code version of the equalizer, so both behave the same.

The release APK is shrunk by R8 (`-dontobfuscate` keeps names, but unused code is removed), so the
extension finds app fields by their **type** and only calls ExoPlayer methods that the app itself
uses. All entry points are wrapped in try/catch: if something does not match, the equalizer is
skipped instead of crashing the app.

## If something does not work

- **Build fails with a fingerprint error**: the method was inlined or changed in your version.
  The error names the fingerprint (`InitPlayerFingerprint` / `SetupVideoSurfaceFingerprint`). Send
  me the message and the APK version.
- **Patch applies but no EQ button**: run `adb logcat -s BraveEqualizer`. "player layout not
  recognised" means a field type was not found (R8 changed something); "secondary controls not
  found" means the `secondaryControls` id is missing in that build.
- **Button shows but video does not change**: also check `logcat -s BraveEqualizer`, the
  ExoPlayer `setVideoSurface` call may have been stripped by R8 in that version.
- `compatibleWith` lists no exact app version (`version = null`, experimental). Add the versions
  you confirmed in `patches/.../shared/Constants.kt`.
- Before publishing, replace the placeholders in `patches/build.gradle.kts` (`about { ... }`).
