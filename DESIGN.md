# OpenHK50 design

OpenHK50 is an Android band companion with Apple-inspired large titles, grouped health readings, and a clear path from Today to the band and its data. It uses Android permissions, dialogs, back navigation, and system text sizing.

- **Navigation** uses four persistent destinations. Phones use a bottom bar and wider windows use a side rail. Tapping a health reading opens that metric and date in History.
- **Typography** uses Android’s sans-serif family, a 44 sp screen title, 23 sp section headings, and tabular readings where values align.
- **Color** uses a lavender-gray canvas, grouped reading surfaces, and indigo controls. Heart rate uses rose, steps amber, oxygen teal, stress plum, and heart-rate variability green. Light and dark schemes keep the same roles.
- **Band** puts the current connection, battery, and primary action together. Disconnected bands can be scanned or reconnected. Connected bands show sync and settings. Bluetooth and sync failures give the next action in context.
- **Data** stays blank until the band supplies readings. History shows individual readings under charts, and sleep shows stage durations alongside the timeline. Status and stages have text labels.
