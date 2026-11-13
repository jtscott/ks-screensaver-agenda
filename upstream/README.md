# Screensaver appearance for plugin cards

Two cases where Screensaver Agenda and Live Activities draw their cards where they should not, because `getScreensaverAppearance` and `screensaver.appearance` do not tell plugins enough.

- **A bare Black screensaver.** **Hide all extras** keeps the Black screensaver fully black: KS hides its small clock, widgets and At a Glance row. Plugin overlays are not covered by that switch, and the appearance only reports the view as `black`, so the cards still show there. Hiding on every `black` view would be wrong: Black without **Hide all extras** still shows KS's widgets, and the cards belong beside them.
- **An alarm on the screensaver.** An alarm showing on the Clock or Weather Mood screensaver draws its label and its Snooze and Stop buttons inside the face (`alarmTakeover`), under plugin overlays drawn over the screensaver. A card can cover the buttons, and a touchable one can take their taps. Nothing in the appearance says an alarm is showing.

The patches make `widgetsHidden` also true while a Black screensaver with **Hide all extras** is in sight (not under a player that fills the screen), add `alarm` (true while an alarm shows on the screensaver), and send the event when either changes. Both plugins already hide on `widgetsHidden` and on `alarm`, and wait for KS's answer when the cards would come back, so they need no change. KS releases without the patch report false on Black and leave `alarm` out, and the cards keep showing there.

## Patches

- `kiosk-satellite-appearance.patch`: both fields, the feed's alarm listenable from `ScreensaverManager.alarmTakeover`, the setting in the event trigger, changelog entries and tests (Black with and without the switch and under a full or shared player, the event when the switch changes, and the alarm's read and events, with the existing full-payload expectations gaining `alarm`). Base: `jxlarrea/kiosk-satellite` commit `ed51998f689cd77bbfca0ffa660bb141abdb0759`.
- `kiosk-satellite-plugins-appearance.patch`: documents both in the KS API reference and the changelog. Base: `jxlarrea/kiosk-satellite-plugins` commit `6951268b5d7e916cec5a60ecbd1e0e626bfeacd3`.

From the respective repository root, apply its patch with `git apply /path/to/the.patch`. The SDK Java interface does not change.

Run the app tests from its `app/` directory:

```sh
flutter test test/plugin_host_api_test.dart test/plugin_now_playing_appearance_test.dart
```

## Validation

Both patches apply cleanly to their base commits. Flutter is not installed in the environment used for this change, so the app tests, `flutter analyze` and a KS build still need to run in a Flutter environment. No KS release or device installation was made.

The Now Playing patches that used to be here shipped in Kiosk Satellite 2026.11.10 as `nowPlaying` and `nowPlayingShared`.
