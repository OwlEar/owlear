# OwlEar technical guide

[← Back to the main page](../README.md)

This page is for developers and people who want to check exactly how OwlEar works. Everyday users only need the [main page](../README.md).

## What it detects, and how

- **Item trackers following you**: Apple AirTag / Find My, Samsung SmartTag, Tile, Chipolo and Google Find My Device tags.
- **Flock Safety license-plate cameras**: matched by the Bluetooth and Wi-Fi names they broadcast.
- **Axon police gear** (body cams, Tasers): matched by Axon's Bluetooth maker ID `00:25:DF`.
- **Police vehicles, including unmarked ones**: matched by the Wi-Fi of their in-car routers. Signatures come from [US Patent 12,177,766](https://patents.justia.com/patent/12177766): Cradlepoint `IBR600/900/1100/1700-xxx` and Compex `114K-634xxx` default names, plus "INMOTION" (high confidence). The router makers' IDs (Cradlepoint `00:30:44`, Compex `04:F0:21` / `00:80:48`, Utility `00:09:BC`) count as "possible" only, because the same routers sit in buses, stores and utility trucks. Possible matches are shown greyed out, never alert, and skip names containing hvac/photo/fire/cradle/wireless/fd-/ems/amb. OwlEar deliberately has no learned or location-based lockout, because the patent claims that method.
- **Drones**: anything broadcasting FAA Remote ID (ASTM F3411) over Bluetooth (service data `FFFA`, app code `0x0D`) or Wi-Fi beacons (vendor element `FA:0B:BC`, Android 11+). Shows the drone's serial when it sends one.
- **Camera glasses**: Luxottica's Bluetooth company ID `0x0D53` (Ray-Ban / Oakley Meta) is high confidence. Meta's IDs `0x01AB` / `0x058E` are "possible" only, since Quest headsets use them too.
- **Hidden Wi-Fi cameras in setup mode**: factory network names like `HDWiFiCam`, `GW_AP_` (Yoosee) and `MVxxxxxx` (V380). A camera already joined to the host's Wi-Fi doesn't broadcast its own network, so this can't catch every camera.
- **Fake cell tower warning signs**: the phone dropping to 2G (shut down by US carriers) or a 4G tower reporting an invalid area code (TAC 0 or 65535). Without root, Android hides the deeper signs, so a warning means "look closer", not proof, and a good fake tower can slip past.
- **User-defined entries**: by maker ID (start of a MAC address), by name text, or by a regular expression starting with `re:`.

The app is receive-only. It never connects to, jams or transmits at anything.

## How it works

| Feature | Implementation |
|---|---|
| Hearing devices | Passive BLE scan plus the phone's Wi-Fi scan results |
| Recognising trackers | Apple company ID `0x004C` with type `0x12`; service UUIDs `FD5A` (Samsung), `FEED/FEEC` (Tile), `FE33` (Chipolo), `FEAA` frames `0x40/0x41` (Google) |
| "Following you" | Same tag, separated from its owner, with you 20+ min **and** heard at 3+ places at least 300 m apart |
| Watchlist | MAC prefix (OUI), name substring, or `re:` regex on the broadcast name |
| Radar distance | RSSI mapped to rings: very close (≥ −60 dBm) / nearby (≥ −75 dBm) / far |
| Radar direction | Body-shielded sweep: the user turns 360° with the phone against their chest while the app logs peak RSSI per 10° compass slice and picks the smoothed peak. Results go stale after 3 minutes and only work for Bluetooth. |
| Per-equipment modes | Each `Category` is Alert / Show only / Off. Off drops the advertisement in `Repo.recordDevice` before a `Sighting` is created |
| Saved places | Geofence (150 m). Mutes are per place and per category. Trackers ignore place mutes, so a tag following you home still alerts |
| "Regular here" | Fixed gear heard at a saved place on 3+ separate days gets a label only, never a mute (stays clear of the patent's auto-lockout claim). Forgotten after 30 days unheard |
| Saved data | AES-256-GCM with a non-exportable Android Keystore key; addresses stored only as HMAC-SHA256 fingerprints; file in no-backup storage; `dataExtractionRules` block cloud backup and device transfer. "Erase everything" deletes the file and destroys the keys. See [PRIVACY.md](../PRIVACY.md) |
| Cell checks | `TelephonyManager.getAllCellInfo()`: serving cell generation and LTE TAC |

## Privacy design

- No `INTERNET` permission in the manifest, so the app cannot open a network connection.
- No third-party libraries, analytics, crash reporting or accounts.
- `allowBackup="false"`.
- Sightings live in memory only and expire after a few hours.

## Limits

- Radar and laser speed guns (10–35 GHz microwave or infrared) can't be detected by any phone, because phones have no receiver for those frequencies.
- Phones can't sense direction live: one omnidirectional antenna and no angle-of-arrival API for apps.
- Flock name patterns come from public research and can change. They can be edited in the app.
- Many devices randomise their Bluetooth address, so maker-ID matches only catch gear that uses its real address.
- Android throttles Wi-Fi scans (about 4 per 2 minutes), so Wi-Fi matches lag behind Bluetooth.

## Build it (free)

1. Fork or clone this public repo.
2. The **Build APK** workflow in the **Actions** tab runs on every push to `main`. Download **owlear-apk** from the run page.
3. Pushing a tag that starts with `v` (for example `v0.1.0`) publishes the APK on the **Releases** page for everyone.

Requirements: Android 8.0 (API 26) or newer with Bluetooth LE.

## Contributing

- **Signatures:** watchlist defaults live in `app/src/main/java/org/owlear/app/Watchlist.kt`, tracker signatures in `Detection.kt`. Pull requests with sourced signatures are welcome.
- **Translations:** the user guide is translated in `docs/README.<language>.md`. Corrections from native speakers are very welcome.

## License

MIT

## Translations

The app screens come in the same 13 languages as the README. All on-screen text lives in `app/src/main/res/values/strings.xml` (English) and one `values-<lang>/strings.xml` per language (`values-iw` is Hebrew, `values-pt` is Brazilian Portuguese).

- Keep every `%1$s` / `%1$d` marker. Each one is filled in by the app; a missing or extra marker crashes that screen.
- Counts use `<plurals>` so each language gets its own forms (Polish, Ukrainian, Arabic and Hebrew have more than two).
- Arabic, Persian and Hebrew lay out right to left (`android:supportsRtl`). Strings in those files that start with a Latin word or a marker begin with `&#x200F;`, an invisible right-to-left mark. Keep it.
- Device names, addresses and place names are wrapped in Unicode direction isolates (`L10n.ltr`) so they read correctly inside right-to-left sentences.
- Built-in watchlist entries are saved under their English name and shown translated. Entries you add yourself are shown as typed.
- On Android 13+, `res/xml/locales_config.xml` lets people choose OwlEar's language separately from the phone's.
