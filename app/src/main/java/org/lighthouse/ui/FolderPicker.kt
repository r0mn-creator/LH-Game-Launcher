package org.lighthouse.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.lighthouse.data.PlatformProfile
import org.lighthouse.data.ProfileStore

/**
 * Adding a ROM folder.
 *
 * SAF grants do NOT transfer between apps and do not survive a reinstall, so
 * LightHouse has to take its own persistable permission for every tree. The
 * Beacon export contains perfectly valid tree URIs that LightHouse still cannot
 * read for exactly this reason - the URI is right, the grant is missing. That
 * is why importing a library still needs the user to re-pick each folder once,
 * and why the UI has to say so instead of showing an empty section.
 */
object FolderPicker {

    fun intent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

    /**
     * Persist the grant and add the tree to a profile.
     * @return the updated profile, or null if the grant could not be taken.
     */
    fun accept(
        context: Context,
        store: ProfileStore,
        profile: PlatformProfile,
        tree: Uri,
    ): PlatformProfile? {
        val ok = runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (!ok) return null

        val roots = (profile.source.roots + tree.toString()).distinct()
        val updated = profile.copy(source = profile.source.copy(roots = roots))
        store.save(updated)
        return updated
    }

    /** Trees LightHouse currently holds a grant for - shown in Settings. */
    fun granted(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
}
