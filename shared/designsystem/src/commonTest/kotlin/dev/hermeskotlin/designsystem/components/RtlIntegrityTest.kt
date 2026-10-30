package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Exact review failures. Native Copy slices rendered text; it must never need guessed cleanup. */
class RtlIntegrityTest {
    private fun prepared(source: String): String = prepareMarkdown(source)

    @Suppress("DEPRECATION") // Same actual parser overloads as the independent ReviewProbe.java.
    private fun ast(source: String): List<Triple<String, IntRange, String>> {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        return buildList {
            fun visit(node: ASTNode) {
                add(Triple(node.type.toString(), node.startOffset until node.endOffset,
                    source.substring(node.startOffset, node.endOffset)))
                node.children.forEach(::visit)
            }
            visit(root)
        }
    }

    @Test fun hebrewNumberedListsKeepActualParserStructure() {
        val source = "1. שלום עולם\n2. בדיקה נוספת"
        val original = ast(source)
        check(original.any { it.first.endsWith("ORDERED_LIST") })
        assertEquals(original, ast(prepared(source)))
    }

    @Test fun referenceDestinationsAreNeverBidiEdited() {
        val source = "שלום עולם [קישור][ref]\n\n[ref]: https://example.com/a_b"
        val original = ast(source)
        check(original.any { it.first.endsWith("LINK_DESTINATION") && it.third == "https://example.com/a_b" })
        assertEquals(original, ast(prepared(source)))
    }

    @Test fun inlineDestinationsKeepTheirParserStructure() {
        val source = "שלום עולם [קישור](https://example.com/a_b)"
        assertEquals(ast(source), ast(prepared(source)))
    }

    @Test fun longFencesMayContainShorterDelimitersWithoutCodeEdits() {
        for (source in listOf(
            "שלום עולם\n````\n```\nfoo\n````",
            "שלום עולם\n~~~~\n~~~\nfoo\n~~~~",
            "שלום עולם\n> ````\n> ```\n> foo\n> ````",
            "שלום עולם\n- item\n\n  ````\n  ```\n  foo\n  ````",
            "שלום עולם\n```kotlin\nprintln(\"שלום\")\n```",
        )) {
            val original = ast(source)
            check(original.any { it.first.endsWith("CODE_FENCE_CONTENT") })
            assertEquals(original, ast(prepared(source)), source)
        }
    }

    @Test fun authoredRlmInInlineCodeSurvivesPreparation() {
        val source = "שלום `foo\u200Fbar` סוף"
        assertEquals(source, prepared(source))
    }

    @Test fun allPartialSelectionsAreExactWithoutBidiRepair() {
        for (source in listOf(
            "שלום hermes update סוף", // review prefix through hermes and suffix from update
            "שלום עולם hermes update סוף נוסף",
            "שלום \u2066hello\u2069 סוף",
            "שלום \u2066123\u2069 סוף",
            "שלום \u2066hermes update\u2069 סוף", // authored, including either boundary alone
            "\u200Fשלום\u200E \u202Babc\u202C \u2067אב\u2069 \u2068x\u2069",
            "שלום 🙂 auto_prune 🙂 auto_prune סוף",
        )) {
            val shown = prepared(source)
            assertEquals(source, shown)
            for (start in 0..source.length) for (end in start..source.length) {
                val expected = source.substring(start, end)
                val selected = AnnotatedString(shown).subSequence(start, end).text
                assertEquals(expected, selected, "range [$start,$end)")
                assertContentEquals(expected.encodeToByteArray(), selected.encodeToByteArray())
            }
        }
    }

    @Test fun highlightsRespectAuthoredControlsAndOriginalOffsets() {
        for (source in listOf("שלום \u2066hello\u2069 סוף", "שלום \u2066123\u2069 סוף", "שלום foo\u200Fbar סוף")) {
            for (start in source.indices) for (end in start + 1..source.length) {
                val annotated = AnnotatedString(source).withHighlights(listOf(TextHighlight(source, start, end)), Color.Red)
                val range = annotated.spanStyles.single()
                assertEquals(start, range.start)
                assertEquals(end, range.end)
                assertEquals(source, annotated.text)
            }
        }
    }

    @Test fun mathPreparationIsUnchangedAndStreamingPrefixesNeverGainControls() {
        for (source in listOf("שלום `hermes update` ואז 2026-11-20", "שלום \$x+1\$ סוף", "שלום\n\$\$x^2\$\$")) {
            for (end in 0..source.length) {
                val prefix = source.take(end)
                assertEquals(MathMarkup.prepare(prefix), prepared(prefix))
            }
        }
    }
}
