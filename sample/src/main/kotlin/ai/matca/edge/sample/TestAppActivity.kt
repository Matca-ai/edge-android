package ai.matca.edge.sample

import ai.matca.edge.Answer
import ai.matca.edge.Classifier
import ai.matca.edge.EdgeConfiguration
import ai.matca.edge.create
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHOOSE_PICTURE = 1
private const val TAKE_PICTURE = 2
private const val SCREEN_PADDING_PIXELS = 32

/**
 * One screen to watch a release reach this phone with no app update: point it at an edge, check for updates,
 * answer a picture, then release something else in the console and check again. A pairing code from the console's
 * Test tab fills the fields itself, and the phone then answers the model's tries and sends what it answered.
 */
class TestAppActivity : Activity() {
    private val savedFields by lazy { getSharedPreferences("fields", MODE_PRIVATE) }
    private val edgeUrl by lazy { savedField("Edge URL") }
    private val deliveryKey by lazy { savedField("Delivery key") }
    private val rootPublicKey by lazy { savedField("Root public key") }
    private val modelName by lazy { savedField("Model name") }
    private val fields by lazy { listOf(edgeUrl, deliveryKey, rootPublicKey, modelName) }
    private val currentRelease by lazy { TextView(this) }
    private val answer by lazy { TextView(this) }
    private val messages by lazy { TextView(this) }

