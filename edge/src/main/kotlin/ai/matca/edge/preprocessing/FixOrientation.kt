package ai.matca.edge.preprocessing

/** The picture upright by its EXIF orientation, 1 to 8, as Pillow's `exif_transpose` turns it. */
internal fun fixOrientation(image: RgbImage, orientation: Int): RgbImage {
    val w = image.width
    val h = image.height
    return when (orientation) {
        2 -> image.mapped(w, h) { x, y -> (w - 1 - x) to y }
        3 -> image.mapped(w, h) { x, y -> (w - 1 - x) to (h - 1 - y) }
        4 -> image.mapped(w, h) { x, y -> x to (h - 1 - y) }
        5 -> image.mapped(h, w) { x, y -> y to x }
        6 -> image.mapped(h, w) { x, y -> y to (h - 1 - x) }
        7 -> image.mapped(h, w) { x, y -> (w - 1 - y) to (h - 1 - x) }
        8 -> image.mapped(h, w) { x, y -> (w - 1 - y) to x }
        else -> image
    }
}
