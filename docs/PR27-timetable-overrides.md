# PR27 timetable adjustments and local edits

Imported CSV and ICS rows remain immutable source data. DormPanel stores day adjustments and class edits separately in `schedule.db`. Re-import and WebDAV/HA source replacement keep edits when the source ID and series ID still match. An unmatched edit remains dormant; deleting a source removes its class edits. Deleting a local recurring class removes its edits. Calendar events are separate and unaffected.

Effective classes are projected in this order: source/local classes, all-weeks edit, target date adjustment, then this-week edit. **No classes** hides that date's classes. **Use another weekday** copies that weekday's series-edited template from the same Monday–Sunday academic week; the source date's own day adjustment is not followed. Tap a day heading in the full timetable to set or reset its adjustment.

Tap a class to see effective details and source information, then choose **Edit**. **This week** targets one effective class in the selected academic week and permits moving it to another date in that week. **All weeks for this timetable item** targets the stable local entry ID or imported `sourceId + seriesId`, never the title. A multi-weekday imported ICS series keeps its original weekday pattern; its other fields remain editable.

Fields left unchanged inherit the latest source/lower-precedence value. Clearing location, teacher, or user note stores an explicit empty value. Teacher and user note are local metadata when the source does not provide them; opaque CSV description is displayed only as source information. **Reset this week** reveals the all-weeks/source value, **Reset all-weeks edit** reveals source values for other occurrences, and resetting a day to **Normal** removes that adjustment.

For a known term profile, **Linked periods** resolves selected period numbers against the phase effective on each occurrence's actual date. Moving a class or copying a makeup-day template across a phase boundary recomputes its clock time. **Custom time** stores the chosen clock range; it remains custom until explicitly switched back. Without a resolvable profile, period labels remain informational and custom clock editing is available. Missing period definitions reject a linked edit.

Schema v3 migrates from v2 and chains from v1 without dropping existing data. New CSV sources persist their term key. A narrowly identified PR25 Hubei CSV source can use the built-in 2026-2027-1 profile when its old term key is null.
