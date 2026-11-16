# ai.matca:edge

The Android library of the Matca platform's device models (`docs/edge-library-contract.md`): one
`Classifier` per model that answers pictures on the phone with the newest verified release and keeps
itself up to date from the edge, and a `Watch` that turns a camera or device feed into labels.

```kotlin
val classifier = Classifier.create(context, "cap-detection", EdgeConfiguration(
    baseUrl = "https://edge.example.eu", deliveryKey = "mdk_…", rootPublicKey = "MFkw…", expectedLabels = listOf("With Cap"),
))
val answer = classifier.classify(jpegBytes)          // or classify(bitmap)
classifier.watch { waitForStablePicture = true }.states(frames).collect { state -> show(state) }
```

`EdgeConfiguration(monitoring = false)` switches off the daily counts of each release (see Monitoring below).
`classify` throws `EdgeError.NoModel` until a release reached the phone; a bundled release
(`EdgeConfiguration.bundledRelease`, a directory with `manifest.json` as the edge answers it, the files at their
listed paths and `reference/<path>`) is taken before any network, and a classification waits for it. `classifier.preprocessing` overrides a release's picture
steps for the app; the library then warns once per step that pictures differ from what the model learned with.

`checkForUpdates()` throws why a release was not taken as an `EdgeError`, the current model still working. Kotlin
does not force you to handle it: a bare `launch { checkForUpdates() }` with no handler crashes the app when the phone
is offline, so catch it or give the scope a `CoroutineExceptionHandler`. The ask at creation and the periodic ask throw
nothing: their reasons go to the `java.util.logging` logger `ai.matca.edge`, without the delivery key. A frame that gets
no answer, `NoModel` included, is dropped and logged and a watch goes on. `close()` ends the classifier and never the
scope given to `create`. Every suspending call does its work off the caller's thread, and a model name that is not a
DNS label is refused with `EdgeError.InvalidModelName`.

## Monitoring

Unless the app sets `monitoring = false`, the classifier counts how each release does on the phone, per release and
UTC day (`docs/edge-library-contract.md`, section 11): answers at or above the 0.95 gate and below it, failed
classifications, a watch's waits for a stable picture that ended on their timeout and its black screens, the answers'
confidence and top labels, the preprocessing and model time in milliseconds in fixed steps, the classifier sessions
with the release loaded, and the app's crashes during them. Counting adds to numbers in memory and nothing else: the
counts are written to `monitoring.json` beside the releases at most once a minute, by a coroutine the next
classification after the minute starts, and before each check. After each manifest ask the edge answered, a coroutine
posts every finished day it holds, at most 14 per check, to `POST /v1/models/{model}/releases/{n}/report`; a day is
never sent before it has ended and is dropped unsent after 7 days. Crashes are read at the next creation from Android's
`ApplicationExitInfo` (Android 11 and later; earlier systems report sessions and no crashes) and counted against the
release the crashed process ran, which `session.json` records. `monitoring = false` counts and sends nothing and deletes
`monitoring.json` and `session.json`.

A report, the whole of it:

```json
{"format":1,"day":"2026-11-03","platform":"android","os":"13","library":"0.2.0",
 "outcomes":{"answered":412,"below_gate":51,"failed":1,"no_stable_picture":18,"black_screen":4},
 "confidence":[1,2,3,4,5,8,12,16,38,49,71,254],"labels":{"With Cap":251,"Without Cap":212},
 "speed_ms":{"preprocessing":[60,201,140,52,9,1,0,0,0,0,0,0],"model":[0,12,88,201,120,30,9,3,0,0,0,0]},
 "sessions":38,"crashes":0}
```

## Privacy

Nothing leaves the phone but the manifest ask, the downloads of a release's files, two counts (that a release took
over, and that a release failed its reference check) and, unless the app switches monitoring off, the daily reports
above. Every call carries the delivery key and `User-Agent: matca-edge-android/<version>`, nothing that identifies the
phone or its user. A report holds counts and the system's major version only: no picture or anything drawn from one, no
device or advertising id, nothing of the user, no location, not the rollout number. Pictures are read only when the app
hands them over and are never sent anywhere. The rollout number is drawn on the device and stays there. Files live
under the app's no-backup directory.

**An app that leaves monitoring on needs its own legal basis for it.** The EDPB's Guidelines 2/2023 read Article 5(3) of
the ePrivacy Directive as covering any information a phone sends, personal or not, and the exemption for what is
strictly necessary is narrow. Whether the app may send the reports, and on what basis, is the app's decision; Matca
processes them for it. An app that has none sets `monitoring = false`.

