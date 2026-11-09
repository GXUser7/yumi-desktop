package com.yumi.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ServerGridTest {
    @Test fun sixHundredServersStayLazyAndSearchSelectsTheLastServer() {
        val directory = Files.createTempDirectory("yumi-grid-test-").toFile()
        seedDemo(directory, 600)
        val model = DesktopModel(DemoBackend(), directory)
        try {
            val last = model.profiles.state.value.nodes.last()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsActive provides false, LocalBackdropDispatcher provides Dispatchers.Unconfined) {
                        YumiApp(model, Page.Servers)
                    }
                }
                val cards = SemanticsMatcher("server cards") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("server-card-") == true }
                assertTrue(onAllNodes(cards).fetchSemanticsNodes().size in 1..100, "Only nearby cards should be composed")
                onNodeWithTag("server-grid").performScrollToIndex(599)
                onNodeWithTag("server-card-${last.id}").assertIsDisplayed()
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("server-search"))).performTextInput("600")
                onNodeWithTag("server-card-${last.id}").assertIsDisplayed().performClick()
                waitUntil { model.profiles.state.value.selectedNodeId == last.id }
                assertEquals(1, onAllNodes(cards).fetchSemanticsNodes().size)
                assertEquals("disconnected", model.status.value.state, "Choosing a server must not raise an idle VPN")
            }
        } finally {
            runBlocking { model.close() }
            check(directory.parentFile.canonicalFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && directory.name.startsWith("yumi-grid-test-"))
            directory.deleteRecursively()
        }
    }
}
