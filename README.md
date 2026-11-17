# Phone Memory Assistant

An Android-first personal memory assistant that uses the phone you already own.

**Status:** Initial setup; offline-first implementation is next. No working
application is available yet.

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
