# Custom build

This branch (`custom`) is Barberfish 4.0 with the developer's unreleased wind work
(`worktree-wind-sock`) merged in, plus additions of its own. It is released as **Barberfish+**
under its own package, `io.github.aryeh95.barberfish`.

## Additions

- **Show speed** and a Headwind layout on the Wind HUD slot (below).
- **Double 1 to 8** ([double-fields.md](double-fields.md)): two HUD slots in one cell. A half
  whose sensor the Karoo has given up on, or that shows "No route" while no route is loaded,
  drops out and the other value takes the cell at native single-cell sizing; both stay when both
  would drop.
- **Climb-triggered slot auto-swap**: every HUD column and Double half has an "On climbs" setting
  that swaps it to another field when on a Karoo Climber segment or when smoothed road grade
  reaches 4.0% (releasing below 2.5%).
- **Custom elevation profile tap-cycle distance scales**: in the HUD elevation profile (and
  standalone Profile card), enter any comma-separated list of distances in your profile's unit
  (`mi` or `km`, e.g. `1, 3, 5, 10` or `1, 2, 5, 8`) to customize both the Lookahead selector and
  the distances cycled when tapping the profile mid-ride.
- **W/kg, VAM, Gears, and Combined Tires**:
  - **W/kg** (`wkg`): live, 3s, 5s, 10s, 30s, Avg, NP, Lap, or Last Lap watts divided by rider
    weight, with power-zone coloring.
  - **VAM** (`vam`): Instant, 30s, Lap, or Ride vertical ascent speed (`m/h` or `ft/h`), colored by
    road gradient.
  - **Gears** (`gears`): teeth (`50-10`), gear numbers (`2-12`), or ratio (`5.00`), colored red on
    the smallest cog, blue on the largest cog, and amber when cross-chained.
  - **Tires** (`tires`): combined Front & Rear Outrider pressures side by side or stacked in a
    single cell or slot, each value colored independently against its own target.
- **Tire Alert Overlay**, under Tire alerts: watches Outrider front and rear streams in the
  background and shows an alert banner over ride or map pages on rapid pressure loss (PSI/min) or
  critical red-band low pressure.
- **Speed slot threshold coloring**: Speed slots in the HUD and Doubles support the same Fixed /
  Avg total / Avg moving threshold coloring as the standalone Speed field.
- **Backup & restore**, under Backup & restore: exports or imports all Barberfish+ preferences as a
  portable JSON file via the Android document picker.
- **Elevation, Ascent and Descent** slots under Climbing, from the Karoo's current elevation and
  the ride's ascent and descent totals.
- **Cadence averages**: Avg, Lap Avg, Last Lap Avg and Max Cadence, as fields and slots, with
  Cadence's threshold coloring.
- **Ride Remaining**: side by side or stacked, the descent left as an optional third value, and
  a Header switch.
- **Ride totals** (Distance, Ascent, Descent) read 0 before the ride and keep their last value
  while their stream is idle, instead of "No data".
- **Tire pressure** slots, Front, Rear, and Front & Rear, for Outrider TL sensors. They read the
  streams the Outrider extension publishes for its paired sensors (`Outrider/outrider-front` and
  `outrider-rear`, the gauge pressure in the unit set in the Outrider app), so the Outrider
  extension must be installed with the sensors paired; set the slot's Unit to match it (switching
  units automatically converts any configured target). A target colors the value green within one
  step, then yellow, orange and red each step further, above or below.
- **Shift overlay**, under Shifting: when a shift lands on the smallest or largest cog, into a
  cross-chained combination (`Ends + Cross`), or on every shift, a panel after
  [Ki2](https://github.com/valterc/ki2)'s overlay shows the chainrings and cassette as bars, the
  gears in teeth and the ratio, plus drivetrain low/critical battery badges, over the ride screen
  for 2 to 5 seconds.
- Header labels in narrow HUD and Double slots shrink to keep a long word whole, and a value
  drawn without its header never outgrows its cell.

The [changelog](../CHANGELOG.md) lists them per release.

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

The file lists below cover the wind work. The other additions are in the commits on `custom`
after the upstream merge; `git log upstream/master..custom` lists them.

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
