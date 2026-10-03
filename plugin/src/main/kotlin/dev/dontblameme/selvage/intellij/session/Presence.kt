package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.dontblameme.selvage.intellij.bridge.Initials
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon

/** One remote caret, resolved into a bound document: offsets are the document's. */
data class RemoteCursor(
    val peerId: String,
    val label: String,
    val role: String,
    val path: String,
    val anchor: Int,
    val head: Int,
    val colour: String,
)

/**
 * Remote carets and selections drawn in each peer's seat colour (VS Code's `decorations.ts`): a
 * two-pixel caret with a mark in the error stripe, a translucent selection, the peer's initials in the
 * gutter once per line, and the name at the caret as `selvage.cursorLabel` asks (`labels.ts`).
 */
class PresenceRenderer(
    private val project: Project,
) {
    private val drawn = HashMap<Editor, MutableList<RangeHighlighter>>()
    private val inlays = HashMap<Editor, MutableList<Inlay<*>>>()

    /** What is drawn now, by path, for a reader that cannot see pixels. */
    var lastDrawn: Map<String, List<RemoteCursor>> = emptyMap()
        private set

    fun render(
        cursors: List<RemoteCursor>,
        documentOf: (String) -> Document?,
        labelMode: String,
    ) {
        clear()
        val byPath = cursors.groupBy { it.path }
        lastDrawn = byPath
        for ((path, list) in byPath) {
            val document = documentOf(path) ?: continue
            for (editor in EditorFactory.getInstance().getEditors(
                document,
                project,
            )) {
                draw(editor, document, list, labelMode)
            }
        }
    }

    fun clear() {
        for ((editor, highlighters) in drawn) {
            if (editor.isDisposed) continue
            highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        }
        drawn.clear()
        for ((editor, list) in inlays) if (!editor.isDisposed) list.forEach { it.dispose() }
        inlays.clear()
        lastDrawn = emptyMap()
    }

    private fun draw(
        editor: Editor,
        document: Document,
        cursors: List<RemoteCursor>,
        labelMode: String,
    ) {
        val markup = editor.markupModel
        val mine = drawn.getOrPut(editor) { ArrayList() }
        val badged = HashSet<Int>()
        for (cursor in cursors) {
            val length = document.textLength
            val head = cursor.head.coerceIn(0, length)
            val anchor = cursor.anchor.coerceIn(0, length)
            val colour = parse(cursor.colour)
            if (anchor != head) {
                val attributes = TextAttributes().apply { backgroundColor = translucent(colour) }
                mine.add(
                    markup.addRangeHighlighter(
                        minOf(anchor, head),
                        maxOf(anchor, head),
                        HighlighterLayer.SELECTION - 1,
                        attributes,
                        HighlighterTargetArea.EXACT_RANGE,
                    ),
                )
            }
            val caret =
                markup.addRangeHighlighter(
                    head,
                    head,
                    HighlighterLayer.SELECTION + 1,
                    TextAttributes().apply { errorStripeColor = colour },
                    HighlighterTargetArea.EXACT_RANGE,
                )
            caret.customRenderer =
                CaretRenderer(colour, if (labelMode == "floating") boundedLabel(cursor.label) else null)
            caret.errorStripeTooltip = "${cursor.label} · ${cursor.role}"
            val line = document.getLineNumber(head)
            if (badged.add(line)) {
                caret.gutterIconRenderer =
                    Badge(Initials.initials(cursor.label), colour, "${cursor.label} · ${cursor.role}")
            }
            mine.add(caret)
            if (labelMode == "chip") {
                editor.inlayModel.addInlineElement(head, true, ChipRenderer(boundedLabel(cursor.label), colour))?.let {
                    inlays.getOrPut(editor) { ArrayList() }.add(it)
                }
            }
        }
    }

    private class CaretRenderer(
        private val colour: Color,
        private val floating: String?,
    ) : CustomHighlighterRenderer {
        override fun paint(
            editor: Editor,
            highlighter: RangeHighlighter,
            g: Graphics,
        ) {
            val point = editor.offsetToXY(highlighter.startOffset)
            g.color = colour
            g.fillRect(point.x, point.y, JBUI.scale(2), editor.lineHeight)
            if (floating != null) {
                val font =
                    editor.colorsScheme
                        .getFont(
                            EditorFontType.BOLD,
                        ).deriveFont(editor.colorsScheme.editorFontSize2D * 0.7f)
                g.font = font
                val metrics = g.getFontMetrics(font)
                val width = metrics.stringWidth(floating) + JBUI.scale(6)
                val height = metrics.height
                val top = point.y - height
                g.fillRect(point.x, top, width, height)
                g.color = Color.BLACK
                (g as? Graphics2D)?.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
                g.drawString(floating, point.x + JBUI.scale(3), top + metrics.ascent)
            }
        }
    }

    private class ChipRenderer(
        private val label: String,
        private val colour: Color,
    ) : EditorCustomElementRenderer {
        private fun font(inlay: Inlay<*>): Font = inlay.editor.colorsScheme.getFont(EditorFontType.BOLD)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            inlay.editor.contentComponent
                .getFontMetrics(font(inlay))
                .stringWidth(" $label ") + JBUI.scale(4)

        override fun paint(
            inlay: Inlay<*>,
            g: Graphics,
            targetRegion: Rectangle,
            textAttributes: TextAttributes,
        ) {
            g.color = colour
            g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
            g.color = Color.BLACK
            g.drawRect(targetRegion.x, targetRegion.y, targetRegion.width - 1, targetRegion.height - 1)
            g.font = font(inlay)
            g.drawString(" $label ", targetRegion.x + JBUI.scale(2), targetRegion.y + inlay.editor.ascent)
        }
    }

    /** A peer's initials on their seat colour, in the gutter. */
    class Badge(
        private val text: String,
        private val colour: Color,
        private val tooltip: String,
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = InitialsIcon(text, colour)

        override fun getTooltipText(): String = tooltip

        override fun equals(other: Any?): Boolean =
            other is Badge && other.text == text && other.colour == colour && other.tooltip == tooltip

        override fun hashCode(): Int = text.hashCode() * 31 + colour.hashCode()
    }

    /** A round badge with initials: the gutter's, the participants view's and the project view's. */
    class InitialsIcon(
        private val text: String,
        private val colour: Color,
        private val size: Int = JBUI.scale(14),
    ) : Icon {
        override fun paintIcon(
            c: Component?,
            g: Graphics,
            x: Int,
            y: Int,
        ) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = colour
                g2.fillOval(x, y, size, size)
                g2.color = Color.BLACK
                g2.font = JBUI.Fonts.miniFont().deriveFont(Font.BOLD)
                val metrics = g2.fontMetrics
                val width = metrics.stringWidth(text)
                g2.drawString(text, x + (size - width) / 2, y + (size - metrics.height) / 2 + metrics.ascent)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size
    }

    companion object {
        /** `LABEL_LIMIT` in `labels.ts`: a drawn name is clipped at 24 code points. */
        const val LABEL_LIMIT = 24

        fun boundedLabel(label: String): String {
            val count = label.codePointCount(0, label.length)
            if (count <= LABEL_LIMIT) return label
            return label.substring(0, label.offsetByCodePoints(0, LABEL_LIMIT - 1)) + "…"
        }

        fun parse(hex: String): Color = JBColor(Color.decode(hex), Color.decode(hex))

        fun translucent(colour: Color): Color = Color(colour.red, colour.green, colour.blue, 64)
    }
}
