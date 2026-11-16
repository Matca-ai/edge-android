package ai.matca.edge.sample

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * The one file the camera app writes the taken picture to. Without a place to write, a camera answers only a
 * small thumbnail with no EXIF; AndroidX's `FileProvider` would do this, but the test app adds no dependency.
 * Not exported: only the one-time grant on the camera intent reaches it, and it serves no other path.
 */
class TakenPictureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(takenPictureFile(requireNotNull(context)), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        val URI: Uri = Uri.parse("content://ai.matca.edge.sample.taken-picture/picture.jpg")

        fun takenPictureFile(context: Context) = File(context.cacheDir, "taken-picture.jpg")
    }
}
