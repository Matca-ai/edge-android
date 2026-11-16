package ai.matca.edge.preprocessing

private const val MARKER_PREFIX = 0xFF
private const val START_OF_IMAGE = 0xD8
private const val START_OF_SCAN = 0xDA
private const val APP1 = 0xE1
private const val ORIENTATION_TAG = 0x0112
private const val ENTRY_SIZE = 12
private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

/**
 * The EXIF orientation of a JPEG, 1 to 8, read from its APP1 segment; 1 when there is none or the
 * bytes do not say. Every offset is checked, because the bytes come from outside the app.
 */
internal fun exifOrientation(jpeg: ByteArray): Int {
    if (jpeg.size < 4 || unsigned(jpeg, 0) != MARKER_PREFIX || unsigned(jpeg, 1) != START_OF_IMAGE) return 1
    var at = 2
    while (at + 4 <= jpeg.size && unsigned(jpeg, at) == MARKER_PREFIX) {
        val marker = unsigned(jpeg, at + 1)
        val length = readUnsigned16(jpeg, at + 2, bigEndian = true)
        if (marker == START_OF_SCAN || length < 2) return 1
        if (marker == APP1 && hasExifHeader(jpeg, at + 4)) return orientationOfExif(jpeg, at + 4, length - 2)
        at += 2 + length
    }
    return 1
}

// An APP1 segment may hold XMP instead, and then the EXIF segment comes after it.
private fun hasExifHeader(jpeg: ByteArray, start: Int): Boolean =
    start + EXIF_HEADER.size <= jpeg.size && EXIF_HEADER.contentEquals(jpeg.copyOfRange(start, start + EXIF_HEADER.size))

private fun orientationOfExif(jpeg: ByteArray, start: Int, length: Int): Int {
    val end = minOf(start + length, jpeg.size)
    val tiff = start + EXIF_HEADER.size
    if (tiff + 8 > end) return 1
    val bigEndian = when (unsigned(jpeg, tiff)) {
        0x4D -> true
        0x49 -> false
        else -> return 1
    }
    val directoryOffset = readUnsigned32(jpeg, tiff + 4, bigEndian)
    if (directoryOffset > end - tiff) return 1
    val directory = tiff + directoryOffset.toInt()
    if (directory + 2 > end) return 1
    val entries = readUnsigned16(jpeg, directory, bigEndian)
    for (index in 0 until entries) {
        val entry = directory + 2 + index * ENTRY_SIZE
        if (entry + ENTRY_SIZE > end) return 1
        if (readUnsigned16(jpeg, entry, bigEndian) == ORIENTATION_TAG) {
            return readUnsigned16(jpeg, entry + 8, bigEndian).takeIf { it in 1..8 } ?: 1
        }
    }
    return 1
}

private fun unsigned(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

private fun readUnsigned16(bytes: ByteArray, at: Int, bigEndian: Boolean): Int =
    if (bigEndian) (unsigned(bytes, at) shl 8) or unsigned(bytes, at + 1)
    else (unsigned(bytes, at + 1) shl 8) or unsigned(bytes, at)

private fun readUnsigned32(bytes: ByteArray, at: Int, bigEndian: Boolean): Long {
    val high = readUnsigned16(bytes, if (bigEndian) at else at + 2, bigEndian).toLong()
    val low = readUnsigned16(bytes, if (bigEndian) at + 2 else at, bigEndian).toLong()
    return (high shl 16) or low
}
