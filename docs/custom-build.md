# Custom build

This branch (`custom`) is Barberfish 4.0 with the developer's unreleased wind work
(`worktree-wind-sock`) merged in, plus **Show speed** on the Wind HUD slot. It is released as
**Barberfish+** under its own package, `io.github.aryeh95.barberfish`.

## Identity and releases

- `app/build.gradle.kts`: `applicationId` is `io.github.aryeh95.barberfish`; the Kotlin namespace
  stays `com.jpweytjens.barberfish`. The Karoo's update URL (`MANIFEST_URL`) and the generated
  `manifest.json` point at this fork's releases, labelled Barberfish+.
- `strings.xml`: `extension_name` is Barberfish+.
- The extension id stays `barberfish`. Do not install the official Barberfish beside this one.
- `.github/workflows/tag-and-release.yml` releases from `custom`: bump `versionName` (no `-`) and
  `versionCode`, add a `## <version>` section to CHANGELOG.md, push. It signs with the
  `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`
  repository secrets.
- README badges and download links point at this fork.
- `scripts/render_config_shots.sh` and `scripts/render_previews.sh` use the new package.

## Show speed

Tap a HUD column, pick **Wind**, and turn on **Show speed**. The slot then stacks:

- top row: the total wind speed above the arrow column, then the ride speed with the slot's own
  speed smoothing
- bottom row: the Wind slot's arrow and headwind number (bare into the wind, minus with it)

It follows the Wind slot's rules: colour stays on the wind number, on the threshold scale
(red into a headwind, green with a tailwind); speed and arrow take the header colour. The
header reads SPEED with the speed and wind icons. When Headwind has no data the slot is a
plain Speed slot, and the column never drops while speed is valid. While the GPS fix has no
course the wind reading is held, as the Wind slot does, and speed keeps updating.

### Layout

With Show speed on, **Layout** picks how the slot draws:

- **Barberfish**: the stack above, in the developer's style.
- **Headwind**: karoo-headwind's Tailwind and ride speed look. A big arrow on the left, the ride
  speed on top and the summary line below (`+9▼14`: tail or head wind, the trend against the ride
  average, the total wind speed), all in wind colors from green with a tailwind to red into a
  headwind. **Header** off hides the header row, gives the digits its height and keeps the wind
  icon at the top of the arrow column. Without wind data the slot keeps this look with the speed
  alone.

## What differs from upstream

New files, no conflict surface:

- `app/src/main/kotlin/.../datatype/SpeedWindSlot.kt`: combining speed and wind states
- `app/src/main/kotlin/.../datatype/shared/SpeedWindBitmap.kt`: the two-row renderer
- `app/src/main/kotlin/.../screens/HUDWindCard.kt`: the Wind slot's controls
- `app/src/main/kotlin/.../datatype/HeadwindStyleSlot.kt`, `SpeedReading.kt`: the Headwind layout's
  states
- `app/src/main/kotlin/.../datatype/shared/HeadwindStyleBitmap.kt`: its renderer
- `app/src/main/kotlin/.../extension/WindLayout.kt`, `res/drawable/ic_wind_arrow.xml`
- `app/src/test/kotlin/.../SpeedWindSlotTest.kt`

Small edits to upstream files:

- `extension/Settings.kt`: `HUDSlotConfig.windShowSpeed` (default off), `windLayout`, `windShowHeader`
- `datatype/shared/FieldState.kt`: `speedRow`, `windSpeedRow`, `headwindLayout`, `hideHeader`
- `datatype/WindField.kt`: `windSpeedRow` on the live and preview states
- `datatype/BarberfishView.kt`: the first branch of the value bitmap chain, the value height and
  header hiding for header-off, and a `@Suppress` on `makeFieldRemoteViews`
- `datatype/HUDField.kt`: `.withSpeed(...)` on the Wind slot flow, `.withSpeedPreview(...)` on
  its preview
- `screens/HUDConfigSection.kt`: the Wind slot shows `HUDWindCard`

`docs/screenshots/all_fields.png` is 4.0's, which predates the Wind field, so it shows 39 of
the 40 fields. Regenerating it needs a device (`AllFieldPreviewsRenderTest`).

`windShowSpeed` is a plain key, so a build without it ignores it. A Wind slot itself is not
in official 4.0: going back to 4.0 resets a HUD that uses one.

## Updating from upstream

1. `git fetch upstream` and merge `upstream/master` (and the wind branch while it is
   unmerged) into `custom`.
2. Check that `SPEED_WIND_DIGIT_FILL` still equals `TWO_ROW_DIGIT_FILL` in `BitmapValue.kt`,
   that the Show speed branch is still first in the value bitmap chain, and that
   `renderWindArrowValueBitmap` keeps its signature, that `WindField` still sets `windSpeedRow`,
   and that the identity lines above survived the merge.
3. Run `./gradlew spotlessApply test detektDebug lintDebug assembleDebug`.