**Google Play's Data safety form changes when monitoring is on.** Play counts what a library sends off the phone as the
app's own collection, and names crash counts and latency as its examples of "App info and performance". An app that
leaves monitoring on declares, under App info and performance, **Crash logs** (the crash counts), **Diagnostics** (the
preprocessing and model times) and **Other app performance data** (the outcome, confidence and label counts) as
collected, for analytics, not shared (Matca processes them for the app as its service provider), encrypted in transit
when the edge's address is HTTPS, and required unless the app gives its users the switch. Nothing in a report falls under
any other category of the form. With `monitoring = false` no report is sent, and these three entries fall away.

## Build

`./gradlew test` runs the JVM unit tests, `./gradlew assembleRelease` builds `edge/build/outputs/aar/edge-release.aar`.
The project uses its own wrapper (Gradle 9.7.0) and needs `JAVA_HOME` on a JDK 17 or newer and `ANDROID_HOME`
(compile SDK 36). Versions, chosen under the 90-day rule of `docs/engineering-standards.md` on 1 November 2026:

| Component | Version | Published | Where it is pinned |
|---|---|---|---|
| Android Gradle plugin | 9.3.0 | 14 July 2026 (`gradle-9.3.0.pom` on maven.google.com) | `build.gradle.kts` |
| Kotlin Gradle plugin, built-in Kotlin of AGP 9 | 2.4.0 | 3 June 2026 (Maven Central directory listing) | `build.gradle.kts`, declared `apply false` so the build classpath carries 2.4.0 instead of the 2.2.10 AGP depends on; `./gradlew buildEnvironment` shows `kotlin-gradle-plugin:2.2.10 -> 2.4.0` |
| LiteRT | 2.1.6 | 1 July 2026 | `edge/build.gradle.kts` |
| kotlinx-coroutines-android | 1.10.2 | 8 April 2025 | `edge/build.gradle.kts` |
| JUnit | 4.13.2 | February 2021 | `edge/build.gradle.kts`, tests only |
| org.json:json | 20251224 | 24 December 2025 (`json-20251224.pom` on Maven Central); OSV lists nothing for it | `edge/build.gradle.kts`, tests only: Android has `org.json` at every API level, the JVM does not |
| androidx.test:runner | 1.7.0 | 30 July 2025 (`runner-1.7.0.pom` on dl.google.com) | `edge/build.gradle.kts`, device tests only; brings `monitor` 1.8.0 and `services:storage` 1.6.0 of the same day |

LiteRT 2.1.6 brings its own dependencies along (`litert-api`, which depends on androidx.lifecycle, Guava,
kotlinx-coroutines-guava and Play's `ai-delivery`); the library declares none of them.

**An app that uses the library needs `android.uniquePackageNames=false` in its `gradle.properties`.** `litert` and
`litert-api` 2.1.6 share one namespace, which AGP 9.3 refuses when it builds an app, and AGP's own option list
schedules that check as enforced from 10.0.

## Test app

`sample` is a one-screen app, never in a store, to watch a release reach a phone with no app update. With the
phone on USB debugging, `./gradlew :sample:installDebug`, then fill in (kept between launches, in plain
`SharedPreferences`, because a delivery key is read-only and this is a test app):

| Field | What goes in it |
|---|---|
| Edge URL | The edge's public address, `https://…`; Android refuses plain HTTP |
| Delivery key | `mdk_…`, from "New delivery key" on the model's SDK page in the console |
| Root public key | The base64 key under "Signing key" on the same page; when that card says the apps carry the root, the root key instead |
| Model name | The model's name in the console, such as `cap-detection` |

**Paired from the console** (docs/on-device-models-plan.md, section 5): the Test tab's Android pairing code opens
the edge's install page, which downloads the signed build the `android` workflow publishes on develop, then opens
`matcaedge://pair?…`; the app spends the code for a key on the test channel and fills the four fields itself.
"Answer the tries" (also done right after pairing) fetches every try picture this phone has not answered, answers
it with the release it holds and sends the answer with its time; a picture taken or chosen here sends its answer
too, never the picture.

"Check for updates" asks the edge, "Choose picture" and "Take picture" answer a JPEG with its EXIF kept. The screen
shows the current release, the answer with every score, and what the last press threw, which says why a release
was not taken. To try a release: add images in the console, retrain, release, press "Check for
updates" and see the current release change; to move back, release the older one and press it again.

## Device tests

`./gradlew :edge:connectedAndroidTest`, with a phone or emulator attached (arm64-v8a, armeabi-v7a or x86_64: LiteRT
ships no other). `PlatformAdaptersTest` runs what the JVM cannot: the real tiny model of `fixtures/tiny-model`
through `LiteRtModelLoader` against the trainer's answers in `expected.json`, `BitmapJpegDecoder` on the sideways
JPEG through the library's orientation step against the trainer's PNG, and `Bitmap.toFrame`. They read the JVM
tests' fixtures as assets, with no second copy. They compile, and have not run yet: the machine they were written on
has no emulator image or phone.

## Parity with the trainer

`PreprocessingParityTest` runs every fixture of `sdks/edge-fixtures` through the library's picture steps
(`edge/src/main/kotlin/ai/matca/edge/preprocessing`) and compares the 224×224 result with the trainer's PNG.
Mean absolute difference per channel, measured on 1 November 2026 (tolerance 3.0; 6.0 for illumination):

| Fixture | default | no-orientation | crop-0.8 | circle | illumination |
|---|---|---|---|---|---|
| cap-upright | 0.000 | 0.000 | 0.005 | 0.081 | 0.000 |
| cap-sideways | 0.000 | 0.000 | 0.005 | 0.080 | 0.000 |
| cap-second | 0.000 | 0.000 | 0.004 | 0.501 | 0.000 |

The resize reproduces Pillow's bilinear resampling (a triangle filter whose support grows with the shrink
factor, one axis at a time with 8-bit rounding between the passes), so a phone that shrinks a large frame
averages as the trainer did. The circle mask rasterises the inscribed ellipse by pixel centres, which differs
from Pillow's edge pixels by the amounts above.

