# Double fields

A Double puts two fields in one cell, side by side or one above the other. There are eight of them, Double 1 to Double 8, each set up on its own, so a page can carry Power and HR in one cell and Speed and Cadence in the next. They are listed with the other Barberfish fields under Extensions when you edit a data page.

Out of the box they show, side by side with icon and header:

| Double | Left | Right |
| ------ | ---- | ----- |
| 1 | Power | HR |
| 2 | Speed | Cadence |
| 3 | Avg Power | Avg Cadence |
| 4 | Lap Avg Power | Lap Avg HR |
| 5 | NP | Avg HR |
| 6 | Grade | Distance Remaining |

In the Double fields section of the Barberfish app each Double has a card, like the Data fields cards: tap one to open it, showing what it holds beside a live preview, and its settings below.

## Slots

Each half of a Double is a [HUD slot](hud.md#slots): it can hold any field a HUD column can, from 3s Power to Last Lap Avg Cadence, with the same smoothing, zone coloring, threshold and format options, kept per Double. In the Double fields section of the Barberfish app, open a Double's card and tap a value in its preview to pick its field and set its options.

The one exception is the Wind slot's Show speed, with its layout, header and speed smoothing: it needs the room of a side-by-side Double with a label, so a stacked or label-free Double draws the wind arrow and number alone and its card hides those options. They are kept, and come back when the Double is switched to horizontal with a label.

## Layout

Orientation sets how the two values share the cell:

- Horizontal puts them side by side. Each half is laid out like a field of its own, its header above the value, the way the HUD lays out its columns.
- Vertical stacks them. Each row carries its label beside the value rather than above it, so both values stay readable in a single-height cell, even on a five- or six-row page. The label sits on the left, or on the right when the field is left-aligned.

Label sets what names each value: the field's icon, its header text, both, or nothing. With no label the values take the whole cell and grow into the room the label would have used.

Values are sized to fit their half of the cell, so long ones like `1h23'45"` or an ETA shrink rather than run off the edge. Alignment follows the cell's Karoo alignment setting and the label size follows the [Data Field Design](matching-karoo.md) section, as on every Barberfish field; whether icons show is the Double's own Label setting.

## Missing sensors

As in the HUD, a value whose sensor goes missing keeps its place while the Karoo searches for it. Once the Karoo gives up the value drops out and the other takes the whole cell, drawn like a standalone field. If both are missing, both stay.
