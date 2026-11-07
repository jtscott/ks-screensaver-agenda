# Now Playing visibility for plugins

Agenda 2.6.1 consumes a new `nowPlaying` boolean in `getScreensaverAppearance` and `screensaver.appearance`. Stock Kiosk Satellite through 2026.11.9 does not send it. Installing the plugin ZIP alone fixes the Date column width, but cannot hide the card over Now Playing on those builds.

The field uses KS's existing `nowPlayingShown` feed. It describes the view on screen, including a paused player or shared layout. It stays false for music playing in the background. Adding it to the appearance payload also makes the existing change detection send an event when the player opens or closes over an unchanged daytime screensaver.

## Patches

- `kiosk-satellite-now-playing.patch`: runtime field, updated existing expectations and a regression test for reads, opening/closing over Clock and photos, and duplicate events. Base: `jxlarrea/kiosk-satellite` commit `67db5492b5546adb3f63d5afa15644b496f729d3`.
- `kiosk-satellite-plugins-now-playing.patch`: documents the field. Base: `jxlarrea/kiosk-satellite-plugins` commit `698e26b3050bfeaa4654e47fc0f51d0f2c14c0e1`.

From the respective repository root, apply its patch with `git apply /path/to/the.patch`. The app patch needs a KS build and installation; the SDK Java interface does not change. Keep Agenda's minimum version at 2026.11.1 until there is a published KS release to name for this feature. Older builds keep their existing behavior when the field is absent.

Run the app regression test from its `app/` directory:

```sh
flutter test test/plugin_host_api_test.dart
```

## Validation

Both patches were checked for clean application to the pinned source files. The Java tests cover the plugin's handling of the field, late replies, preview, pending calendar reads, night hiding and dismissal. Flutter is not installed in the development environment used for this change, so the app test and KS APK build still need to run in a Flutter environment. No KS release or device installation was made.
