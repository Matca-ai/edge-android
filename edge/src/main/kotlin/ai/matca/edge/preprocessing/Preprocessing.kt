package ai.matca.edge.preprocessing

import ai.matca.edge.EdgeError

/** The steps a release records, in the order they run; each is a switch the app may override. */
internal data class PreprocessingSteps(
    val fixOrientation: Boolean = true,
    val centreSquareCrop: Boolean = true,
    val cropFactor: Double? = null,
    val circleMask: Boolean = false,
    val illumination: Illumination? = null,
) {
    /** The picture as the model takes it: the steps that are on, then the resize to the model's input. */
    fun prepare(image: RgbImage, orientation: Int, width: Int, height: Int): RgbImage {
        requireInRange()
        var picture = image
        if (fixOrientation) picture = fixOrientation(picture, orientation)
        if (centreSquareCrop) picture = centreSquareCrop(picture)
        cropFactor?.let { picture = cropByFactor(picture, it) }
        if (circleMask) picture = circleMask(picture)
        illumination?.let { picture = illuminationNormalised(picture, it) }
        return resizeBilinear(picture, width, height)
    }

    /** An override comes from the app and is not checked by the control plane, as a release's steps are. */
    private fun requireInRange() {
        cropFactor?.let { if (!(it > 0 && it <= 1)) throw EdgeError.InvalidPreprocessing("crop_factor") }
        illumination?.let {
            val percentiles = it.percentileLow >= 0 && it.percentileLow < it.percentileHigh && it.percentileHigh <= 100
            if (!percentiles || !(it.sigmaPercent > 0 && it.sigmaPercent <= 100)) throw EdgeError.InvalidPreprocessing("illumination")
        }
    }

    /** The steps with the app's overrides applied, and the names of the steps the app changed. */
    fun overridden(overrides: PreprocessingOverrides): Pair<PreprocessingSteps, List<String>> {
        val changed = mutableListOf<String>()
        fun <T> choose(name: String, current: T, override: StepOverride<T>, off: T): T = when (override) {
            StepOverride.Keep -> current
            StepOverride.Off -> off.also { if (current != off) changed += name }
            is StepOverride.On -> override.settings.also { if (current != it) changed += name }
        }
        val steps = PreprocessingSteps(
            fixOrientation = choose("fix_orientation", fixOrientation, overrides.fixOrientation, false),
            centreSquareCrop = choose("centre_square_crop", centreSquareCrop, overrides.centreSquareCrop, false),
            cropFactor = choose("crop_factor", cropFactor, overrides.cropFactor, null),
            circleMask = choose("circle_mask", circleMask, overrides.circleMask, false),
            illumination = choose("illumination", illumination, overrides.illumination, null),
        )
        return steps to changed
    }

    companion object {
        /** The features this library has; a release that requires another needs a newer library. */
        val FEATURES = setOf("fix_orientation", "centre_square_crop", "crop_factor", "circle_mask", "illumination")
    }
}

/** What the app says about one step: leave it as the release has it, switch it off, or switch it on. */
public sealed interface StepOverride<out T> {
    public data object Keep : StepOverride<Nothing>
    public data object Off : StepOverride<Nothing>
    public data class On<T>(val settings: T) : StepOverride<T>
}

/** The app's own switches over the release's steps; the library logs each one it applies. */
public data class PreprocessingOverrides(
    val fixOrientation: StepOverride<Boolean> = StepOverride.Keep,
    val centreSquareCrop: StepOverride<Boolean> = StepOverride.Keep,
    val cropFactor: StepOverride<Double> = StepOverride.Keep,
    val circleMask: StepOverride<Boolean> = StepOverride.Keep,
    val illumination: StepOverride<Illumination> = StepOverride.Keep,
)
