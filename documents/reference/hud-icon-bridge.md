# HUD / cluster turn icons for Play forks

## The conflict

On **gminfo3.7** (Info 3.7, AAOS 12), GM's Templates Host writes each navigation
maneuver icon to:

```
com.google.android.apps.automotive.templates.host.ClusterIconContentProvider
```

GM never registers that provider. Whoever claims it gets to supply the PNG the
instrument cluster and HUD show for turn-by-turn.

Google Play enforces content-provider authority uniqueness and locked
`zeno.carlink` (upstream) as the first publisher of that authority
([lvalen91/carlink#6](https://github.com/lvalen91/carlink/issues/6)). Any other
Play AAB that declares it is rejected ("authority in use by other developers").

This fork's Play package is `jbr1.carlink`. Its `play` flavor therefore
registers a **derived** authority (`jbr1.carlink.ClusterIconContentProvider`)
so the AAB uploads. Templates Host never calls that derived authority, so a
Play-only install shows **road name + distance with no turn glyph** on the
cluster/HUD.

Confirmed on-device by fork testers on the XDA thread and in issue #6 comments:
app-side Trip/Maneuver data is fine; `ICON_SHIM` never fires because nothing
owns the reserved authority.

## What does **not** work

| Approach | Result |
|---|---|
| Play AAB claiming the reserved GM authority | Play Console reject |
| Play AAB with app-unique authority only | Uploads; HUD icons never render |
| Sideloading the full `jbr1.carlink` APK (sideload flavor) | Icons work, but GM covers the UI when leaving Park (distraction overlay) |

There is no single-APK Play path that both uploads and delivers icons.

## Alternative that works: sideloaded icon bridge

Same idea as [enigy Carlink2](https://github.com/enigy/Carlink-legacy) / Eric Yanush
[PR #15](https://github.com/lvalen91/carlink/pull/15), scoped to icons only:

| App | Package | Install | Role |
|---|---|---|---|
| Display | `jbr1.carlink` | Google Play | UI, USB, CarPlay/AA, Car App Library nav session (sends CarIcons) |
| Icon bridge | `jbr1.carlink.hudicons` | Sideload (USB drive / adb) | Hosts the reserved `ClusterIconContentProvider` |

Templates Host resolves the provider **by authority string**, not by the nav
app's package. The bridge's exported provider receives `insert` / `query` /
`openFile` while the Play app keeps the screen while driving.

The Play app already probes the reserved authority at startup
(`NavigationStateManager.initialize`). With the bridge installed and the
`<queries>` entry in the display app's manifest, that probe succeeds via the
exported companion provider and AA/composed maneuver bitmaps stay enabled.
Stable content-URI icons (rev 156) stay off unless **this** process owns the
authority; the bitmap path is the correct one for a companion-hosted provider.

### Build

```bash
./gradlew :app:bundlePlayRelease -Pcarlink.buildRoot=./build-out
./gradlew -p iconbridge assembleRelease -Pcarlink.buildRoot=./build-out
```

`iconbridge/` is a nested standalone Gradle project (its own `settings.gradle.kts`),
not an included module of the main app — that keeps Android Studio sync of
`jbr1.carlink` free of the `:iconbridge:prepareKotlinBuildScriptModel` failure.

- Display AAB → Play Internal Testing (`jbr1.carlink`)
- Bridge APK → USB drive / `adb install` (`jbr1.carlink.hudicons`)

### Install on the head unit

1. Uninstall any other package that claims
   `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`
   (upstream sideload APK, Carlink2 bridge, etc.) — Android only allows one
   claimant (`INSTALL_FAILED_CONFLICTING_PROVIDER`).
2. Install `jbr1.carlink` from Play.
3. While parked, sideload `iconbridge-release.apk` (Files app from USB, or adb).
4. Force-stop / relaunch Carlink so the icon probe runs with the bridge present.
5. Drive a route: log should show
   `[NAVI_ICON] Cluster icon provider available via jbr1.carlink.hudicons …`.

### Platform limits (unchanged)

- **gminfo3.7 only** for app-supplied bitmaps. GM VCU (Bosch VCUNH1, AAOS 14+)
  ignores app icons and draws from `setManeuverType()` enum glyphs.
- Rich CarPlay junction geometry still benefits from custom adapter firmware;
  stock firmware narrows maneuvers. See [app-notes.md](app-notes.md).

## Full USB+icon bridge (optional future)

Carlink2 also moves USB ownership into `android.car.usb.handler` to silence
GM's permission prompts. This repo's `:iconbridge` does **not** do that — USB
stays in the Play app. If permission churn becomes the next pain point, port
the USB half of PR #15 / Carlink2 on top of this icon split.
