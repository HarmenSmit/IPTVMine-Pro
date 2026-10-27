package com.samyak.television

import android.app.Application
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samyak.playlist.PlaylistStore
import com.samyak.television.data.ProviderRepository
import com.samyak.television.data.TelevisionViewModel
import com.samyak.television.model.Channel
import com.samyak.television.model.Provider
import com.samyak.television.ui.screens.SearchScreen
import com.samyak.television.ui.theme.IPTVMineProTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChannelSearchTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owner = ViewModelStore()
    private lateinit var application: Application
    private lateinit var viewModel: TelevisionViewModel
    private val source = "https://example.invalid/playlist.m3u"
    private val sport = Channel("NL Sport", "", "https://example.invalid/sport", category = "Sports")
    private val news = Channel("NPO 1", "", "https://example.invalid/npo", category = "News")

    @Before fun setUp() {
        // Unique names isolate test data while keeping it accessible to the target app's UID.
        val base = instrumentation.targetContext
        val prefix = "search-${UUID.randomUUID()}-"
        application = object : Application() {
            init { attachBaseContext(base) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
            override fun getDatabasePath(name: String): File = base.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                base.openOrCreateDatabase(prefix + name, mode, factory)
        }
        replacePlaylist(listOf(sport, news, sport, sport.copy(name = "Sport Extra", category = "News")))
        ProviderRepository(application).addProvider(Provider("Test playlist", source))
        instrumentation.runOnMainSync {
            viewModel = TelevisionViewModel(application)
            owner.put("search", viewModel)
        }
        compose.waitUntil(10_000) { viewModel.channels.value.size == 4 && !viewModel.isSearching.value }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { owner.clear() }
    }

    private fun replacePlaylist(channels: List<Channel>) {
        PlaylistStore(application, "iptv-tv-channels.db", Channel::class.java, { it.name }, { it.category }).use { store ->
            store.beginImport(source).use { stage -> channels.forEach(stage::add); stage.publish() }
        }
    }

    private fun search(query: String, category: String = "All") {
        instrumentation.runOnMainSync { viewModel.searchChannels(query, category) }
        compose.waitUntil(10_000) { !viewModel.isSearching.value }
        assertNull(viewModel.searchError.value)
    }

    private fun SemanticsNodeInteraction.selectWithRemote() {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun indexedSearchKeepsLiveTvFilterAndReusesCachedResults() {
        instrumentation.runOnMainSync { viewModel.selectCategory("News") }
        compose.waitUntil(10_000) { viewModel.filteredChannels.value.size == 2 }
        search("SPORT")
        assertEquals(3, viewModel.searchResults.value.size)
        val previousResult = viewModel.searchResults.value
        search("NP") // Short terms still work without trigram lookup.
        assertEquals(listOf(news), viewModel.searchResults.value)
        search("sport")
        assertSame(previousResult, viewModel.searchResults.value)
        search("sport", "Sports")
        assertEquals(listOf(sport, sport), viewModel.searchResults.value)
        assertEquals("News", viewModel.selectedCategory.value)
        assertEquals(listOf(news, sport.copy(name = "Sport Extra", category = "News")), viewModel.filteredChannels.value)
        search("")
        assertSame(viewModel.channels.value, viewModel.searchResults.value)
    }

    @Test fun latestQueryWinsAndPlaylistRefreshReappliesIt() {
        val original = viewModel.searchResults.value
        instrumentation.runOnMainSync {
            viewModel.searchChannels("sport")
            assertTrue(viewModel.isSearching.value)
            assertSame(original, viewModel.searchResults.value) // No query before the debounce.
            viewModel.searchChannels("NPO")
        }
        compose.waitUntil(10_000) { !viewModel.isSearching.value }
        assertEquals(listOf(news), viewModel.searchResults.value)
        val updated = news.copy(name = "NPO 2")
        replacePlaylist(listOf(updated, sport))
        instrumentation.runOnMainSync { viewModel.refreshChannels(manual = false) }
        compose.waitUntil(10_000) {
            !viewModel.isLoading.value && !viewModel.isSearching.value && viewModel.searchResults.value.firstOrNull()?.name == "NPO 2"
        }
        assertEquals("NPO", viewModel.searchQuery.value)
        assertEquals(listOf(updated), viewModel.searchResults.value)
    }

    @Test fun remoteSearchScreenFiltersDuplicatesAndOpensSelectedChannel() {
        var opened: Channel? = null
        compose.setContent {
            IPTVMineProTheme {
                SearchScreen(viewModel, onChannelClick = { opened = it }, onMovieClick = { _, _, _ -> })
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("SPORT")
        compose.waitUntil(10_000) { !viewModel.isSearching.value && viewModel.searchResults.value.size == 3 }
        compose.onNodeWithText("3 TV channels").assertIsDisplayed()
        compose.onNodeWithText("Sports").selectWithRemote()
        compose.runOnIdle { assertEquals("Sports", viewModel.searchCategory.value) }
        compose.waitUntil(10_000) { !viewModel.isSearching.value && viewModel.searchResults.value.size == 2 }
        compose.onAllNodesWithText("NL Sport").assertCountEquals(2)
        compose.onAllNodesWithText("NL Sport")[0].selectWithRemote()
        compose.runOnIdle { assertEquals(sport, opened) }
        compose.onNodeWithText("Clear").selectWithRemote()
        compose.waitUntil(10_000) { !viewModel.isSearching.value && viewModel.searchQuery.value.isEmpty() }
        compose.onNodeWithText("Movies & Shows").selectWithRemote()
        compose.onNodeWithText("TV channels").selectWithRemote()
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.runOnIdle { assertEquals("Sports", viewModel.searchCategory.value) }
    }
}
