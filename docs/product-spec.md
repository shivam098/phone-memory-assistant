# Phone Memory Assistant: cross-platform product specification

**Spec 0.4 · First-release contract, not release notes.** Help someone answer
“What did we decide?” from material they chose to save, see the exact words behind
the answer, and correct or forget them. Use devices people already own.

| Status | How to use this spec |
| --- | --- |
| **MUST** | The first release (R1) is **offline-complete**. Both transcription and short, source-backed insights must work **on device** in Hindi, English and code-switched Hinglish **before any cloud feature**. The flows, safeguards and R1 checklist below are requirements; text search alone is not a pass. |
| **Candidate / open** | Native APIs and storage layouts below are implementation candidates. Model, runtime, support floors and numerical budgets are open; choose them from measurements, not from this table. |
| **Implemented / verified** | A prototype feature is not a passed gate. Mark a gate verified only with a passing run on a named build, device and OS. No R1 gate is marked verified here. |

Android comes first, iOS next. macOS and Windows share the contract; neither a
desktop release order nor a date is implied. Each installation is independent.
R1 needs no wearable, privileged OS access, provider sign-in, network service
or cross-device sync.

**Android M1 evidence (unpublished test APK).** The prototype limits mic clips
to 25 seconds in the foreground, discards the active clip on lock, and requires
transcript review before saving. These are prototype limits, not the R1 capture
contract. It can select a verbatim source highlight or abstain. In a desktop
ASR smoke test, one English sentence was correct; the Hindi and code-switched
transcripts were incorrect. There is no native-device validation yet, including
on the OnePlus 8T. All-day capture and R1 language/insight quality remain unverified.
The Android M2 prototype only processed queued audio while unlocked; that does
not meet the [overnight transcription requirement](#overnight-transcription-while-locked).

Port guides: [Android](#android) · [iOS](#ios) · [macOS](#macos) ·
[Windows](#windows). All four use the [same release checklist](#release-and-port-conformance-checklist).

## What the user can do

1. **Save a detail.** A student types a note or picks a local text or audio file.
   Preview what will be copied, then save or cancel. No microphone permission is
   needed. The selection authorizes that item only, not its folder. Reject
   unsupported input or a cloud-only placeholder without silently fetching it.
2. **Remember conversations throughout the day.** With participants informed
   and agreeing, the user taps **Start**, pockets the phone and later taps
   **Stop**. Screen lock and switching apps do not interrupt a visible, active
   session. On-device automatic speech recognition (ASR) saves time-linked
   transcripts labeled **machine-transcribed, unreviewed**; review is optional.
   Saved notes, imports and transcripts appear in one encrypted local timeline
   with source, date (capture/import date if event date is unknown) and processing state.
3. **Recall and check.** Search or ask a question in any of the three language
   modes. Show a short answer or recap and any stated commitment. Each factual
   claim opens its source at the quoted passage, with time offset for audio.
   Distinguish what was said from a suggestion; show conflicting evidence side
   by side. Say what the searched passages support and what is still waiting
   for transcription. Never guess an owner, date or answer. A commitment is
   not a scheduled reminder or a sent message.
4. **Correct or forget.** Edit the passage in its source view; label user
   corrections as such, without rewriting the original audio. Withdraw outdated
   answers and rebuild from the corrected version. **Forget** previews the local
   items affected and removes the source and what the app derived from it.

## Capture lifecycle

**R1 requires an all-day listening session, not an always-on microphone.** The
user starts it once. It continues until stopped, including through screen lock
and app backgrounding, while the OS still permits capture and the recording
indicator and controls remain available. Keeping the display lit is not required.

| Event | Required transition and user-visible result |
| --- | --- |
| Start from **Idle** | Require an explicit user gesture. Explain who may be recorded, that recording continues when locked, how to pause, and what is retained. Ask the user to confirm participant consent. Check OS permission, input, visible controls, protected storage and local ASR readiness. Show **Starting**, not “listening”, until frames arrive. |
| **Recording**, including while locked | Keep a persistent, content-free OS-visible indicator and accessible **Pause** and **Stop**. Provide one-tap Pause from the ongoing notification or equivalent lock-screen control. Show active input and elapsed capture time from real frames, not a UI timer. Do not rely on color alone. |
| Pause or interruption | Release the mic on Pause, call, mic contention or OS silencing, permission/privacy-toggle revocation, route loss/change, capture suspension or stalled frames. Also pause if the indicator, controls or protected writer become unavailable. Seal the captured prefix, mark a gap with its reason and show **Paused** or **Interrupted** without personal content. Never switch inputs silently. |
| Resume | Only an explicit **Resume** gesture starts a new span, after rechecking consent, OS access, input, storage and controls. Tapping the app's Android notification **Resume** action counts. Unlock, wake, end of a call, route recovery, service recreation, boot and relaunch do not. |
| Stop or discard | **Stop** releases the mic immediately and closes capture; already queued local transcription may finish without it. Ignore late callbacks, including delayed starts. Discard purges session content and dependent work. Cancelling a job on a saved source removes partial outputs and offers retry or Forget. None of these restarts recording. |
| OS kill or process death | Do not depend on a shutdown callback. On recovery, keep the mic off, seal the prefix at the last authenticated chunk and mark the remaining interval interrupted (reason unknown if necessary). Offer to keep or discard the prefix. Never reconstruct missing words, report complete coverage, or auto-resume. |

An absent or invalid ASR model disables audio start/transcription with a local
setup explanation. An unavailable insight model disables insights, not reading
or searching existing notes. Neither state is “offline ready”. Never fabricate
a transcript, show a simulated recording, or route a failed local job to the network.

### Battery-efficient all-day capture

**R1 separates recording from inference.** An explicitly started session can
keep capturing on battery; by default ASR and new insights run only on external
power, with saver off, battery not low and safe thermal status. After the first
unlock since restart, ASR processes queued audio **while the phone is locked**;
jobs that need to read saved memories wait for unlock. Unplugging pauses
automatic work. A thermal warning pauses any inference job at a safe checkpoint,
including **Process now**. Plugging in may
make queued work eligible; it never starts the mic.

| What the user sees | Required behavior |
| --- | --- |
| **Waiting for charge** | While unlocked, search existing transcripts, citations and saved insights on battery. State coverage for the requested interval, e.g. “3 h 20 min of captured audio waiting for charge.” Pending audio is not evidence that nothing was said. Distinguish queued speech, gated-out time and interruption gaps. |
| **Transcribed overnight; available after unlock** | Show this content-free status only for work durably sealed while locked. It is not yet searchable. Never show transcript text, a source name or a preview on the lock screen; recall can cite the result only after the unlock-time merge succeeds. |
| **Process now** | For an urgent question, let the user select a time window and explicitly run its ASR/insight work on battery. Show progress and Cancel. This overrides only the external-power condition, not low-battery, saver, thermal, unlock or consent checks. Cancellation leaves unfinished chunks queued; it does not stop capture or grant a lasting override. |
| **Paused to protect battery** | If OS battery saver activates or charge reaches the low-battery threshold, release the mic, seal the prefix and show a content-free notification with tap-to-resume. Reject Start/Resume while the guard is active. Clearing it or connecting power does not resume a paused recording. |

Keep the screen-off capture path small:

- **Input:** Use one mono 16 kHz input, or one native equivalent with a single
  conversion if needed. No extra audio effects. Use large reusable buffers and
  blocking reads or native event-driven callbacks. With the screen off, do no
  UI refresh or diagnostics work beyond required indicator/control transitions.
  Add no wake locks, polling timers, sensors, location requests or network traffic.
- **Speech gate:** Apply a cheap loudness check, then a small local voice
  activity detector (VAD). Keep only detected speech with short pre/post padding
  from memory. Never write or transcribe stand-alone silence. Preserve skipped
  time as metadata, not audio; the mic indicator stays on while sampling.
- **Storage:** Append independently authenticated, encrypted chunks in time
  order, batching roughly one minute of session time. Seal shorter batches on
  pause or stop. Bound the chunk, not the multi-hour source. Use lossless audio
  by default (e.g. FLAC); do not adopt a lossy codec until its ASR impact has
  been measured across all three language modes.
- **Backlog:** Show pending duration and bytes. Warn before the queue or disk
  fills, then pause capture with a reason; never overwrite old speech or
  silently restart. Save transcripts locally with ASR provenance and
  **unreviewed** status. Delete working audio once the result is durably sealed;
  retained audio belongs with saved memories, not in the queue.

**Later candidates, off by default:** User-scheduled quiet hours could pause a
session but would still require an explicit Resume. Duty-cycled listening
(turning the mic off between samples) could miss the first words or an entire
utterance. R1's speech gate instead evaluates continuous input during capture.

### What dedicated recorders do

**Design rationale, not measurements of this app.** The useful comparison is
what a recorder does *during capture* versus what it defers.

| Source | Documented behavior |
| --- | --- |
| [Plaud device comparison](https://www.plaud.ai/pages/plaud-device-comparison) | Plaud lists a record button and local storage. Its Note Pro offers an Endurance recording mode; its NotePin lists cloud upload while charging as a transfer option. That describes transfer, not on-device transcription. |
| [NeoSapien Neo 1 FAQ](https://neosapien.ai/shop/ai-personal-assistant/) | Neo 1 advertises automatic listening with an active indicator. It can capture without internet while connected to the phone by Bluetooth; memories are processed when the phone is online again. Offline capture is not offline recall. |
| [Omi firmware](https://github.com/BasedHardware/omi/blob/main/omi/firmware/readme.md) | The CV1 firmware encodes audio as Opus frames and buffers timestamped records to flash when disconnected. The phone can retrieve them later. |

R1 borrows the separation, not the cloud dependency, automatic start or codec.
[Zhidkov et al. (2018)](https://doi.org/10.3390/asi1010008) found that recording
itself dominated background audio power on the Android phones tested. Moderate
signal processing added comparatively little; consumption varied by model with
no clear relationship to release year or chipset. That study did not measure
our ASR or the OnePlus 8T. Measure each reference device; do not convert wearable
runtime claims into a phone battery budget.

## Boundaries of permission

| Permission | What it permits | What it does **not** permit |
| --- | --- | --- |
| OS mic permission | The app may request the input while OS policy allows. Recheck on use. | Starting a recording, or consent on behalf of anyone nearby. |
| Participant consent and user start | This visible session only. The user must inform participants and get their agreement; pause when someone new joins until they agree. If anyone objects, do not record. | The app obtaining consent on others' behalf. Neither the start tap nor the persistent indicator substitutes for their agreement or authorizes future sessions, call audio, sharing or actions. |
| Source read | Import the chosen item. **Later:** read a specifically authorized personal calendar or Gmail selection, with the narrowest available read-only scope. Show the real provider scope and apply any narrower user filter. On revocation, cancel pending reads and stop new ones; offer Forget for saved copies. | Cloud AI processing, creating events, replying to mail, or reading other apps. |
| Cloud processing (**later, opt-in per request**) | Review the **exact** passages, question and metadata to be sent, named destination and purpose. Send only that payload after approval; cancel before dispatch means no send. | Uploading the timeline, continuing permission, writing to a service, or fallback when local capability is missing. |
| External write or export (**later**) | Preview the exact fields/text/file, operation and destination/recipient; approve this action only. Prefer a user-controlled save dialog or composer. Changing the payload or target requires new approval. | Autonomous sends, calendar changes on read permission, or claiming a handoff means delivery. |

Speech, notes and imported messages are **data**, never instructions to an
adapter. Message-specific integrations are later. SMS/RCS and personal WhatsApp
material may come from a user-selected share or export where supported, not
private inbox scraping, unrestricted SMS permissions, notification harvesting
or a business API used as personal history.
An authorized network read contacts the source; it is not permission to forward
the response. OS backup and transfer are separate paths, not connectors.

## Portable data and adapter contract

The following are logical records, not an Android schema, wire protocol or
requirement to use a particular database. IDs are local and opaque. Keep text
as entered, imported or transcribed, including Devanagari, Latin and mixed
script. Normalized spellings or transliterations are search aids, not quotations.

| Record | Minimum information and invariant |
| --- | --- |
| **Source** | Stable `sourceId`, monotonic `revision`, kind (note/import/mic; connector later), display label, capture/import time, optional event time and zone, local payload reference, processing/coverage state. Record origin identity when available for duplicate/forget checks. |
| **Segment** | `sourceId`, `revision`, `segmentId`, order, original-script text, provenance (typed/imported/ASR/user-corrected) and ASR review state. For audio, `[startMs,endMs)` on the session timeline, plus chunk/media offsets when audio exists. Keep pause/interruption ranges and reasons, and skipped silence, distinct so later citations do not shift. No fake offsets for text notes. |
| **Index entry** | Local search term or other retrieval key → source revision, segment and text span. Return hits only for live revisions. Derived indexes, including any embeddings, get the same protection as the originals. |
| **Evidence span** | Source ID + revision + segment ID + half-open text range in Unicode scalar values of that segment, not platform string indices. Resolve against the stored text for the exact quote; use segment timing for audio. No playback link if audio was not kept. |
| **Insight / commitment** | Local recap, answer or proposed task; attach evidence spans to **each claim**, plus producer/version. Distinguish quoted fact, inference, conflict and unknown. Task, owner and due time are separate; an unstated value is null, not inferred from the current user or clock. |
| **Grant** | Capability (mic/read/egress/write), scope, purpose, validity/revocation and, for egress/write, destination and approved payload identity. A stored grant cannot override current OS state. Never reuse one kind for another. |
| **Pending action** | Draft operation, exact payload/destination, source revisions, approval and operation ID. Persist the dispatch attempt before invoking the target. Distinguish draft, approved, cancelled, handed off, confirmed, failed and **outcome unknown**. If an in-flight attempt lacks a receipt after timeout or restart, check status if possible; never retry blindly. |
| **Job / forget marker** | Jobs carry input IDs/revisions and a cancellation token. For queued audio, record the covered time window, checkpoint and wait reason. A durable, content-free marker blocks forgotten origins on future imports. Neither a job ID nor a cached answer is proof its inputs still exist. |

| Adapter boundary | Operations and results a port must provide |
| --- | --- |
| Input and capture | `pickLocal()` yields selected bytes/type/origin or cancel; `start(input, consent)` yields a session or reason. Emit frames, route/permission changes, interruptions and end. Expose pause/resume/stop/discard. Do not let capture code invoke a connector. |
| Local models | `probe(task, languages)` checks installed assets, integrity, architecture and resources for **ASR and insights separately**. `transcribe(chunk, cancel)` yields time-linked, unreviewed segments; `derive(question, evidence, cancel)` yields supported claims or abstains. No network backend. |
| Protected store and retrieval | `save`, `timeline`, `search`, `resolve(span)`, `correct` and `forget` require the unlocked vault. `stageSealed(job, output)` can write while locked but cannot read the journal. `mergeOnUnlock` publishes output atomically only if **all** input revisions are current and none was forgotten. Recheck before displaying a late result. |
| Optional external I/O | Separate `read(scope)`, `processApproved(payload, destination)` and `performApproved(action)` entry points. Bind approval to the reviewed bytes/fields, revisions and target; recheck at dispatch. The offline pipeline must run with **none** of these adapters installed. |

Return explicit `denied`, `cancelled`, `locked`, `model unavailable`, `no space`,
`interrupted` or `stale input` results as applicable. “No matching evidence” is
a successful search of the stated coverage, not another name for a failed
search or audio that has not been processed.

### Overnight transcription while locked

Use **two protection classes with separate keys**. Locking a phone after its
first unlock is not the same as restarting it and leaving it locked. Require a
secure screen lock; if a platform cannot enforce the separation, report the
capability unavailable rather than lowering the journal's protection.

| Class | Before first unlock after restart | After first unlock, then relocked |
| --- | --- | --- |
| **Pending audio** — encrypted speech chunks, their processing keys and minimal job metadata | Neither the audio nor the keys needed to read it are available. No transcription. | An eligible local charging job can read its assigned chunks, including after process death. It needs no saved-memory key and never opens the mic. Delete each pending chunk and its wrapped content key after its result is durably sealed. |
| **Saved memories** — segments, transcripts, insights, journal, indexes and any audio the user chose to keep | Unreadable. | Still unreadable. A worker can seal **new** output using the vault's public key; the private decryption key, existing journal and index are unavailable. Close readers and clear decrypted caches and vault keys on lock. |

1. **Seal, don't publish.** Keep plaintext ASR output only in working memory.
   Encrypt each result with a fresh authenticated-encryption key, wrap that
   key to the vault public key provisioned while unlocked, then clear it.
   Bind the envelope to its job, source revision and chunk ID. Close and durably
   stage it before retiring the input; on failure leave the audio queued.
   If retention was chosen, also seal the audio for the vault before deleting
   the queue copy. No plaintext checkpoints or index updates while locked.
2. **Merge on the next unlock.** Verify the envelope and expected job, then
   in one transaction recheck source revisions, cancellations and Forget
   markers before adding to the journal and index. Drop stale results rather
   than recreating a source. Record accepted chunk IDs so a crash or replay
   cannot publish twice. If the device relocks, close the vault and leave
   unmerged envelopes sealed.

**Disclose the tradeoff before recording:** If a powered-on phone is stolen
after its first unlock and someone bypasses the screen lock to gain the
background worker's access, they could read audio still awaiting transcription.
That access does **not** decrypt saved memories while the OS still protects the
vault key. It is not a guarantee against someone who can actually authenticate
as the user or compromise the OS or keystore.

### Storage, correction and forgetting

- **At rest:** Encrypt recordings, source text, metadata, indexes, derived
  outputs and temporary files (including database journals and model input
  caches). Protect each class's keys with native user/device key storage.
  Fail closed if protected storage is unavailable. No content
  in telemetry, logs, crash reports, notifications or system search indexes.
  Exclude the store, staging files and key material from default backup, sync
  and unintended device transfer. A sandbox or disk encryption alone is not enough.
- **Active capture while locked:** The recorder may only append encrypted chunks
  to its user-started session. Wrap chunk keys under the pending-audio class;
  hold the unwrapped session key only in memory during capture and release it
  on pause, interruption or stop. The separate ASR worker can read sealed pending
  chunks, not the journal. Unlocking alone never starts or resumes recording.
- **Correct:** Publish a new revision and immediately invalidate old search
  hits, citations, insights, displayed/cached answers and unsent actions depending
  on the old one. Re-index/recompute from current sources. Never point an old
  citation at a different sentence merely because its offsets still fit.
- **Forget:** In one durable transaction, make the source unreadable, mark its
  origin suppressed and invalidate all dependent work. Cancel jobs, remove raw
  audio/text, pending keys, staged envelopes, segments, postings, derived results,
  drafts and caches. Recompute multi-source outputs using only survivors.
  Finish interrupted cleanup on
  restart before serving results; do not promise physical overwrite of flash.
- **Prevent resurrection:** Match a minimal local keyed identifier of stable
  origin and, for files, a keyed fingerprint of the original bytes. Check these
  markers in the import transaction before creating a source ID, not just when
  the job starts. A renamed copy, repeated share, connector refresh or replayed
  job must not silently recreate the item. Keep no text in the marker. Adding
  it again requires a fresh decision. Explain the limits: changed unidentifiable copies,
  originals in other apps, prior exports, recipients and independently made
  backups are outside local Forget; a full store reset also loses its markers.

## Native adapter notes (candidates)

Use the common lifecycle and tests below even when the OS offers more access.
Package the required models with the app or as integrity-checked packs that can
be installed from local media. A fresh install with those assets must work
offline without a first-use fetch. Probe real local inference; an OS language
listing or available accelerator is not proof of offline support.

### Android

- Use `AudioRecord` with runtime `RECORD_AUDIO`. For continued background
  capture, start a [microphone foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
  from visible user interaction, with the applicable manifest permissions/type
  and a content-free ongoing notification with **Pause** and **Stop**. Verify
  the notification and actions remain available through screen lock and app
  switching; if hidden or blocked, do not continue background capture. After
  interruption, offer **Resume** by notification tap subject to [foreground
  service start rules](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-exemptions);
  bring the user to the app if needed. Test a call, privacy toggle, Bluetooth
  loss and process kill. Never reacquire the mic on service recreation or boot.
- Queue resumable chunk jobs in [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
  or `JobScheduler` with charging and battery-not-low constraints. Observe
  `PowerManager` saver and thermal status; recheck constraints while running.
  Keep inference out of the microphone service. Respect [foreground service
  type timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
  for the target OS; the `mediaProcessing` time limit is not a microphone timer.
- Check the packaged ABI, local model load and available memory. Store pending
  chunks and their wrapped keys in app-private [credential-encrypted storage](https://developer.android.com/privacy-and-security/direct-boot),
  never device-encrypted Direct Boot storage. This protects the wrapped chunk
  keys until first unlock; a Keystore alias alone does not establish that gate.
  Use a separate queue wrapping key and check `UserManager.isUserUnlocked()`,
  not a blanket `isDeviceLocked` veto on ASR. Gate vault **decryption** on
  current unlock.
- Android's [`setUnlockedDeviceRequired(true)`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean))
  does not restrict public-key operations: the worker can wrap a result key
  while the vault private key cannot decrypt. On Android 11 and lower, the
  flag also did not restrict symmetric **encryption or verification**; that
  exception is not permission to decrypt. The API also documents encrypting
  with a software-generated key, importing it as unlock-required, then clearing
  the software key. Test actual lock behavior on every supported OS version.
  Use no-backup storage for both classes and staging; set and test
  [backup rules](https://developer.android.com/identity/data/autobackup) for both
  cloud backup and device-to-device transfer on supported OS/OEM combinations;
  do not rely on `allowBackup` alone.
- Import through `ACTION_OPEN_DOCUMENT` or an explicit share to the app. A
  reviewed export can later use `ACTION_CREATE_DOCUMENT` or the sharesheet.
  Do not request broad file or message access.

### iOS

- Use AVFoundation capture with an [`AVAudioSession` recording category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record),
  a microphone usage description and [capture authorization](https://developer.apple.com/documentation/avfoundation/requesting-authorization-to-capture-and-save-media).
  Enable the background audio mode **only for an ongoing user-started recording**.
  Keep the system recording indicator and provide lock-screen Pause/Stop
  controls; the indicator itself is not a pause button. Test both while locked
  and backgrounded on a physical iPhone. Pause if the controls cannot be kept
  available. A call or headset loss interrupts; its end is not a Resume gesture.
- Request a `BGProcessingTask` with [`requiresExternalPower`](https://developer.apple.com/documentation/backgroundtasks/bgprocessingtaskrequest/requiresexternalpower)
  set on its request. iOS chooses when to run it. Check charge level, Low Power
  Mode, `ProcessInfo.thermalState` and **pending-class** access; checkpoint on
  expiration. A locked journal does not block eligible ASR. Run **Process now**
  as visible, cancellable foreground work.
- Bundle or locally import models and exercise both tasks on device. Use
  [Complete Until First User Authentication](https://developer.apple.com/documentation/foundation/fileprotectiontype/completeuntilfirstuserauthentication)
  (class C) for sealed pending audio and [AfterFirstUnlockThisDeviceOnly](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly)
  for its Keychain material. A restarted worker must be able to reopen pending
  files on a relocked iPhone, not just keep one recording handle open.
- Stage new sealed outputs with [Complete Unless Open](https://developer.apple.com/documentation/foundation/fileprotectiontype/completeunlessopen)
  (class B, [ECDH-based file protection](https://support.apple.com/guide/security/data-protection-classes-secb010e978a/web)):
  write while locked, then close; a closed output cannot reopen until unlock.
  Keep the journal and index under Complete Protection (class A), and vault
  keys [WhenUnlockedThisDeviceOnly](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly).
  Release **vault** keys/handles on lock without disabling the pending class.
  Exclude both classes and staging from [backup](https://developer.apple.com/documentation/foundation/urlresourcevalues/isexcludedfrombackup)
  and migration; do not synchronize either Keychain item.
- Use a document picker or share extension for selected material only; protect
  and clean any extension staging area. A later share sheet or event editor is
  a reviewed handoff, not access to Messages or Mail history.

### macOS

- Use AVFoundation or Core Audio input, a microphone usage description, TCC
  (macOS privacy approval) and the [audio-input entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.device.audio-input)
  as applicable to the signed sandbox/hardened build. Provide window or menu-bar
  recording controls. On screen lock, continue only if the indicator and controls
  remain accessible; a hidden menu-bar item does not qualify. Release input on
  sleep or route loss. Test lock, denial/revocation on the distributed build,
  USB mic removal and lid sleep/wake. No login recorder or system-audio capture.
- Schedule automatic inference on AC power. Observe power-source changes, Low
  Power Mode and `ProcessInfo.thermalState`; checkpoint if eligibility changes.
- Package and load a local runtime for each claimed CPU architecture; verify
  any acceleration on that hardware. Keep encrypted files in non-synced,
  per-user Application Support with a non-synchronizing Keychain-protected key.
  Separate after-login queue access from vault decryption gated on current
  unlock or user presence; an ordinary login Keychain is not a screen-lock gate.
  Keep data out of iCloud Drive and Spotlight; configure and test backup
  exclusion, including Time Machine. Check migration separately from sync.
- Use `NSOpenPanel` or deliberate drag-and-drop to import. A later save panel
  or sharing service requires review. No Full Disk Access or cross-app scripting
  to read private Mail or Messages stores.

### Windows

- Use [WASAPI capture](https://learn.microsoft.com/en-us/windows/win32/coreaudio/capturing-a-stream)
  on the selected input endpoint (not loopback), or `MediaCapture`. Check mic
  privacy settings: declare the microphone capability where the packaged app
  model requires it; classic desktop apps use desktop-app privacy controls,
  not necessarily a per-app prompt. Keep pause/stop in the window or tray.
  Test default-device change, endpoint unplug, denied access, session lock and
  suspend/resume. If session lock hides the only controls, pause. A new default
  mic or a return from suspend requires an explicit Resume.
- Use AC/battery and energy-saver [power notifications](https://learn.microsoft.com/en-us/windows/win32/power/power-setting-guids)
  for inference eligibility. Checkpoint on unplug or OS thermal pressure. Verify
  which thermal signals the target exposes; do not assume a CPU temperature
  sensor is available or treat an unknown reading as evidence that it is cool.
- Ship matching local runtime/model packs for each claimed architecture; test
  a CPU path rather than assuming a GPU/NPU. Store encrypted files in user-local
  non-roaming app data, not redirected Documents or OneDrive. Wrap the data key
  with user-scoped [DPAPI](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata),
  not machine scope. Test profile redirection, backup/restore and a different
  Windows user; roaming profiles mean DPAPI is not a device-only guarantee.
  DPAPI alone is also not a workstation-lock gate. Give the after-logon worker
  only pending keys and the vault public key; verify a separate unlock-bound
  private-key policy before claiming locked processing on Windows.
- Use a file-open picker/dialog or explicit drop; use a reviewed save dialog or
  share target for export. Do not read another app's message database or capture
  call audio. Check the packaged or unpackaged distribution, not just a debug build.

## Shared acceptance fixture

**“Field kit”** is invented test material. Record a consenting tester reading
the lines, or import a local recording of them; the offsets below illustrate a
fixture, not a required ASR segmentation. Also run the text-only path with a
typed note. Start with no other sources.

| Media time | Spoken line / expected source passage |
| --- | --- |
| 00:00–00:03 | सेंसर का डिब्बा नीला है। |
| 00:03–00:07 | The spare cable is in drawer two. |
| 00:07–00:13 | Friday को blue box लाना है। Time अभी तय नहीं है। |

1. Ask **“क्या लाना है Friday को, और कितने बजे?”** Expect *bring the blue box on
   Friday*, citing the last passage at 00:07–00:13. Time is explicitly undecided;
   who will bring it is absent. Show no invented time, owner or calendar event.
   Asking which box is for the sensor should open the Hindi passage. Request a
   local recap: “The sensor box is blue. Bring the blue box on Friday; time not
   set.” The first claim must link to the Hindi passage, the rest to the mixed one.
2. Ask **“Spare cable कहाँ है?”** Expect *drawer two*, citing the exact English
   passage. Correct **two** to **three** in the transcript. The old citation must
   no longer resolve as current. Ask again: *drawer three*, with a quote visibly
   marked as the user correction at the new revision.
3. Start another insight and **Forget Field kit** before it finishes. Its late
   result must never appear. After restart the timeline, search, answers and
   pending actions have no Field kit content. A new search returns no evidence;
   replaying the identical import cannot restore it automatically.

## Release and port conformance checklist

Apply these gates on **each** proposed port; passing on Android does not certify
a desktop or iOS build. Use synthetic or expressly consented fixtures only.

- [ ] **Offline usefulness:** On a fresh install with local assets, external
  power and radios off, run note, import and visible mic → ASR → timeline → cited recall/short
  insight → correction → Forget. Run Hindi, English and within-utterance
  Hinglish. Without first reviewing the transcript, check the ASR words and
  each cited claim against the recording. A typed or corrected transcript, a desktop smoke
  test or a verbatim highlight alone cannot pass this native end-to-end gate.
- [ ] **All-day capture:** Start a multi-hour handset session; background the
  app and lock the screen. Speak distinct synthetic markers before and during
  lock. Both appear in order without another Start. Exercise one-tap Pause
  from the lock screen, then Resume. Interrupt with a call; ending it must
  not reopen the mic. Tap **Resume** (Android: the notification action).
  Force-kill the recording process mid-chunk; unlock and relaunch without
  resuming. Verify a sealed prefix, marked gaps/reasons, content-free status
  and no frames in the gaps. Resume explicitly, then Stop; wake and boot must
  not start new audio. On desktop repeat with input contention and sleep/wake;
  expect a marked pause on lock if the OS hides the controls.
- [ ] **Overnight transcription:** Queue two synthetic clips and reboot on
  external power. Before first unlock, neither pending audio nor saved memories
  can be decrypted; ASR does not run. Unlock once, relock and allow a charging
  job to run overnight. Verify the backlog drains **while locked**, even from a
  fresh worker process, without opening the vault or mic. Pending audio/keys
  are removed after durable staging; the worker cannot decrypt its closed
  outputs, retained audio, historical entries or index. Status contains no text
  from the clips. A run that only processes after morning unlock is not a pass.
- [ ] **Unlock-time merge:** Hold the merge, unlock and edit or forget one
  source. Release the merge, interrupt it and retry. Only the unchanged clip
  becomes searchable and citable, exactly once, with ASR provenance and
  unreviewed status; no stale sealed result resurfaces. Separately fill the
  pending queue with power disconnected: warn and pause without losing audio.
- [ ] **Battery and speech gate:** With the screen off and unplugged, capture a
  consented fixture; verify no automatic ASR/insights and that recall reports
  the backlog. Run **Process now** on one interval and cancel; other intervals
  stay queued. Plug in to drain, then unplug or raise thermal pressure: workers
  checkpoint without duplicates. Trigger saver/low battery: capture pauses and
  stays off until a tap. Score gated and ungated runs against the same human
  reference for soft speech, Hindi and code switches; count missed utterances and clipped
  first/last words. Silence-only intervals produce no audio or ASR jobs. Pass
  the agreed device budgets below without trading away the language gates.
- [ ] **No egress:** Repeat cold start, capture, inference, search, failure and
  restart on an **otherwise online** device with no external grants. Observe
  app/SDK and delegated-service traffic, not just a network-denied UI. Expect
  no outbound attempts from the local workflow, including telemetry and model
  fetches; distinguish unrelated OS traffic.
- [ ] **Failures and capture truth:** Deny/revoke the mic, cancel a picker/job,
  remove or corrupt each model independently, lock the store and exhaust free
  space. Expect specific unavailable states, no false success and no plaintext
  fallback. Exercise the native interruptions above: no frames while paused,
  and no restart on call end, kill/relaunch, reboot or wake without a new gesture.
- [ ] **Protection:** Use a unique synthetic phrase. Inspect files, indexes,
  journals, temp/cache directories, logs and crash diagnostics after normal
  use and a forced exit; no plaintext content. Check actual default backup,
  restore, migration and sync paths on the target OS: no unintended copy or
  usable migrated vault. Test unavailable key handling without resetting data.
- [ ] **Consistency:** Hold a worker just before publishing, then edit or
  forget its source. Release it and restart during cleanup. Expect no stale
  result, resolvable old citation or recreated data; unchanged independent
  sources still work. Retry a renamed identical import and rebuild the index.
- [ ] **Evidence for the support claim:** Record build, device/OS, RAM, model
  versions, backend, fixture and measured results. Agree thresholds for the
  measures below before sign-off; missing budgets or evidence leave this gate open.

**Separate gate for later integrations:** With read-only access, try a cloud
request and a calendar write: both must stop at separate reviews. Decline,
revoke or edit a reviewed payload and verify nothing was dispatched. Simulate a
lost acknowledgement after an approved write: show *outcome unknown*, not
“sent” or an automatic retry. Removing all integrations must not break R1.

### Measurements and decisions still open

Start Android evaluation with the **OnePlus 8T (Snapdragon 865, 8/12 GB RAM
variants)**. Record the *installed* OS and actual memory variant; neither a
minimum OS nor the performance of either configuration is established. Select
physical iPhone, Mac and Windows reference devices before claiming a port.

| Measure on native hardware | Evidence needed to set a release threshold |
| --- | --- |
| Speech and retrieval | Compare transcripts with human references by language, script, noisy/quiet speech and code switch: word/character errors, lost negations, names and numbers. Measure retrieval of the right passage across query languages. |
| Recall and insights | Human-check individual claims against cited spans; count unsupported claims, missing/wrong citations, correctly recognized unknowns and errors after correction/deletion. |
| Latency and capacity | Cold/warm time to first/final transcript, search and insight latency; peak resident RAM, storage growth per hour, peak pending audio (minutes/bytes) and time to clear it. |
| Battery and heat | On the same build/device, repeat matched screen-off runs: **idle** (mic off), **capture only** (read/discard), **capture + gate + encrypted writes**, then **on-charger processing** of the same backlog. Log duration, speech/silence mix and temperature. For unplugged runs report total mAh, mAh/hour and charge percentage points/hour, including the increase over idle. Use a charge counter or external meter; label unavailable readings. On charger, measure audio minutes processed per hour, temperature and throttling, not net battery percentage as an energy estimate. |

R1 targets battery-efficient all-day sessions. Agree battery, thermal, storage,
backlog and speech-miss budgets **with the user from OnePlus 8T measurements**
before Android sign-off; a full day on one charge is not a measured result.
Model/runtime, licenses and offline packaging, OS/architecture floors, quality,
latency, memory limits and any optional audio-retention period remain open.

## Optional ideas from Plaud

These are candidates, not approved requirements. Establish the offline Hindi,
English and Hinglish baseline on a handset before taking on new model work.
The links describe Plaud's features; the adaptations below need no Plaud SDK,
sign-in, cloud sync or marketplace prompts.

- **Bookmark a moment** ([Plaud highlights](https://support.plaud.ai/hc/en-us/articles/63429919023385)).
  The first idea to try needs no new model: during visible recording, tap to
  mark the time and optionally type a short memo. Jump to the reviewed transcript
  segment even after temporary audio is deleted. Label the memo as the user's
  note, not spoken evidence; Forget removes both the bookmark and memo.
- **Limit a question to chosen sources** ([Ask Plaud](https://support.plaud.ai/hc/en-us/articles/63429926029593);
  [single-file timestamp links](https://support.plaud.ai/hc/en-us/articles/50636602977817)).
  Add a scope picker to existing cited recall: *This conversation* or *Selected
  sources*. Filter before retrieval, jump to the supporting passage, and say
  “not found in these sources” even when an unselected file contains the answer.
- **Suggest familiar spellings** ([Plaud custom vocabulary](https://support.plaud.ai/hc/en-us/articles/63429935673625)).
  Let users opt into a device-local list of names, acronyms and Devanagari/Latin
  aliases. Show proposed corrections for review; do not silently rewrite quotes.
  Compare missed terms and false substitutions with vocabulary on and off.
  This is not a fix for code-switched ASR.
- **Show different views of one transcript, later** ([Plaud multidimensional summaries](https://support.plaud.ai/hc/en-us/articles/63429925078553)).
  Group supported passages from the same revision into *Decisions*,
  *Commitments* and *Open questions*. A missing owner or deadline stays
  unknown; a commitment creates no external task.
