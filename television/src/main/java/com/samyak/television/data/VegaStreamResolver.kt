package com.samyak.television.data

import android.content.Context
import com.samyak.player.StreamOption
import com.samyak.player.StreamResolver
import com.samyak.television.model.prioritizeStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges the Player module to the TV app's scraper engine so the player's
 * "Next Episode" button can turn a provider episode link into playable stream
 * candidates. Registered into [com.samyak.player.StreamResolverHolder] at TV app startup.
 */
class VegaStreamResolver(context: Context) : StreamResolver {

    private val appContext = context.applicationContext
    private val runner by lazy { VegaProviderRunner(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun resolve(
        providerUrl: String,
        scraperValue: String,
        link: String,
        type: String,
        callback: (streams: List<StreamOption>?, error: String?) -> Unit
    ) {
        if (link.isBlank()) {
            callback(null, "Invalid episode link")
            return
        }
        scope.launch {
            try {
                val streams = prioritizeStreams(
                    runner.getStream(providerUrl, scraperValue, link, type).filter { it.link.isNotBlank() }
                )
                if (streams.isEmpty()) {
                    callback(null, "No stream links found")
                } else {
                    callback(streams.map { StreamOption(it.link, HashMap(it.headers ?: emptyMap())) }, null)
                }
            } catch (e: Exception) {
                callback(null, e.message ?: "Error resolving stream")
            }
        }
    }
}
