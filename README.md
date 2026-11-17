# Phone Memory Assistant

An Android-first personal memory assistant that uses the phone you already own.

**[Cross-platform product specification](docs/product-spec.md)**: shared behavior,
native adapter guidance and release tests for Android, iOS, macOS and Windows.

**Status:** The Android test prototype is published here as source, not as a
production release. Its speech and text models are imported separately.
OnePlus 8T validation is pending, including battery use and recognition
quality in Hindi, English and mixed Hinglish. Early desktop speech tests
misrecognized Hindi and code-switched clips.

## Try the notebook

1. Install the debug APK on an ARM64 Android 10+ test phone. Start in airplane
   mode; no sign-in, API key, assistant service or microphone grant is needed for notes.
2. Type a note, or tap **Import UTF-8 text** and choose one local plain-text
   file. Review the draft, then **Save locally**. Imports are copies, not live
   connections. This milestone allows 100 sources, up to 16 KiB each.
3. In **Recall**, enter words from a line. Each result quotes the saved source
   and gives its reference and line number. Tap to read it. This is literal
   Unicode text search, not a generated answer or cross-script translation.
4. Open a source and **Forget source**. Its notebook copy and derived matches
   disappear, including after restart. The app does not delete the original
   file outside the notebook.

### Optional offline models

The APK has **no `INTERNET` permission**. To acquire weights, run the following
on a **computer** with Node.js 20+ (or download the exact files from the
[pinned catalog](app/src/main/assets/models.json)). No account is needed.

```shell
node tools/fetch-models.mjs speech
node tools/fetch-models.mjs text
```

Copy the `local-models/speech` and `local-models/text` folders to the phone
over USB. In **Manage local models**, long-press and select **all three speech
files together**, then select the single `.task` file for text. The importer
checks byte sizes and SHA-256 before activating a complete set. It doesn't
trust provider filenames or accept an English-only substitute. Allow room for
the picker copies, the app's own copies (~675 MiB combined) and runtime cache.
The picker copies can be removed after successful installation.

- **Speech:** get everyone's permission, choose a language hint if desired,
  then tap **Start recording**. Granting mic permission does **not** start it;
  tap again. The mic stays on only on the visible recording screen, for at most
  25 seconds. Stop to transcribe. Edit the unverified draft before saving;
  no audio file is written. Losing focus during capture discards the clip.
- **Text:** open a saved source and tap **Find a highlight**. A local text
  model selects one of up to six short passages. Only a valid selection is
  displayed, as a verbatim excerpt with a citation. This is **extractive**:
  no free-form advice, generated facts or actions. Missing models, bad
  imports, interrupted work and unsupported output do not produce fake results.

See the [model evidence and handset test guide](docs/handset-check.md) for
licenses, limitations and the tests needed before using this for real memories.

### Build

Use JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME`
or configure `sdk.dir` in the ignored `local.properties`. The first **build**
fetches Gradle/dependencies; running the app does not. No NDK build is required.

```shell
./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Use `gradlew.bat` on Windows. The installable app is
`app/build/outputs/apk/debug/app-debug.apk` (not the instrumentation APK).
`:core:test` can run without an Android SDK. The debug build is for synthetic
test data, not a signed production distribution.
## Product direction

Help people recall conversations, notes and information they choose to connect,
then turn that context into useful, source-backed insights.

- Personal context stays on the device by default.
- Cloud processing requires explicit permission.
- Reading a connected source does not authorize sending its contents to an AI
  provider or performing an action.
- Sending messages, changing calendar events and other external writes require
  separate approval.
- Android comes first; iOS follows after the core experience is established.

The first release is **offline-complete**: transcription and AI insights must
work locally before cloud features are introduced. Initial speech support must
cover Hindi, English and mixed Hinglish.

Local-first describes the ownership and default handling of personal context.
Optional cloud processing may be added later with explicit consent, but is not
a fallback for missing local capability in the initial release.

## Initial development sequence

1. Local notes and selected imports, encrypted storage, search, source references
   and deletion.
2. Explicitly started conversation capture and an on-device transcription
   experiment.
3. Source-grounded recall and insight generation, with clear unavailable and
   uncertain states.
4. Selected read-only calendar and email connections.
5. Explicitly authorized cloud assistance and separately approved actions.

Exact model selection and supported insight complexity remain open. Evaluate
both transcription and generation on representative phone hardware, including
code-switched speech. There are no measured performance or all-day recording
guarantees.

## Privacy and correctness requirements

- Separate consent for capture, source access, cloud processing and writes.
- No silent fallback from local processing to a remote provider.
- Protect recordings, transcripts, indexes, summaries and credentials; exclude
  sensitive data from automatic cloud backup and unintended transfers.
- Do not place personal content in analytics, crash reports or operational logs.
- Preserve source references and uncertainty; generated guesses are not facts.
- Forgetting content must remove dependent memories and indexes and prevent
  background work from recreating it.
- Treat recorded speech and imported content as data, not action authorization.
- Keep external writes reviewable and report uncertain outcomes honestly.

## Integration boundaries

Calendar and Gmail connections require appropriate permissions and scopes.
SMS access is restricted by Android distribution policies. WhatsApp's public
business platform is not general access to a personal inbox; user-selected
sharing or imports are the initial path to evaluate.

The app must not bypass operating-system permissions or promise unrestricted
access to other apps, private chats or call audio.

## Development data

Use synthetic or explicitly consented test data. Never commit recordings,
transcripts, credentials, signing keys, OAuth tokens or personal memory databases.
Keep local experiments and model assets in ignored directories. Do not copy
private research notes or account configuration into this public repository.
