package com.samyak.television.data

import com.samyak.playlist.M3uParser
import com.samyak.playlist.PlaylistDownload
import com.samyak.playlist.PlaylistStore
import com.samyak.playlist.PlaylistVersion
import com.samyak.playlist.PlaylistFingerprint
import com.samyak.television.model.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.URL

object ChannelsLoader {
    private val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".mpg", ".mpeg", ".3gp", ".ogv", ".rm", ".rmvb")

    /** True only when a complete replacement was published. */
    suspend fun importChannels(url: String, providerTitle: String?, store: PlaylistStore<Channel>, cacheDir: File): Boolean {
        val scheme = URL(url).protocol
        if (scheme != "http" && scheme != "https") throw IOException("Unsupported playlist scheme")
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val previous = store.version(url)
        if (videoExtensions.any { path.endsWith(it) }) {
            val context = currentCoroutineContext()
            return replaceIfChanged(store, previous, null, { context.ensureActive() }) { emit ->
                emit(Channel(providerTitle ?: path.substringAfterLast('/').substringBeforeLast('.'), "", url, category = "Direct Videos"))
            }
        }
        return PlaylistDownload.checkFile(cacheDir, url, previous.fingerprint) { file, fingerprint, checkCancelled ->
            if (file == null || fingerprint.sha256 == previous.fingerprint?.sha256) {
                store.markUnchanged(previous, fingerprint, checkCancelled)
                return@checkFile false
            }
            replaceIfChanged(store, previous, fingerprint, checkCancelled) { emit ->
                M3uParser.parse(file.inputStream(), requireName = false, checkCancelled = checkCancelled) { record ->
                    emit(Channel(record.name, record.logoUrl, record.streamUrl, category = record.category,
                        tvgId = record.tvgId, tvgName = record.tvgName, playlistMetadata = record.metadata))
                }
            }
        }
    }

    private fun replaceIfChanged(
        store: PlaylistStore<Channel>, previous: PlaylistVersion, fingerprint: PlaylistFingerprint?,
        checkCancelled: () -> Unit, read: ((Channel) -> Unit) -> Unit
    ): Boolean {
        if (store.matches(previous, read)) {
            store.markUnchanged(previous, fingerprint, checkCancelled)
            return false
        }
        store.beginImport(previous.source).use { staging ->
            read(staging::add)
            staging.publishIfCurrent(previous, fingerprint, checkCancelled)
        }
        return true
    }
}
