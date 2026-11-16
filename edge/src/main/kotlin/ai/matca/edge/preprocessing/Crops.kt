package ai.matca.edge.preprocessing

/** The largest centred square. */
internal fun centreSquareCrop(image: RgbImage): RgbImage {
    val side = minOf(image.width, image.height)
    return image.cropped((image.width - side) / 2, (image.height - side) / 2, side, side)
}

/** The middle share of the picture: both sides scaled by the factor, truncated, at least one pixel. */
internal fun cropByFactor(image: RgbImage, factor: Double): RgbImage {
    val width = maxOf(1, (image.width * factor).toInt())
    val height = maxOf(1, (image.height * factor).toInt())
    return image.cropped((image.width - width) / 2, (image.height - height) / 2, width, height)
}

/** Black outside the largest centred circle: an ellipse inscribed in the centred square, as Pillow draws it. */
internal fun circleMask(image: RgbImage): RgbImage {
    val side = minOf(image.width, image.height)
    val radius = (side - 1) / 2.0
    val centreX = (image.width - side) / 2 + radius
    val centreY = (image.height - side) / 2 + radius
    val out = image.pixels.copyOf()
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val dx = (x - centreX) / radius
            val dy = (y - centreY) / radius
            if (dx * dx + dy * dy > 1.0) {
                val at = (y * image.width + x) * RgbImage.CHANNELS
                out.fill(0, at, at + RgbImage.CHANNELS)
            }
        }
    }
    return RgbImage(image.width, image.height, out)
}
