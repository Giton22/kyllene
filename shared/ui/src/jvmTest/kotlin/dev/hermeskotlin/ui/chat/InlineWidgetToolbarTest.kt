package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.HermesTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineWidgetToolbarTest {
    @Test
    fun toolbarSpansTheWidgetAndKeepsBothFileActionsOnPreviewFailure() = runComposeUiTest {
        val file = GatewayFile("/tmp/checklist.html")
        val downloads = mutableListOf<GatewayFile>()
        setContent {
            HermesTheme {
                Box(Modifier.size(700.dp, 400.dp)) {
                    InlineWidget(
                        ReplyMedia(file.path, file.name, isImage = false, preview = true),
                        file,
                        WidgetFiles(
                            identity = "test-profile",
                            load = { FileLoad.Failed(FileProblem.WidgetNotLoaded) },
                            download = {
                                downloads.add(it)
                                FileDownload.Failed(FileProblem.WidgetNotFetched)
                            },
                        ),
                        messageKey = "reply",
                    )
                }
            }
        }

        val filename = onNodeWithText(file.name)
        val save = onNodeWithContentDescription("Save ${file.name}")
        val open = onNodeWithContentDescription("Open in app: ${file.name}")
        val retry = onNodeWithText("Click to retry.", substring = true)
        val toolbarRight = open.fetchSemanticsNode().boundsInRoot.right
        val frameRight = retry.fetchSemanticsNode().boundsInRoot.right
        assertTrue(toolbarRight > frameRight * 0.9f && toolbarRight <= frameRight)
        assertTrue(filename.fetchSemanticsNode().boundsInRoot.center.y in
            save.fetchSemanticsNode().boundsInRoot.let { it.top..it.bottom })
        save.performClick()
        waitForIdle()
        open.performClick()
        waitForIdle()
        runOnIdle { assertEquals(listOf(file, file), downloads) }
    }

    @Test
    fun remoteImageGetsTheToolbarOnlyAfterOptInAndStillOpens() = runComposeUiTest {
        val item = ReplyMedia("https://example.com/chart.png", "chart.png", isImage = true)
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        val requests = mutableListOf<String>()
        var opened: ViewerImage? = null
        setContent {
            HermesTheme {
                CompositionLocalProvider(
                    LocalMediaLoader provides { source -> requests.add(source); bytes },
                    LocalOpenImage provides { opened = it },
                ) {
                    ReplyMediaList(listOf(item), "reply")
                }
            }
        }

        val save = onNodeWithContentDescription("Save ${item.name}")
        val openInApp = onNodeWithContentDescription("Open in app: ${item.name}")
        save.assertDoesNotExist()
        openInApp.assertDoesNotExist()
        runOnIdle { assertTrue(requests.isEmpty()) }

        onNodeWithText("example.com", substring = true).performClick()
        waitUntil { onAllNodes(hasContentDescription(item.name)).fetchSemanticsNodes().size == 1 }
        save.assertIsDisplayed()
        openInApp.assertIsDisplayed()
        onNodeWithContentDescription(item.name).performClick()
        runOnIdle {
            assertEquals(listOf(item.source), requests)
            assertEquals(item.source, opened?.source)
        }
    }
}
