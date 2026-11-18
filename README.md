# Matca Edge for Android

Run your Matca image model inside your Android app. The model answers on the phone, with no network needed,
and new releases you make in the Matca console reach the app by themselves, with no app update.

## What it does

- **Classifies pictures on the phone**: JPEG bytes or a `Bitmap` in, a label and its confidence out.
- **Keeps the model up to date**: it checks for a new release at start and, once 6 hours have passed, on the next
  picture; it verifies the release's signature and takes it over on the picture after.
- **Gets the model on the first launch**: until the first release is on the phone it asks again within seconds, and
  `waitUntilReady()` tells your app when the model is there. A failed update never stops the model the phone already has.
- **Watches a camera feed**: turns a stream of frames into steady labels, and can wait for a stable picture,
  notice a black screen and only report confident answers that several frames agree on.
- **Works offline from the first launch**, when you ship a release inside the app.
- **Reports how each release does** on phones, within minutes, as counts only, never a picture. You can switch this off.

## Requirements

Android 7.0 (API 24) or newer, on arm64-v8a, armeabi-v7a or x86_64. Kotlin with coroutines. The model runs on
Google's LiteRT, which the library brings along.

## Install

The library is on Maven Central as `ai.matca:edge`. In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.matca:edge:1.2.0")
}
```

and in `gradle.properties` (LiteRT's two parts share one namespace, which Android Gradle plugin 9 otherwise refuses):

```properties
android.uniquePackageNames=false
```

## Use

You need three values from your model's **SDK** page in the Matca console: the edge address, a delivery key
(`mdk_…`) and the signing key.

### Classify a picture

```kotlin
import ai.matca.edge.Classifier
import ai.matca.edge.EdgeConfiguration
import ai.matca.edge.classify
import ai.matca.edge.create

val classifier = Classifier.create(
    context, "cap-detection",
    EdgeConfiguration(
        baseUrl = "https://assistant.matca.ai",
        deliveryKey = "mdk_…",
        rootPublicKey = "MFkw…",
    ),
)

val answer = classifier.classify(jpegBytes)   // or classify(bitmap)
println("${answer.label} ${answer.confidence}")   // With Cap 0.98
```

`classify` throws `EdgeError.NoModel` until the first release has reached the phone. Call `classifier.close()` when
you no longer need it.

### Wait for the model on the first launch

Right after install the model may still be downloading. Show that it is being prepared, then start:

```kotlin
showPreparing()                                   // e.g. "Preparing the model…"
try {
    classifier.waitUntilReady()                   // returns at once once the model is on the phone
} catch (error: EdgeError) {
    showError(error)                              // after 60 s: why no model came (no network, nothing released, …)
}
```

### Watch a camera feed

```kotlin
val watch = classifier.watch {
    waitForStablePicture = true
    detectBlackScreen = true
}

watch.states(frames).collect { state ->   // frames: Flow<Frame>, e.g. bitmap.toFrame()
    when (state) {
        WatchState.Ready -> Unit                       // warm-up done
        WatchState.BlackScreen -> showCameraBlocked()
        is WatchState.Label -> show(state.label, state.confidence)
    }
}
```

| `watch { }` setting | Default | What it does |
|---|---|---|
| `frameInterval` | 250 ms | Classifies at most one frame per interval |
| `waitForStablePicture` | `false` | Waits for a steady, bright picture before the first answer (at most 4 s) |
| `stablePictureFrames` | 12 | Steady frames in a row that settle the picture |
| `stablePictureMinimumBrightness` | 130.0 | Brightness (0–255) a steady frame needs; `0` if a dark picture is itself an answer |
| `stablePictureTimeout` | 4.seconds | How long the wait lasts at most before answering anyway |
| `detectBlackScreen` | `false` | Reports `BlackScreen` and stops when the picture goes black |
| `ignoreFirstAnswers` | 4 | Skips the answers while the camera settles |
| `confidenceGate` | 0.95 | Drops answers below it |
| `agreement` / `agreementAtStart` | 3 / 4 | Answers in a row a label needs before it is reported |

### Options

| `EdgeConfiguration` | Default | What it does |
|---|---|---|
| `expectedLabels` | empty | Refuses a release that lacks one of these labels |
| `bundledRelease` | `null` | A release shipped in the app, used before any network |
| `wifiOnly` | `false` | Downloads releases on Wi-Fi only |
| `checkInterval` | 6 hours | How often it checks for a new release |
| `monitoring` | `true` | Sends each release's counts while the app classifies; `false` sends nothing |

`classifier.checkForUpdates()` checks right away and throws an `EdgeError` that says why a release was not taken.
Catch it: an uncaught one in a bare `launch { }` crashes the app when the phone is offline. The current release is
`classifier.currentRelease`.

## Privacy

- Pictures never leave the phone.
- The library sends only the release check, the downloads and, unless `monitoring` is `false`, reports of
  counts per release and day: answers, confidence, speed and crashes. They go after each release check and every
  15 minutes while the app classifies. No device id, no user data, no location.
- **Google Play's Data safety form**, with monitoring on: under *App info and performance* declare *Crash logs*,
  *Diagnostics* and *Other app performance data* as collected, for analytics, not shared, encrypted in transit.
  With monitoring off there is nothing to declare for this library.
- With monitoring on, your app needs its own legal basis for sending the reports (ePrivacy Directive, Article 5(3)).

## Try it

`sample` is a one-screen test app. With a phone on USB debugging, run `./gradlew :sample:installDebug` and fill in
the edge address, delivery key, signing key and model name, or pair it from the model's **Test** tab in the console.

## License

Apache-2.0
