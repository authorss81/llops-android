package com.authorss81.noteflow.services

import java.io.File

/**
 * Phase 270: confines verified-artifact payload extraction under the plugin's
 * app-private payload root.
 *
 * A crafted artifact entry such as `assets/mlkit-google-ocr-models/../../evil`
 * passes the reserved-prefix allow-list yet resolves OUTSIDE the payload root
 * when joined with `File(root, target)`. Every candidate target must therefore
 * pass the same canonical-containment gate the download path already uses
 * (`PluginDownloader`: `parentFile?.canonicalPath != targetDir.canonicalPath`
 * for the flat artifact file — here a strict strict-descendant check because
 * payloads legitimately nest under `lib/<abi>/` + `assets/`).
 *
 * Pure JVM (unit-tested without Robolectric or a Context).
 */
object PluginPayloadPathPolicy {

    /**
     * Resolve [targetName] (a forward-slash relative path derived from the
     * artifact entry) under [root], or null when it escapes the root (zip-slip
     * `..`, absolute path, symlink resolution outside the root). Callers skip
     * null targets — the entry is ignored, never written.
     */
    fun resolveTarget(root: File, targetName: String): File? {
        // Legitimate entries are flat `lib/<abi>/<file>` + reserved `assets/…`
        // paths — they never contain a `..` segment. Rejecting `..` outright
        // (not just escapes past the root) also blocks in-root relocations
        // such as `assets/<reserved>/../../.payload-<hash>` forging the
        // extraction marker. The canonical check below stays as
        // defense-in-depth (symlinks, absolute-path edge cases).
        if (targetName.split('/').any { it == ".." }) return null
        return try {
            val rootCanonical = root.canonicalPath
            val out = File(root, targetName)
            val outCanonical = out.canonicalPath
            if (outCanonical != rootCanonical &&
                outCanonical.startsWith(rootCanonical + File.separator)
            ) {
                out
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
