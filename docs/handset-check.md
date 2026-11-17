# First handset check

## What is actually in the APK

| Path | Runtime / candidate | Weights, not inferred RAM |
|---|---|---|
| Speech draft | [sherpa-onnx 1.12.14](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.14), ARM64 CPU, greedy, two threads. [Whisper Base multilingual int8 ONNX](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/bb53ee204431c90d314c1cc08d28d23e5b7927cc), *transcribe*, not translate. Auto detects one language for the clip; alternatively the user can hint Hindi or English. | Encoder + decoder + tokens: 160,609,290 bytes |
| Source highlight | [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) 0.10.27, ARM64 CPU. [Qwen2.5-0.5B-Instruct dynamic-int8 `.task`](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/tree/49bfd323c25c9dae2118906e1f3ffd16e0729b13). This multilingual candidate selects a passage number or abstains. | 546,660,344 bytes |

These are feasibility candidates, not a final model selection. No AICore,
cloud inference, automatic model updates or unrestricted access to other
apps. Exact revisions, files and checksums are in
[`models.json`](../app/src/main/assets/models.json). The importer verifies
these locally. Incomplete copies cannot be loaded.

[Whisper code and model weights](https://github.com/openai/whisper#license)
are MIT; the ONNX conversion repository does not publish a separate model
card. [Qwen's original model](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct)
and the linked LiteRT conversion identify Apache-2.0. sherpa-onnx and
MediaPipe are Apache-2.0; ONNX Runtime is MIT. Keep upstream notices with
copies. Weights are not committed or included in the APK.

## Evidence so far

- Built the ARM64 debug app and its instrumentation APK. The JVM suite covers
  import limits/encoding, encrypted persistence and tamper rejection,
  Unicode retrieval, citations, deletion, stale-result rejection, interrupted
  writes and model verification. Android lint runs separately.
- **Actual desktop speech run:** the matching sherpa-onnx 1.12.14 CPU CLI
  loaded the three pinned files. One Windows-synthesized English sentence
  (“The yellow notebook is beside the window.”) was reproduced. For an
  eSpeak NG 1.52.0 Hindi voice, auto-detection chose Latin for a Hindi clip
  and English for a mixed clip. The Hindi words were misrecognized; part of
  the English clause survived. Forcing `hi` did not repair the two clips.
  Artificial pronunciation and two examples are not a handset quality score.
- The Qwen file's size and digest were verified and it contains the LiteRT
  graph, tokenizer and chat metadata. **A valid bundle is not a successful
  generation.** Neither Android native inference path, mic lifecycle nor
  Keystore has been exercised on an 8T in this milestone.

## Run on a test OnePlus 8T

Use synthetic or explicitly consented inputs. Record only the OS/API level,
RAM variant, build/model pins and aggregate findings. Do not collect mail,
messages, device identifiers, private recordings, logs containing content,
or a phone-wide bug report. A target device is necessary: Snapdragon 865
and an unspecified 8/12 GB variant are not performance measurements.

1. Install the APK and models following the README. Disconnect USB data if
   desired; turn on airplane mode and turn Wi-Fi off. With models **absent**,
   verify notes/search/deletion and the explicit setup buttons. Try the wrong
   file, duplicate files, insufficient space, then a valid install.
2. Make a note and import a different UTF-8 note. Restart; open a hit, check
   its line against the saved source, generate a highlight and inspect the
   quote. If the engine returns unrelated prose or a bad number, expect **no
   grounded highlight**. Test Hindi, English and mixed notes independently.
3. Speak separate short English, Hindi and code-switched clips. Example test
   scripts: “The yellow notebook is beside the window”, “नीली किताब मेज़ पर है”,
   and “कल की meeting Friday को है। Bring the blue notebook.” Compare Auto
   and Hindi hints. Mark omitted/changed names, dates, numbers, script
   changes and words on either side of a switch. Don't infer quality from
   language detection or one correct sentence. If recall-critical words are
   repeatedly lost, compare a larger multilingual or code-switch-trained
   candidate before choosing a release model.
4. Deny/revoke the mic grant. While capturing, lock the screen, press Home,
   pull down the notification shade, contend for the mic, and force-stop the
   app. The mic should stop, drafts should not auto-save, and the next launch
   must not resume recording. Also check the 25-second cap and silent input.
5. Forget a source while its highlight is running, then wait and restart.
   The source, search hit and quote must stay gone. Confirm that forgetting
   an import leaves the picker original intact. Check restore/device transfer
   with **synthetic** data only: there should be no recoverable notebook.
6. Measure cold model load + inference time and repeated runs separately,
   audio duration, time to draft, valid-citation/abstention count, peak app
   PSS sampled through each run (`adb shell dumpsys meminfo dev.phonememory`),
   thermal status and battery energy over repeated matched-duration runs vs
   an idle-screen control. Keep screen brightness and charging state fixed.
   No latency, battery-life or memory targets have been demonstrated yet.

On a **dedicated test device**, `./gradlew :app:connectedDebugAndroidTest`
checks Android Keystore and manifest restrictions. After installing both the
app and test APK and importing the text model, run this optional **real CPU**
model probe; it fails if no valid grounded selection is returned:

```shell
adb shell am instrument -w -e modelSmoke true -e class dev.phonememory.DeviceSmoke dev.phonememory.test/androidx.test.runner.AndroidJUnitRunner
```

## Storage and deletion boundary

Each immutable source is AES-256-GCM encrypted with an Android Keystore key,
a fresh IV, and record ID/version authenticated as associated data. Origin,
timestamp and text are inside the ciphertext in credential-protected
`noBackupFilesDir`. New records are published by atomic rename; interrupted
partials are discarded, not replayed. A missing key or failed authentication
doesn't reset a nonempty notebook. There is no plaintext database, disk
search index, saved chat history or stored highlight. Capture buffers and
unreviewed results stay in RAM. Synchronous native work may take time to
return after cancellation, but it cannot write a source or publish a stale
result. Only a user save creates a memory.

Backup is disabled and cloud/device-transfer exclusions are explicit. The
app uses a secure window, asks IMEs not to learn text, and does not request
persistable URI grants or log personal content. Use a trusted offline
keyboard and choose local files; a third-party keyboard or file provider is
outside the app's boundary. Forget is logical deletion of the app's copy,
not forensic erasure of flash, other apps' files, or a defense against a
rooted device, a debugger or an already-unlocked phone.
