# Three private Google Maps destinations

Version `1.0.0-rc1` adds three manual navigation tiles below the existing actions.
Their order is always slot 1, 2, 3, regardless of JSON array order. A configured tile uses
its private label, a generic illustration, and **Jedź z Google Maps**. An empty tile has
an outlined pin, dashed border and **Brak konfiguracji**. Tapping it explains how to import.
The address can be the label; the pin illustration has no personal or relationship meaning.

![Configured navigation - synthetic emulator capture](images/navigation-populated.png)

[Empty slots](images/navigation-empty.png). These images contain no actual configured destinations.

## File configuration

Copy [navigation.example.json](../navigation.example.json) to an owner-only directory outside
Git. Fill only that private copy. No source edits, APK rebuild, API key or Google account
credentials are needed. `null` or an omitted slot means empty. Import replaces all three slots.

This is a deliberately synthetic schema example, not a usable driving destination:

```json
{
  "schema_version": 1,
  "slots": [
    {
      "slot": 1,
      "destination": {
        "label": "Fixture A",
        "icon": "pin",
        "address": "Synthetic address",
        "latitude": 0,
        "longitude": 0
      }
    },
    {"slot": 2, "destination": null},
    {"slot": 3, "destination": null}
  ]
}
```

| Field | Requirement |
|---|---|
| `schema_version` | Integer `1` |
| `slots` | Array of at most three distinct integer slot IDs, 1 to 3 |
| `destination` | `null`, or an object with the fields below |
| `label` | 1 to 64 characters; displayed on the tile, at most two lines |
| `icon` | `home`, `squash` or `pin`; bundled generic art |
| `address` | Optional descriptive text, 1 to 160 characters when supplied |
| `navigate_by` | Optional `coordinates` (default) or `address`; address mode requires a full, unambiguous `address` |
| `latitude`, `longitude` | Finite JSON numbers in WGS84 degrees, within ±90 and ±180 |

The file must be valid UTF-8 and at most 16 KiB. Duplicate fields/slots, unknown fields,
unsupported icons, string-valued coordinates, control characters and trailing content are
rejected. Labels and addresses must not have leading or trailing whitespace. By default,
address text is descriptive and navigation uses the explicit coordinate pair. Since
`1.0.0-rc2`, setting `navigate_by` to `address` on one destination sends its encoded address
instead. Other destinations retain their existing behavior. Coordinates remain required for
file compatibility and reference, but are not sent in address mode. Older app versions reject
the new field; update the app before importing a file that uses it.

## Import on the radio

Choose **Ustawienia > Wczytaj miejsca z pliku**, then select the JSON using Android's
document picker. Dudu Home reads only that selected document, validates it off the UI thread
and saves an atomic private copy in `no_backup/navigation.json`. It does not retain a document
permission or watch the original file. Editing the source file requires importing again.
Cancel, invalid input or a failed write leaves the previous destinations intact. Import never
starts navigation, calls the gate, cleans, resets the daily quota or rearms media automation.

An installation may also supply `--navigation "$PRIVATE_DIR/navigation.json"` to
`scripts/configure-device.py`; see [OPERATIONS.md](OPERATIONS.md). Omitting the option preserves
the existing navigation file. Supplying three empty slots clears only navigation destinations.
The installer stages the validated object alongside the other private configuration sections;
the one-time importer separates them and removes staging. Never copy the file to the public tree.

## Launch and interaction with automation

A configured tap sends one `ACTION_VIEW` intent, explicitly addressed to the installed Google
Maps package, with `google.navigation:q=LAT,LON&mode=d`, or an encoded full address when that
destination explicitly selects address mode. This requests driving navigation from the current
position. Google Maps resolves the address; the app does not geocode the private tile label.
It follows the [Android Google Maps navigation intent contract](https://developer.android.com/guide/components/google-maps-intents#launch_turn-by-turn_navigation).
Maps controls route availability, GPS/permission prompts, first-run dialogs and actual guidance;
an accepted intent alone is not proof of active guidance. Missing Maps or a launch failure
produces a fixed message, without a fallback or automatic retry.

The tile label is local to Dudu Home. Google Maps names the coordinate using its own place
data or saved labels, so a nearby business name can appear in the route summary instead of
the tile label. Address mode can select the building/address rather than a nearby business
associated with the coordinate. Google Maps still controls the displayed name and resolved
endpoint; verify both on the radio. An ambiguous address may select the first match. There is
no automatic fallback to coordinates and no guaranteed custom-name override.

Before launching, Dudu Home durably consumes pending Yanosik/Spotify startup opportunities.
Queued media work and a pending Spotify resume are cancelled; an already-playing session is
not stopped. Gate/cleaning rules and pending home work remain unchanged. The choice survives
process restart. A fresh vendor observation first establishes a baseline so that a delayed
read from the current wake cannot undo the choice. A later increasing wake counter or full
system boot can rearm media. If the radio sleeps before that baseline is observed, media may
conservatively stay suppressed through the next wake. No timer or menu return rearms it.
If the durable reservation fails, Maps is not launched and a storage error is shown.

## Privacy and verification

Actual labels, addresses, coordinates, Maps links, private screenshots and configuration stay
outside Git, test fixtures and the APK. The app never writes them to its diagnostics. The
private navigation file is not encrypted; it is protected by the Android application sandbox
and excluded from backup. Debug/root access is privileged. Android or Google Maps may include
intent coordinates in their own logs; raw device logs and screenshots must remain private.
Opening navigation necessarily sends its destination to Google Maps.

Before publication, inspect the staged tree, public images and metadata, and run:

```sh
python3 scripts/check-public-tree.py --working-tree --all-history \
  --private-config "$PRIVATE_DIR/config.json" \
  --private-roborock "$PRIVATE_DIR/roborock/routine-credentials.json" \
  --private-navigation "$PRIVATE_DIR/navigation.json"
```

The scanner checks private values, address words, escaped text and rounded coordinates. Short
labels can match ordinary prose, and compressed image bytes are not text; manually review any
ambiguous match and every image. Pattern checks do not certify pixel content. Never publish
private fixture data merely to test the privacy scanner. All navigation tests use synthetic
points near zero. Local build, lint, parser tests, emulator document handling and the full
installer are separate from real Maps guidance acceptance on DUDU7; see [VERIFICATION.md](VERIFICATION.md).
