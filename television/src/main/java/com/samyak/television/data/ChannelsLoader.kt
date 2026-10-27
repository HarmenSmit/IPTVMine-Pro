package com.samyak.television.data

import com.samyak.playlist.M3uParser
import com.samyak.playlist.PlaylistDownload
import com.samyak.playlist.PlaylistStore
import com.samyak.television.model.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.URL

object ChannelsLoader {
    private val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".mpg", ".mpeg", ".3gp", ".ogv", ".rm", ".rmvb")

    suspend fun importChannels(url: String, providerTitle: String?, store: PlaylistStore<Channel>, cacheDir: File) {
        val scheme = URL(url).protocol
        if (scheme != "http" && scheme != "https") throw IOException("Unsupported playlist scheme")
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (videoExtensions.any { path.endsWith(it) }) {
            store.beginImport(url).use { staging ->
                staging.add(Channel(providerTitle ?: path.substringAfterLast('/').substringBeforeLast('.'), "", url, category = "Direct Videos"))
                currentCoroutineContext().ensureActive()
                staging.publish()
            }
            return
        }
        PlaylistDownload.useFile(cacheDir, url) { file, checkCancelled ->
            store.beginImport(url).use { staging ->
                M3uParser.parse(file.inputStream(), requireName = false, checkCancelled = checkCancelled) { record ->
                    staging.add(Channel(record.name, record.logoUrl, record.streamUrl, category = record.category,
                        tvgId = record.tvgId, tvgName = record.tvgName, playlistMetadata = record.metadata))
                }
                staging.publish(checkCancelled)
            }
        }
    }
}
