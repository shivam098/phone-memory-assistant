# M2: handset acceptance and power measurements

**Status:** buildable Android test milestone. No Android device was attached
for implementation. Passing JVM tests or compiling a device-test APK does
not demonstrate a day of capture, handset FLAC support, or useful Hindi ASR.
Use synthetic or explicitly consented inputs; don't collect private phone data.

## What dedicated recorders do differently

| First-party source | What transfers to this app |
|---|---|
| [Plaud device comparison](https://www.plaud.ai/pages/plaud-device-comparison) | A record button, local storage and a charging-time transfer option separate capture from later work. Uploading while charging is not offline transcription. |
| [NeoSapien Neo 1 FAQ](https://neosapien.ai/shop/ai-personal-assistant/) | Neo describes offline capture over Bluetooth and processing when the phone is online again. We use a queue too, but do not require connectivity or start automatically. |
| [Omi firmware](https://github.com/BasedHardware/omi/blob/main/omi/firmware/readme.md) | The CV1 buffers encoded audio when disconnected. We use lossless speech chunks, not Opus; codec effects on code-switched ASR need a comparison before considering lossy encoding. |

[Zhidkov et al.](https://doi.org/10.3390/asi1010008) measured a substantial
cost for an open microphone that varied between phones. VAD avoids writing
and transcribing long silences; it does **not** make an open microphone free.
None of the above is a measurement of a OnePlus 8T.

## What the APK does

- One 16 kHz, mono, PCM16 `AudioRecord` with blocking reads. No app wake lock,
  screen timer, location, network or audio effects. A short in-memory FIFO
  decouples reads from inference and FLAC encoding; it does not silently drop
  blocks on overload. The keep/discard decision is loudness then bundled
  [Silero v5.1.2](https://github.com/snakers4/silero-vad/tree/v5.1.2) (MIT,
  SHA-256 verified at build). The gate retains short pre/post padding. Noise
  can be mistaken for speech and quiet speech can be missed.
- Retained audio is packed at roughly one-minute boundaries, or on Stop/Pause,
  as [lossless FLAC](https://developer.android.com/media/platform/supported-formats#audio-formats),
  encrypted before any filesystem write. The global backlog is limited to
  256 chunks / 64 MiB with space reserved for the next chunk and free-space
  headroom. It is not a ring buffer: nothing is evicted to keep recording.
- A user tap from a visible unlocked activity starts a typed
  [microphone foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
  Denied/blocked notifications prevent starting. Losing visible controls,
  permission, input, or battery eligibility interrupts. Pause seals the
  prefix and records a gap; Stop ends the session. A stopped notification
  opens the app; **Resume needs explicit confirmation**. No mic start on boot,
  service recreation, unlock, charging, or opening a notification.
- The [charging scheduler](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
  uses ordinary, constrained WorkManager jobs (one source/chunk, no personal
  text in job data). No expedited/long-running work or separate ASR foreground
  service. Android 15's time-limited `mediaProcessing` FGS is not needed.
  WorkManager contributes scheduler/wake, network-state and reboot rescheduling
  permissions; these do **not** permit mic restart or Internet access. The
  app doesn't request a [Doze/battery optimization exemption](https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases).
- The candidate ASR is [sherpa-onnx 1.12.14](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.14)
  (Apache-2.0) + [multilingual Whisper Base int8](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/bb53ee204431c90d314c1cc08d28d23e5b7927cc)
  (OpenAI weights MIT; conversion repo has no separate card). Input is decoded
  in at most 20-second ASR slices. The app publishes one unreviewed passage
  for the complete chunk with producer/version and **chunk-level** bounds on
  the capture timeline, not word alignment. Long silences can lie inside those
  bounds. The optional [Qwen2.5 0.5B int8](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/tree/49bfd323c25c9dae2118906e1f3ffd16e0729b13)
  (Apache-2.0) selects a verbatim highlight or abstains.
- Each source has a random AES-256 data key wrapped by an unlocked-device
  Android Keystore key. While locked only the active writer retains its
  session key. Files have random IVs and GCM authenticates their source and
  record identity. Headers, gaps, audio, text and provenance are encrypted
  under `noBackupFilesDir`; opaque directory IDs, file counts and sizes are
  not hidden from a filesystem observer. M1 records are retired individually
  **after** committing and verifying a matching v2 source. Atomic revision
  publication precedes audio unlink; recovery finishes interrupted cleanup.
  Search/quotes bind to source ID, exact revision, segment ID and half-open
  Unicode-scalar range. Forget detaches the entire source directory before
  cleanup; late workers cannot recreate it. It is not forensic flash erasure.

## Before measuring battery

1. Install the app and instrumentation APK on a dedicated, screen-lock-enabled
   ARM64 Android 10+ handset. To test migration, first save **synthetic** M1
   examples and install M2 over M1 (`adb install -r`, same signing key).
   Check origins, original script, timestamps and IDs after restart. Never
   uninstall a real notebook to work around signing or Keystore failures.
2. With Wi-Fi off and airplane mode on, try missing, wrong and valid model
   imports. `./gradlew :app:connectedDebugAndroidTest` exercises native
   Keystore/permissions, bundled VAD and FLAC round trip (when a device is
   connected). With the text model installed, run the opt-in grounding probe:
   `adb shell am instrument -w -e modelSmoke true -e class dev.phonememory.DeviceSmoke dev.phonememory.test/androidx.test.runner.AndroidJUnitRunner`.
3. Check that both the initial Start and every Resume require an explicit
   tap/participant consent, and that permission grants alone do not start.
   Lock/background with Pause and Stop accessible. Test actual OxygenOS
   lock-screen privacy settings, blocked/dismissed notification, mic privacy
   switch, permission revocation, call/VoIP, headset/Bluetooth removal and
   competing recorders. If a lock screen hides controls and capture continues,
   do **not** sign off that OS configuration.
4. Pause and resume; check the gap and timestamps. Force-stop or kill mid-chunk
   with already sealed chunks, relaunch after unlock, and confirm the mic stays
   off. An abrupt kill cannot post a notification or persist its exact time:
   recover from the last durable boundary, not the next launch time. In-RAM
   speech since that boundary can be lost. Test screen-off idle/Doze and
   OxygenOS background-kill settings without exempting the app.
5. On battery, confirm pending seconds/bytes grow with speech but not silence.
   Fill the queue with no ASR model; expect a visible warning then a pause,
   not overwrite. Test low space, battery saver and the adjustable 15% guard;
   no auto-resume when the condition clears. While charging but locked or
   thermally constrained, pending audio stays queued. Unlock and check it
   drains. Test loss of the constraints during a job, cancellation of Process
   now, revision changes/Forget during processing, restart and backup/transfer
   exclusion with synthetic data.
6. Human-check soft and normal Hindi, English and mixed Hinglish with and
   without background noise. Compare a consented reference with the speech
   admitted by the gate; count missed utterances, clipped first/last words,
   changed names/numbers and script switches. Then inspect unreviewed spans,
   correction, quote invalidation and abstention. The earlier **desktop**
   smoke reproduced one synthetic English sentence, but misrecognized Hindi
   and mixed examples even with a Hindi hint. It is not a handset score.

## Matched power protocol

Use the **same test handset**, APK, model hashes, OS, RAM variant, brightness,
audio route, airplane-mode settings and starting charge range. Use repeatable
external synthetic playback at fixed distance/level, with known speech and
silence durations. Use at least three equal-length repeats per unplugged arm
(e.g. 60 minutes), allow cooling and vary arm order. No unrelated private
apps, personal recordings or content-bearing logs are needed.

| Arm | How to run | Record |
|---|---|---|
| Idle | App installed, mic off, screen locked; no USB power. | Elapsed hours, start/end charge, % and temperature. |
| Mic only | **Diagnostics: mic-only baseline**, confirm consent, lock for the same interval, Stop. This reads/discards the same mono input without VAD or audio writes. | Same counters, frame-derived capture hours. |
| Capture + gate + writes | **Start listening**, replay the same mix, lock, Stop without processing. | Same counters, retained speech minutes, pending bytes/chunks, interruptions and missed speech. |
| Charging throughput | Begin with the same known pending audio, plug in, unlock, keep saver off. Let normal jobs drain. | Audio minutes completed per elapsed hour, peak PSS, temperature/thermal pauses, remaining bytes and failures. Net charging percentage is **not** an energy estimate. |

Use **View local diagnostics** and the session details after Stop. Charge
counter units are µAh (`BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER`);
unavailable is not zero. On a dedicated test device, Android's
`adb shell dumpsys batterystats --reset` before a run and
`adb shell dumpsys batterystats dev.phonememory` after it provide a secondary
app-scoped check. Do not collect a whole-phone bugreport. Sample matching
idle endpoints with a charge counter or external meter, and avoid attaching
a charging USB cable during an unplugged run. Report `(start−end)/1000` mAh,
mAh/hour and percentage points/hour, and subtract idle mAh/hour for incremental
cost. Record the measurement method; if no trustworthy counter/meter is
available, report percentage only. Do not extrapolate a day of operation
without a sustained handset run and an agreed battery/quality budget.