    // Why a check or a picture failed reaches the screen.
    private val screenScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error -> showMessage(error.message ?: error.javaClass.simpleName) },
    )
    private var classifier: Classifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(screen())
        showCurrentRelease()
        if (!pairFrom(intent)) withClassifier {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairFrom(intent)
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    override fun onDestroy() {
        screenScope.cancel()
        classifier?.close()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val picture = when {
            resultCode != RESULT_OK -> null
            requestCode == CHOOSE_PICTURE -> data?.data
            requestCode == TAKE_PICTURE -> TakenPictureProvider.URI
            else -> null
        } ?: return
        val name = if (requestCode == TAKE_PICTURE) "taken on the phone" else "chosen on the phone"
        withClassifier { classifier ->
            val bytes = readPicture(picture)
            val started = System.nanoTime()
            val result = classifier.classify(bytes)
            showAnswer(result)
            testPhone()?.let { phone -> send { phone.sendAnswer(null, name, result.release, scoresOf(result), millisSince(started)) } }
        }
    }

    /** The pairing link the install page opened, if this is one: its code spent for the four fields. */
    private fun pairFrom(intent: Intent?): Boolean {
        val link = PairingLink.from(intent?.data) ?: return false
        messages.text = "Pairing this phone…"
        screenScope.launch {
            val paired = withContext(Dispatchers.IO) { TestPhone.pair(link, appVersion()) }
            edgeUrl.setText(paired.edgeUrl)
            deliveryKey.setText(paired.key)
            rootPublicKey.setText(paired.rootPublicKey)
            modelName.setText(paired.model)
            saveFields()
            showMessage("Paired: this phone takes the release on the test channel.")
            withClassifier { classifier ->
                classifier.checkForUpdates()
                answerTries(classifier)
            }
        }
        return true
    }

    /** Every try picture this phone has not answered: fetched, answered on this phone's chip, the answer sent. */
    private suspend fun answerTries(classifier: Classifier) {
        val phone = testPhone() ?: return
        val open = withContext(Dispatchers.IO) { phone.openPictures() }
        open.forEach { picture ->
            val bytes = withContext(Dispatchers.IO) { phone.picture(picture) }
            val started = System.nanoTime()
            val result = classifier.classify(bytes)
            send { phone.sendAnswer(picture, "", result.release, scoresOf(result), millisSince(started)) }
        }
        showMessage(if (open.isEmpty()) "No try is waiting for this phone." else "Answered ${open.size} try pictures.")
    }

    /** The paired phone's calls, when the key in the fields is a paired one; an app's own key reads no tries. */
    private fun testPhone(): TestPhone? = fields.takeIf { list -> list.none { it.text.isBlank() } }?.let {
        TestPhone(edgeUrl.text.toString().trim().trimEnd('/'), deliveryKey.text.toString().trim(), modelName.text.toString().trim())
    }

    private suspend fun send(call: () -> Unit) = withContext(Dispatchers.IO) { call() }

    private fun scoresOf(result: Answer): Map<String, Double> = result.all.associate { it.label to it.confidence }

    private fun millisSince(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

    private fun appVersion(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION") // longVersionCode needs API 28; the sample runs from API 24
        return "${info.versionName} (${info.versionCode})"
    }

    private fun saveFields() {
        savedFields.edit().apply { fields.forEach { putString(it.hint.toString(), it.text.toString()) } }.apply()
    }

    private fun screen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SCREEN_PADDING_PIXELS, SCREEN_PADDING_PIXELS, SCREEN_PADDING_PIXELS, SCREEN_PADDING_PIXELS)
        }
        val buttons = listOf(
            button("Check for updates", ::checkForUpdates),
            button("Answer the tries", ::answerTriesNow),
            button("Choose picture", ::choosePicture),
            button("Take picture", ::takePicture),
        )
        (fields + buttons + listOf(currentRelease, answer, messages)).forEach(content::addView)
        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        }
    }

    private fun savedField(name: String) = EditText(this).apply {
        hint = name
        // Keeps the keyboard from learning the key or correcting a URL.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setText(savedFields.getString(name, ""))
    }

    private fun button(label: String, onPress: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onPress() }
    }

    private fun checkForUpdates() = withClassifier { classifier ->
        showMessage("Asking the edge…")
        classifier.checkForUpdates()
        showMessage("The check finished.")
    }

    private fun answerTriesNow() = withClassifier { classifier ->
        classifier.checkForUpdates()
        answerTries(classifier)
    }

    private fun choosePicture() = startForPicture(
        // JPEG only: the library reads EXIF from JPEG, so another format could be answered sideways with no warning.
        Intent(Intent.ACTION_GET_CONTENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE),
        CHOOSE_PICTURE,
    )

    private fun takePicture() = startForPicture(
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, TakenPictureProvider.URI)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        TAKE_PICTURE,
    )

    private fun startForPicture(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            messages.text = "No app on this phone can do that."
        }
    }

    /** Runs the work on the classifier for what the fields say now, and shows what came of it. */
    private fun withClassifier(work: suspend (Classifier) -> Unit) {
        answer.text = ""
        if (fields.any { it.text.isBlank() }) {
            messages.text = "Fill in all four fields."
            return
        }
        messages.text = ""
        val model = modelName.text.toString().trim()
        val configuration = EdgeConfiguration(
            baseUrl = edgeUrl.text.toString().trim(),
            deliveryKey = deliveryKey.text.toString().trim(),
            rootPublicKey = rootPublicKey.text.toString().trim(),
        )
        screenScope.launch {
            work(classifierFor(model, configuration))
            showCurrentRelease()
        }
    }

    /** A classifier cannot change its configuration, so a changed field needs a new one. */
    private fun classifierFor(model: String, configuration: EdgeConfiguration): Classifier {
        classifier?.let { if (it.model == model && it.configuration == configuration) return it }
        classifier?.close()
        return Classifier.create(this, model, configuration).also { classifier = it }
    }

    private suspend fun readPicture(picture: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bytes = checkNotNull(contentResolver.openInputStream(picture)) { "the picture cannot be read" }.use { it.readBytes() }
        TakenPictureProvider.takenPictureFile(this@TestAppActivity).delete()
        bytes
    }

    private fun showAnswer(result: Answer) {
        answer.text = buildString {
            appendLine("Answer: ${result.label} ${decimal(result.confidence)}, release ${result.release}")
            result.all.forEach { appendLine("${it.label}: ${decimal(it.confidence)}") }
        }
    }

    // The exception handler may run on any thread.
    private fun showMessage(text: String) = runOnUiThread {
        messages.append(text + "\n")
        showCurrentRelease()
    }

    private fun showCurrentRelease() {
        currentRelease.text = "Current release: ${classifier?.currentRelease ?: "none"}"
    }

    private fun decimal(confidence: Double) = "%.4f".format(Locale.ROOT, confidence)
}
