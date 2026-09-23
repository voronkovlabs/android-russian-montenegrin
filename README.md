# Crnogorski

An Android trainer for **Montenegrin**, built for a family of three.

Russian speakers learning the language of the place they live in: Russian →
Montenegrin, Latin script, ijekavian. No accounts, no server, no analytics.
Lessons ship inside the APK; progress lives in a local Room database.

> **This is personal software, published as-is.** It was written for one
> household — a husband, a wife and a thirteen-year-old — and every design
> decision assumes exactly that. It is not a product, it has no roadmap, and
> nobody is on call. You are welcome to read it, build it, fork it and take
> whatever is useful.

## What is in it

| | |
|---|---|
| Lessons | **61**, **671** exercises, an A1–A2 grammar ladder plus thematic units |
| Stories and dialogues | **32** texts, **494** segments, three exercise modes each |
| Vocabulary | **3 501** words with glosses, inflection tables and example sentences |
| Word hints | **1 561** Montenegrin entries, tappable in any exercise |
| Stress marks | **405** words where the position is certain — and silence where it is not |

Twelve exercise types, from multiple choice to reading a paragraph aloud. Nine
of them are checked **offline, on the device**; only free-form translation goes
to a language model.

### The parts worth stealing

**Spaced repetition tuned against boredom.** A simplified SM-2 with a binary
grade, plus a twist: an answer noticeably faster than your own average *for
that exercise type* counts as easy and gets pushed further out. The thresholds
came from complaints, not from a paper — "I'm tired of doing the same exercises"
is a scheduling bug.

**A daily session measured in minutes, not items.** Twenty multiple-choice
questions and twenty read-alouds are not the same amount of work, so the app
times every exercise type and fills a fifteen-minute budget with whatever fits.
Idle time is subtracted: leaving the phone on the table does not count as study.

**Speech that forgives the right things.** Android has no Montenegrin locale, so
`sr-RS` is used for both synthesis and recognition. Recognition is scored by
longest common subsequence with a per-character tolerance that grows with word
length — because the engine swallows prepositions and hears neighbouring words,
and demanding a perfect match made the app miserable to use. Word order still
counts.

**Ekavian answers are accepted, and then gently corrected.** The course is
Montenegrin, but a correct Serbian form is not an error. A narrow yat-reflex
fold decides when the difference is real, and the app stays silent whenever it
is not sure — because confidently teaching something false about a language is
worse than teaching nothing.

**A corpus self-test.** The phone reads every story and speech exercise aloud to
itself, listens with its own recogniser and compares. It finds segments that are
impossible to pass *before* a human walks into them. A failure is evidence; a
pass proves nothing, since both ends are the same vendor's model.

**A complaint loop that closes.** Every exercise has a flag. A complaint is
written to a file, uploaded as a GitHub issue, and when the issue is closed as
completed the phone shows a notification to whoever filed it. Feedback that
disappears into a void stops being written.

## Build

Requires Android Studio, JDK 21 and an Android 14 device (`minSdk 34`).

```bash
cd src
./gradlew assembleDebug
```

Create `src/local.properties` (template: `src/local.properties.example`):

```
sdk.dir=/path/to/Android/sdk
```

**You will need your own Anthropic API key.** It is not compiled into the app —
the first launch asks for it on a full screen and the app will not start without
it. Get one at [console.anthropic.com](https://console.anthropic.com). It is
stored in the app's private preferences, never leaves the device, and is not
included in cloud backups.

`GITHUB_TOKEN` in `local.properties` is optional and only used to file issues
from inside the app.

### A deliberate oddity

The published build ships a GitHub token in plain sight. That is not an
accident: it is what lets **anyone** file a complaint from the app without
signing in. Signing in through GitHub is offered but optional — if you do, your
issues are filed under your own name; if you don't, the shared token is used.
The trade-off is written down in `CLAUDE.md`.

## There are no tests, on purpose

The project is at a laboratory stage: ideas are tried and thrown away weekly. A
test freezes a decision that has not been made yet, and code that is defended
starts to feel valuable merely because deleting it would hurt. The three users
*are* the testers, and every release is installed and run by hand.

One thing is checked before every release, and only one: that the dictionary has
not silently lost a single lemma. A lemma is the key of a flashcard, so a
vanished word takes months of someone's real progress with it — and that is
exactly the kind of failure a human running the app cannot possibly notice.

## Documentation

**The documentation is in Russian**, and there is a lot of it — `CLAUDE.md` is
the design record of the whole project: not what the code does, but why each
decision was made and what was tried and rejected. `STATUS.md` carries the
running log, `IDEAS.md` the backlog, `research/` the source catalogue with
licences.

If you read Russian, `CLAUDE.md` is far more interesting than this file.

## Licence

Two licences, and they do not mix — see [`LICENSE`](LICENSE).

* **Code, lessons, stories and documentation** — MIT.
* **`src/app/src/main/assets/vocab/` and `research/data/`** — CC BY-SA 4.0,
  because they are derived from share-alike sources.

### Data sources

| what | from | licence |
|---|---|---|
| Glosses | Russian Wiktionary | CC BY-SA 4.0 / GFDL |
| Inflection tables | srLex 1.3, CLARIN.SI | CC BY-SA 4.0 |
| Example sentences | Tatoeba | CC BY 2.0 FR |
| Stress marks | English Wiktionary | CC BY-SA 4.0 |
| Two folk tales | Vuk Karadžić, *Srpske narodne pripovijetke* (1870) | public domain |
| Splash riff | Freesound | CC0 |

Lesson content is original. Topic ordering was informed by Columbia's *Naš
jezik* (CC BY-NC-SA); ordering is not copyrightable, and no text was copied.

Background photographs, the poster and the app icon are the author's own work.

Full breakdown: [`research/data/LICENSES.md`](research/data/LICENSES.md).