## Tests

`./gradlew test` runs the JVM unit tests, no device: the signature and key trust over the control plane's own
fixture (`SigningTest`), the manifest reader (`ManifestTest`), the picture steps against the fixtures and Pillow's
answers (`PreprocessingParityTest`), the steps of taking a release with a fake edge and a fake model
(`ClassifierUpdateTest`), failures that stay inside and what `checkForUpdates` throws (`ClassifierFailureTest`),
closing and overlapping work (`ClassifierLifetimeTest`), the delivery API against a `com.sun.net.httpserver` fake edge
(`HttpDeliveryTest`), the watch's stability, black screen, warm-up, gate and agreement (`WatchTest`), and monitoring:
a scripted day of 38 sessions whose report equals `fixtures/report.json` and 100,000 counts in under 50 ms
(`MonitoringReportTest`, about 2 ms measured on 3 November 2026), when a finished day is sent, dropped or kept, the
minute's write, an unreadable `monitoring.json` and the switch (`MonitoringSendingTest`), and a crash counted once
against its session's release and a report the edge never answers slowing no classification and no check
(`MonitoringSessionTest`).

## Not covered

- LiteRT itself and `BitmapFactory` and `Bitmap` decoding (`Bitmaps.kt`) run only on a device: the device tests
  cover them but have not run yet, so a real `.tflite` through the library is still unproven.
- `WifiOnlyPolicy` itself, which reads the phone's network, has no test; what a check does with its answer has. The connect and read timeouts have none either: a test would have to wait for them. The full path on a phone (`Classifier.create`, a real
  edge, the eight steps) has none either: the test app is how a person walks it, and it has not been launched yet.
- Camera `ImageProxy` frames: the app converts them to a `Bitmap` in this version.
- What LiteRT brings into an app's manifest, through Play's `ai-delivery` and WorkManager, which the library never
  starts and has not been proven to work without, since excluding them needs a device. Permissions:
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`. Exported components,
  each disabled or guarded by a system permission: `AssetPackExtractionService` (disabled),
  `SessionStateBroadcastReceiver` (`INSTALL_PACKAGES`), `SystemJobService` (`BIND_JOB_SERVICE`), `DiagnosticsReceiver`
  and `ProfileInstallReceiver` (`DUMP`). The test app removes those five with `tools:node="remove"`; an app can too.
- The periodic ask is triggered from `classify` once `checkInterval` has passed, not by a timer; an app that
  never classifies asks only at creation and on `checkForUpdates()`. The daily reports ride on those asks.
- `ApplicationExitHistory`, the reader of `ApplicationExitInfo`, runs only on a device: a device test calls it, not yet
  run; the JVM tests cover what is done with the records. A process killed between two writes of `monitoring.json`
  loses at most a minute of counts, and one killed after a report was taken but before the next write sends that day
  again.
