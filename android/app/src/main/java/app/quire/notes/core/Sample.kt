package app.quire.notes.core

/** The "Field Notes" sample vault from sampleVault() in the prototype. */
object Sample {
    const val NAME = "Field Notes"
    val folders = listOf("Docs", "Drafts", "Journal", "Projects")
    val tabs = listOf("Drafts/On slow software.md", "Journal/2026-11-12.md", "Welcome.md")
    val expanded = setOf("Drafts", "Journal")

    // The prototype names keyboard shortcuts here. On a phone the notes name the buttons instead.
    val files: Map<String, String> = linkedMapOf(
        "Welcome.md" to """# Welcome to Quire

Quire edits a folder of plain Markdown files. Each note is a `.md` file on your disk, so any other app can open it, and it will still open in twenty years.

## The basics

- Write in Markdown. Tap the eye button to switch between source and preview.
- Link notes with double brackets, like [[Reading list]] or [[On slow software]]. Long-press a link to follow it.
- Tag anything with a hash, like #idea or #todo.
- Tap the search button to jump to any note. The drawer lists every command.

## Try it

- [x] Open this note
- [ ] Rename it by tapping its name above
- [ ] Turn on focus mode from All commands in the drawer
- [ ] Switch to the dark theme in settings

> A link to a note that doesn't exist yet, like [[Someday]], creates it when you follow it.
""",
        "Ideas.md" to """# Ideas

A dumping ground. Anything worth keeping moves into its own note.

- A reading lamp that dims with the sunset #idea
- Why old text editors still feel fast: [[On slow software]]
- Try a three sisters bed next spring, see [[Garden plan]]
- Keep a log of every book abandoned halfway #reading
- Make the [[Sync protocol]] doc readable by someone who isn't me
""",
        "Reading list.md" to """# Reading list

#reading

## Now

- *The Peregrine* by J. A. Baker
- *A Pattern Language* by Christopher Alexander

## Next

- *The Mushroom at the End of the World* by Anna Lowenhaupt Tsing
- *Seeing Like a State* by James C. Scott

## Finished this year

- [x] *Piranesi* by Susanna Clarke
- [x] *The Overstory* by Richard Powers
- [x] *Braiding Sweetgrass* by Robin Wall Kimmerer
""",
        "Drafts/On slow software.md" to """# On slow software

#draft

The text editor I used in 2004 opened before I could lift my finger off the trackpad. The one I use now shows a splash screen. It has more features than I will ever touch, and I pay for every one of them in the half-second I wait each morning.

Some of this is nostalgia. Most of it isn't. A tool that responds instantly disappears, and a tool that hesitates reminds you it exists. When you are trying to write, the reminder costs more than the wait.

## What makes a tool feel fast

Three things, roughly in order:

1. **Input latency.** The gap between a keypress and a letter on screen. Under about 30 ms it feels like paper.
2. **Startup.** If opening a note takes longer than remembering why you wanted it, you stop opening notes.
3. **Predictability.** The same command does the same thing, in the same place, every time.

None of these appear on a feature list, which is why they are the first things traded away.

## Plain files

Software stays fast when it does less, and the simplest way to do less is to keep your work in a format that needs no software at all. A Markdown file is readable in any editor written in the last forty years, and it will be readable in the next forty.

> The best file format is one you could parse with a pencil.

## To do

- [ ] Find the latency numbers from the [[Sync protocol]] tests
- [ ] Cut the second section by half
- [ ] Ask Maya to read it #todo
""",
        "Docs/Sync protocol.md" to """# Sync protocol

#spec

How notes move between devices. Version 2, replacing the polling approach from last spring.

## Overview

Each device keeps a local log of changes. When two devices meet, they exchange the parts of their logs the other hasn't seen, then apply them in order.

## Change record

```json
{
  "path": "Drafts/On slow software.md",
  "hash": "9f2c41e",
  "parent": "71ab0d3",
  "device": "laptop",
  "time": "2026-11-11T21:04:17Z"
}
```

## Conflicts

When two devices edit the same file from the same parent, the later change wins. The earlier version is saved beside it as `name (conflict laptop).md`. Nothing is merged automatically.

## Open questions

- [ ] Should renames be their own record type? #todo
- [ ] Measure apply time on a folder of 10,000 notes
- [x] Drop support for the v1 log format

See also [[About this folder]].
""",
        "Docs/About this folder.md" to """# About this folder

Everything here is plain Markdown. Nothing depends on Quire.

## Layout

- `Journal/` holds one file per day, named `YYYY-MM-DD.md`
- `Drafts/` is writing in progress
- `Docs/` is reference material and specs
- `Projects/` is anything with a deadline

## Conventions

- Tags are lowercase: #journal, #draft, #spec, #idea, #reading
- Link generously. A link to a missing note, like [[Someday]], is fine. It becomes a note when you follow it.
""",
        "Journal/2026-11-12.md" to """# Thursday 12 November

#journal

Cold, bright morning. Frost on the shed roof until ten.

Spent the first hour on [[On slow software]]. The second section still reads like a list of complaints. Maybe it should just be a list.

- [x] Order seed potatoes
- [ ] Reply to the allotment committee
- [ ] Finish the conflicts section of [[Sync protocol]] #todo
""",
        "Journal/2026-11-11.md" to """# Wednesday 11 November

#journal

Long walk along the canal after lunch. Eleven herons, which might be a record.

Read forty pages of *The Peregrine*. Baker writes about a hawk the way other people write about weather. Added two more books to the [[Reading list]].
""",
        "Projects/Garden plan.md" to """# Garden plan

#project

## Spring beds

- **Bed 1:** potatoes, earlies then second earlies
- **Bed 2:** three sisters, so corn, beans and squash together
- **Bed 3:** brassicas under netting

## Before March

- [x] Order seed potatoes
- [ ] Repair the north fence
- [ ] Build a second compost bay
- [ ] Ask about the water butt at the next committee meeting

Ideas for later live in [[Ideas]].
"""
    )
}
