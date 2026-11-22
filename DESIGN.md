# OpenHK50 design

OpenHK50 is an Android band companion. Today pairs the daily step total and average heart rate above the step chart. Health, Activity, and History provide more detail. The app uses Android permissions, dialogs, back navigation, and system text sizing.

- **Navigation** uses Today, Health, Activity, History, and Band. Phones use a bottom bar and wider windows use a side rail. Tapping a reading opens that metric and date in History. Band contains device actions and backup.
- **Typography** uses Android’s sans-serif family, a 44 sp screen title, 23 sp section headings, and tabular readings where values align.
- **Color** uses an off-white canvas and forest controls in light mode, and pine charcoal with light green controls in dark mode. Heart rate uses coral, activity amber, oxygen teal, stress violet, and variability green.
- **Band** puts the current connection, battery, and primary action together. Disconnected bands can be scanned or reconnected. Connected bands show sync and settings. Bluetooth and sync failures give the next action in context.
- **Data** stays blank until the band supplies readings. Daily charts show the time of each reading. Weekly charts mark available days and open their daily details. Sleep shows stage durations with the timeline. Workouts show elapsed time and saved sessions.
