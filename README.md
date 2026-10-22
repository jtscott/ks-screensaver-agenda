# Screensaver Agenda

> [!NOTE]
> This plugin was created with assistance from AI (Claude Code). Every change is tested and reviewed. See [Tested devices](#tested-devices) for the hardware it has run on.

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that shows upcoming events from one or more Home Assistant calendars in a corner or along an edge of the screensaver, over the Immich slideshow or any other mode.

## Installation

### Community Plugins (Recommended)

Screensaver Agenda is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=jtscott/ks-screensaver-agenda)

Or search for `Screensaver Agenda` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/jtscott/ks-screensaver-agenda` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Then open the plugin's settings and add your calendars under **Calendars**.

## Requirements

**Kiosk Satellite 2026.10.25 or later.** Native overlays arrived in 2026.10.13, list settings and `visibleWhen` in 2026.10.23, and plugin calls to Home Assistant in 2026.10.25. KS 2026.10.32 added a built-in **Up next** screensaver widget that covers the rest of today. This plugin adds the coming days.

## Tested devices

| Model | Codename | Android | Supported firmware fingerprint | Kernel | Status |
| --- | --- | --- | --- | --- | --- |
| Meta Portal 10-inch (2nd generation) | omni | 10 | `Facebook/omni_prod/omni:10/QKQ1.210213.001/3051355900018050:user/prod-keys` | 4.9.227-perf+ | Tested |

Running it on something else? Open an issue or a pull request with your device's row. With ADB connected, this prints the codename, Android version, firmware fingerprint and kernel:

```sh
adb shell 'getprop ro.product.device; getprop ro.build.version.release; getprop ro.build.fingerprint; uname -r'
```

## How it works

- **The plugin reads the calendars itself.** It runs `calendar.get_events` for each calendar in **Calendars**, one call per calendar, through Kiosk Satellite's own Home Assistant connection (the `ha` capability). It needs no token and no helper sensor. It reads at start, when the calendar list or **Days ahead** changes, and when the card appears with the screensaver or **Show preview**. While the screensaver or preview shows, it also reads every 10 minutes on a Home Assistant `time_pattern` trigger, since plugins have no timer of their own. It unsubscribes once both have ended, so an idle kiosk makes no calls beyond the reads at start and on settings changes.
- **One calendar's limits stay its own.** KS caps each answer at 32 KB. A calendar whose events (descriptions included) go over shows an error in the plugin's status naming that calendar, and the others still show.
- **Auto-refresh needs an admin Home Assistant user.** Home Assistant only lets admin users subscribe to triggers. If Kiosk Satellite signs in as a non-admin user, the plugin's status says "Auto-refresh stopped", and the card updates only when the screensaver or preview starts or a calendar setting changes.
- **It shows one native overlay** while the screensaver runs and hides it when the screensaver ends.
- **The card is visual only.** It is drawn with `onTop(true)` and `touchable(false)`, so it sits above the screensaver and a tap still wakes the kiosk. Back does not close it.
- **It stays current on its own.** The card redraws on each minute, so countdowns and finished events stay right between reads.

The layout follows [Calendar Card Pro](https://github.com/alexpfau/calendar-card-pro) (see [Credits](#credits)). Each day gets a date block (Wed / 21 / OCT). Each event gets an accent bar in its calendar's color and shows:
- its calendar's icon, if set
- its title
- its time with a clock icon: "6:30 PM - 8:00 PM" or "All day", then its calendar's name, if set
- how far off it is, on the right: now, in 5 hours, tomorrow, in 2 days

All-day events come first. An overnight or multi-day event appears once, under today. Every one of these parts can be switched off under **Display**.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Calendars | None | Up to 6 calendars. Pick each **Calendar** entity, such as `calendar.family`. Each has a **Name** (after the time, e.g. "6:30 PM - 8:00 PM · Alice"), an **Icon** (before the title) and a **Color** for its accent bar, name and icon. An empty name or icon shows nothing |
| Title | Family | Empty for none |
| Days ahead | 3 | Today included |
| Maximum events | 5 | |
| Show location | Off | On its own line under the time |
| Date column | On | Off shows a Today / Tomorrow / date heading above each day instead |
| Date alignment | Center | Left, Center or Right. Shown while Date column is on |
| Accent bar | On | One per event, in its calendar's color |
| Show end time | On | |
| Clock icon | On | Also the location's map pin |
| Relative time | On | now, in 5 hours, tomorrow, in 2 days |
| Title lines | 2 | 1 to 3 |
| Text sizes | 12 / 15 / 34 / 13 / 13 / 17 / 14 / 14 sp | Title, weekday, day number, month, day heading, event title, time and location, relative time. Each shows only while its text does. Size scales them all |
| Clock format | 12-hour | |
| Position | Top left | Where this card sits: a corner, the middle of an edge, or the center. It is not a Kiosk Satellite widget, so **Settings > Screensaver > Widgets** does not place it. Pick a spot those widgets leave empty. |
| Size | 100% | |
| Background opacity | 55% | 0 draws text only |

**Show preview** and **Hide preview** (plugin actions) display the card without the screensaver, for placing it.

## Data and privacy

The plugin talks only to your Home Assistant, through Kiosk Satellite's existing connection and account. It sends `calendar.get_events` for the calendars you list and, while the screensaver or preview shows, one `time_pattern` trigger subscription for the 10-minute refresh. It makes no network connections of its own, stores nothing outside Kiosk Satellite's plugin settings, and sends no data anywhere else.

## Credits

- [Calendar Card Pro](https://github.com/alexpfau/calendar-card-pro) by Alex Pfau inspired the card's layout: the date column, the accent bars and the countdowns. No code is shared; this plugin draws natively on Android.
- [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) by Javier Larrea, whose [plugin SDK and tools](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0) are in `sdk/` and `tools/`.

## Development

Needs JDK 17+, Python 3 and an Android SDK with platform 35 and build-tools.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

For testing a local build, install `dist/screensaver-agenda-<version>.zip` with **Plugin Manager > Developer Tools > Install from ZIP**, on the kiosk or in the remote admin. Then enable it.

Releases are built by GitHub Actions: publishing a GitHub release tagged `vX.Y.Z` runs `.github/workflows/build.yml`, which tests, builds and attaches the manifest, ZIP and checksum. The tag sets the version.

`sdk/` and `tools/` come from the official [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) repository (Apache-2.0), last refreshed 2026-10-21. To refresh again, copy its `sdk/` and `tools/` over these and set the test list in `tools/test.py` back to `agenda.ks.AgendaTest`.
