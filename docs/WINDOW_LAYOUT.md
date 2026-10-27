# App-owned windows and content sizing

Local code 31 (`1.4.0-rc4-local-layout`) is a presentation candidate based on frozen code 30.
It changes no automation timing, detector, command, cancellation token or safety reservation.

The reported narrow media panel came from a 350 dp floating window with a horizontal icon,
text column and cancellation button competing for that width. Padding and side controls left
less than 190 dp for some messages. Longer Polish text consequently wrapped into a tall stack.
The inline variant also reserved a separate 76 dp full-width cancellation row.

| Surface | Source | Sizing and behavior |
| --- | --- | --- |
| Detection, queued/running media, cancellation and result overlay | `ProgressOverlay`, `ProgressPanel`, `automation_progress_floating.xml` | Up to 560 dp wide inside system-bar/cutout-safe bounds; actual measured height; 20/16 sp text; cancellation beside text when readable width permits, below otherwise. Only overflowing message content scrolls; cancel stays visible and at least 48 dp. |
| Inline progress in focused menu | `automation_progress.xml` | Up to 720 dp and no wider than its actual host; natural height, same responsive arrangement, existing 22/18 sp text and 76 dp cancel target. |
| Passive street overlay | `TripPresentation`, `TripLocationCard` | Selected card remains at most 286 dp, 25 sp street and 16 sp locality. Existing full-name overflow, X, 350 ms horizontal drag and saved preferences remain. Shared placement now supplies measured bounded native window height. |
| Navigation group chooser | `NavigationPanel` | At most 840 dp, or available width minus 32 dp. Less decorative padding/art and 88 dp minimum rows; destination labels/addresses wrap inside a measured scroll area. Full group title can scroll beside a persistent 48 dp X. |
| Settings list | `MainActivity`, `ContentDialogs` | Native list alert up to 600 dp. Platform list scrolling, selection and dismissal remain. |
| Map attribution, location configuration status, media-access explanation/unavailable, empty navigation guidance | `MainActivity`/`NavigationPanel`, `ContentDialogs` | Native message alerts up to 520 dp, capped by actual available width minus 32 dp; native scrolling and buttons remain. |
| Manual trip Reset confirmation | `TripActivity`, `ContentDialogs` | Same bounded native message alert. Reset/Cancel callbacks are unchanged. |
| Menu, number/Roborock setup, action progress/success/error/cooldown | `activity_main.xml` | Full-screen, vertically scrollable content. Below 600 dp available height, smaller action art/indicator and gaps keep ordinary errors and controls together. Text/buttons retain their sizes; oversized content still scrolls. |
| Full passive trip screen | `activity_trip.xml` | Existing full-screen ScrollView and naturally wrapping address. No data collection or action lease change. |
| Automation/trip notifications, Toast messages, Android permission/settings/document picker | Android/vendor UI | The app supplies content/actions or launches standard system UI. Their container dimensions are owned by Android/DUDU; this change cannot resize Dudu Auto's own windows. |

There are no app-owned PopupWindow instances. All seven native AlertDialog creation sites use
the bounded width helper. Window resizing rechecks native alert reading width; overlay position
and chooser list height remeasure content against available bounds.

## Synthetic rendering

Build with the installed JDK 17 / Android SDK and then use an explicitly selected emulator:

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
python3 scripts/capture-window-layout.py emulator-SERIAL
```

The capture helper refuses physical devices, clears only the chosen emulator's Dudu Home data,
never grants GPS permission and installs only the local app/test APKs. The runner uses invented
configuration and renderer states; it does not invoke an action executor or media/Binder/HTTP
transport. It verifies no dial reservation and no action lease. Cancel and X retain distinct roles.
Captures and measurements stay under ignored `build/window-layouts`, including an APK digest.
The five profiles are:

| Profile | Pixels | Density | Font scale |
| --- | --- | --- | --- |
| radio | 2000 × 1200 | 240 dpi | 1.0 |
| radio-large-font | 2000 × 1200 | 240 dpi | 1.6 |
| landscape | 1280 × 720 | 240 dpi | 1.0 |
| compact | 800 × 480 | 160 dpi | 1.0 |
| compact-large-font | 800 × 480 | 160 dpi | 1.6 |

All five final coordinator-run profiles passed geometry and control checks, producing 105
synthetic captures (21 per profile) from the final local APK. The visible empty first-run capture
clears FLAG_SECURE only in its synthetic fixture. Compact action/error art and spacing are
reduced; ordinary error controls must be visible immediately at normal font, and reachable
by scrolling at enlarged font. The first error viewport and scrolled bottom are both captured.

The initial enlarged-font line-width check counted trailing break whitespace as visible glyphs;
the final harness checks Android's visible line extent (`Layout.getLineMax`) while retaining
complete text/no-ellipsis assertions and detailed width diagnostics. Coordinator visual review
is distinct from automated geometry checks.

Coordinator-run native drag, process restart/restore and resize checks passed, including
2000 × 1200 to 800 × 480. Full SafetyChecks also passes on the final APK at radio-size
normal font with denied GPS, including actual progress/cancellation/350 ms gesture windows
and synthetic transport safety tests. No real robot, media playback or DUDU IPC is exercised.
Physical radio display, touch/drag, vendor overlay policy and real journey/wake acceptance
remain separate and pending. Synthetic renderer success is not execution success.
