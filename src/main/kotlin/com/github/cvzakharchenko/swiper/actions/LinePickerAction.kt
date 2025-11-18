package com.github.cvzakharchenko.swiper.actions

import com.github.cvzakharchenko.swiper.MyBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.IllegalComponentStateException
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import kotlin.math.roundToInt
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes

class LinePickerAction : DumbAwareAction(
    MyBundle.message("linePicker.action.text"),
    MyBundle.message("linePicker.action.description"),
    AllIcons.Actions.Search
) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = resolveEditor(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = resolveEditor(e) ?: return
        val project = e.project ?: editor.project ?: return

        val activePopup = currentPopup
        if (activePopup != null && !activePopup.isDisposed) {
            return
        }

        val document = editor.document
        if (document.lineCount == 0) {
            return
        }

        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        val originalCaret = editor.caretModel.logicalPosition
        val previewController = CaretPreviewController(editor, document, originalCaret)
        val searchHighlightManager = SearchHighlightManager(editor, document)
        var committedNavigation = false
        var previewArmed = false
        var currentSearchWords: List<String> = emptyList()
        var currentMatchedEntries: List<LineEntry> = emptyList()
        val popupWidth = computePopupWidth(project, editor)

        val lines = buildList {
            for (line in 0 until document.lineCount) {
                val startOffset = document.getLineStartOffset(line)
                val endOffset = document.getLineEndOffset(line)
                val rawLine = document.getText(TextRange(startOffset, endOffset))
                val normalized = normalizeLineText(rawLine)
                add(LineEntry(line, normalized))
            }
        }

        val listModel = CollectionListModel(lines.toMutableList())
        val caretLine = editor.caretModel.logicalPosition.line

        val listRenderer = LineEntryRenderer { currentSearchWords }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = listRenderer
            emptyText.text = ""
        }
        val rowHeight = list.getFontMetrics(list.font).height + JBUI.scale(3)
        list.fixedCellHeight = rowHeight
        val scrollPane = ScrollPaneFactory.createScrollPane(list).apply {
            border = JBUI.Borders.empty(2)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            isWheelScrollingEnabled = true
        }
        list.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                previewArmed = true
            }
        })
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            if (!previewArmed) return@addListSelectionListener
            val entry = list.selectedValue ?: return@addListSelectionListener
            val target = findPreviewTarget(document, entry.lineNumber, currentSearchWords)
            previewController.preview(target)
        }

        var popupRef: JBPopup? = null

        val searchField = SearchTextField(false).apply {
            border = JBUI.Borders.empty(2, 4)
        }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                previewArmed = true
                val filterResult = applyFilter(
                    lines,
                    listModel,
                    list,
                    caretLine,
                    searchField.text,
                    scrollPane,
                    popupRef,
                    searchField,
                    popupWidth
                )
                currentSearchWords = filterResult.words
                currentMatchedEntries = filterResult.entries
                searchHighlightManager.update(currentSearchWords, currentMatchedEntries)
            }
        })

        val container = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(4, 6)
            add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        val popupBuilder = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(container, searchField)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(true)
        val popup = popupBuilder.createPopup()
        popupRef = popup

        currentPopup = popup
        val selectionPerformer = installSelectionHandlers(
            list,
            popup,
            editor,
            document,
            { currentSearchWords },
            { previewArmed = true }
        ) {
            committedNavigation = true
        }
        installSearchFieldNavigation(searchField, list, selectionPerformer) { previewArmed = true }
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (currentPopup === popup) {
                    currentPopup = null
                }
                if (!committedNavigation) {
                    previewController.restore()
                } else {
                    previewController.clearPreview()
                }
                searchHighlightManager.clear()
            }
        })
        installAltFMnemonicBlocker(popup)

        val initialFilterResult = applyFilter(
            lines,
            listModel,
            list,
            caretLine,
            searchField.text,
            scrollPane,
            popup,
            searchField,
            popupWidth
        )
        currentSearchWords = initialFilterResult.words
        currentMatchedEntries = initialFilterResult.entries
        searchHighlightManager.update(currentSearchWords, currentMatchedEntries)
        popup.showAtTopOfWindow(editor, project, popupWidth)
    }

    private fun resolveEditor(e: AnActionEvent): Editor? {
        e.getData(CommonDataKeys.EDITOR)?.let { return it }
        val project = e.project ?: return null
        return FileEditorManager.getInstance(project).selectedTextEditor
    }

    private fun JComponent.componentRoot(): JComponent = this.rootPane ?: this

    private fun navigateToLine(editor: Editor, lineNumber: Int, column: Int = 0) {
        if (lineNumber !in 0 until editor.document.lineCount) return
        val caretModel = editor.caretModel
        val safeColumn = column.coerceAtLeast(0)
        caretModel.moveToLogicalPosition(LogicalPosition(lineNumber, safeColumn))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun normalizeLineText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return MyBundle.message("linePicker.emptyLine")
        }
        val singleLine = StringUtil.replace(trimmed, "\t", "    ")
        return StringUtil.shortenTextWithEllipsis(singleLine, 120, 0)
    }

    private fun installAltFMnemonicBlocker(popup: JBPopup) {
        IdeEventQueue.getInstance().addDispatcher({ event ->
            if (popup.isDisposed) return@addDispatcher false
            if (event !is KeyEvent) return@addDispatcher false
            if (event.id != KeyEvent.KEY_PRESSED) return@addDispatcher false
            val isAltF = event.keyCode == KeyEvent.VK_F &&
                (event.modifiersEx and InputEvent.ALT_DOWN_MASK) != 0
            if (isAltF) {
                event.consume()
                return@addDispatcher true
            }
            false
        }, popup)
    }

    private data class LineEntry(val lineNumber: Int, val text: String) {
        val displayText: String = text
        val searchableText: String = text.lowercase()
        override fun toString(): String = displayText
    }

    private fun LineEntry.matches(words: List<String>): Boolean =
        words.all { searchableText.contains(it) }

    private fun LineEntry.computeWeight(words: List<String>): Int {
        if (words.isEmpty()) return 0
        val distinctWords = words.distinct()
        val lowerText = text.lowercase()
        val matchesByWord = distinctWords.associateWith {
            collectMatchInfos(text, lowerText, it)
        }
        var score = distinctWords.count { word ->
            matchesByWord[word].orEmpty().any { it.qualifies }
        }
        val sharedTokenBonus = matchesByWord.sharedTokenBonus(distinctWords.size)
        score += sharedTokenBonus
        return score
    }

    private companion object {
        private val POPUP_MIN_WIDTH = JBUI.scale(420)
        private val POPUP_MAX_WIDTH = JBUI.scale(900)
        private val POPUP_VERTICAL_PADDING = JBUI.scale(10)
        private val POPUP_MAX_HEIGHT = JBUI.scale(600)
        private const val MAX_VISIBLE_ROWS = 12
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        @Volatile
        private var currentPopup: JBPopup? = null
    }

    private data class FilterResult(
        val words: List<String>,
        val entries: List<LineEntry>
    )

    private fun applyFilter(
        source: List<LineEntry>,
        model: CollectionListModel<LineEntry>,
        list: JBList<LineEntry>,
        caretLine: Int,
        query: String,
        scrollPane: JScrollPane,
        popup: JBPopup?,
        searchField: SearchTextField,
        popupWidth: Int
    ): FilterResult {
        val words = query.parseSearchWords()
        val filtered = if (words.isEmpty()) source else source.filter { it.matches(words) }
        val sorted = if (words.isEmpty()) {
            filtered
        } else {
            filtered.sortedWith(
                compareByDescending<LineEntry> { it.computeWeight(words) }
                    .thenBy { it.lineNumber }
            )
        }
        model.replaceAll(sorted)
        if (sorted.isEmpty()) {
            list.clearSelection()
            resizePopupForItems(list, searchField, scrollPane, 0, popup, popupWidth, collapsed = true)
            return FilterResult(words, emptyList())
        }
        val targetIndex = if (words.isEmpty()) {
            val caretIndex = sorted.indexOfFirst { it.lineNumber == caretLine }
            if (caretIndex >= 0) caretIndex else 0
        } else {
            0
        }
        list.selectedIndex = targetIndex
        list.ensureIndexIsVisible(targetIndex)
        resizePopupForItems(list, searchField, scrollPane, sorted.size, popup, popupWidth, collapsed = false)
        return FilterResult(words, sorted.toList())
    }

    private fun resizePopupForItems(
        list: JBList<LineEntry>,
        searchField: SearchTextField,
        scrollPane: JScrollPane,
        itemCount: Int,
        popup: JBPopup?,
        popupWidth: Int,
        collapsed: Boolean
    ) {
        popup ?: return
        val rows = if (itemCount <= 0) 1 else itemCount.coerceAtMost(MAX_VISIBLE_ROWS)
        list.visibleRowCount = rows
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        val rowHeight = list.fixedCellHeight.takeIf { it > 0 }
            ?: (list.getFontMetrics(list.font).height + JBUI.scale(6))
        val listHeight = rowHeight * rows + list.insetsHeight()
        val searchHeight = searchField.preferredSize.height
        val scrollInsets = scrollPane.verticalPadding()
        val minHeight = searchHeight + list.insetsHeight() + scrollInsets + POPUP_VERTICAL_PADDING
        val desiredHeight = if (collapsed) searchHeight + JBUI.scale(12) else
            (listHeight + searchHeight + scrollInsets + POPUP_VERTICAL_PADDING).coerceAtLeast(minHeight)
        val totalHeight = desiredHeight.coerceAtMost(POPUP_MAX_HEIGHT)

        popup.setSize(Dimension(popupWidth, totalHeight))
        list.isVisible = !collapsed
        scrollPane.isVisible = !collapsed
    }

    private fun String?.parseSearchWords(): List<String> {
        if (this.isNullOrBlank()) return emptyList()
        return WHITESPACE_REGEX.split(trim())
            .asSequence()
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toList()
    }

    private fun installSelectionHandlers(
        list: JBList<LineEntry>,
        popup: JBPopup,
        editor: Editor,
        document: Document,
        getSearchWords: () -> List<String>,
        armPreview: () -> Unit,
        onCommit: () -> Unit
    ): () -> Unit {
        val chooseSelection = selection@{
            val entry = list.selectedValue ?: return@selection
            val target = findPreviewTarget(document, entry.lineNumber, getSearchWords())
            onCommit()
            popup.cancel()
            navigateToLine(editor, target.line, target.column)
        }

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    chooseSelection()
                }
            }
        })

        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    armPreview()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    e.consume()
                    chooseSelection()
                }
            }
        })

        return chooseSelection
    }

    private fun installSearchFieldNavigation(
        searchField: SearchTextField,
        list: JBList<LineEntry>,
        chooseSelection: () -> Unit,
        armPreview: () -> Unit
    ) {
        val editor = searchField.textEditor
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        armPreview()
                        moveSelection(list, +1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        armPreview()
                        moveSelection(list, -1)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        e.consume()
                        chooseSelection()
                    }
                }
            }
        })
    }

    private fun moveSelection(list: JBList<LineEntry>, delta: Int) {
        val size = list.model.size
        if (size <= 0) return
        val current = if (list.selectedIndex >= 0) list.selectedIndex else 0
        val next = (current + delta).coerceIn(0, size - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private fun findPreviewTarget(document: Document, lineNumber: Int, words: List<String>): PreviewTarget {
        if (lineNumber !in 0 until document.lineCount) return PreviewTarget(lineNumber, 0, 0)
        val normalizedWords = words.filter { it.isNotBlank() }.map { it.lowercase() }
        val matches = collectLineMatchRanges(document, lineNumber, normalizedWords)
        if (matches.isNotEmpty()) {
            val first = matches.first()
            return PreviewTarget(
                lineNumber,
                document.getLineStartOffset(lineNumber).let { start -> first.startOffset - start },
                (first.endOffset - first.startOffset).coerceAtLeast(1)
            )
        }
        val startOffset = document.getLineStartOffset(lineNumber)
        val endOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(TextRange(startOffset, endOffset))
        val firstNonWhitespace = lineText.indexOfFirst { !it.isWhitespace() }
        if (firstNonWhitespace >= 0) {
            val length = lineText.substring(firstNonWhitespace)
                .takeWhile { !it.isWhitespace() }
                .length
                .coerceAtLeast(1)
            return PreviewTarget(lineNumber, firstNonWhitespace, length)
        }
        return PreviewTarget(lineNumber, 0, lineText.length.coerceAtLeast(1))
    }

    private class LineEntryRenderer(
        private val wordsProvider: () -> List<String>
    ) : ColoredListCellRenderer<LineEntry>() {
        override fun customizeCellRenderer(
            list: JList<out LineEntry>,
            value: LineEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            val text = value.text
            val searchWords = wordsProvider().filter { it.isNotBlank() }.map { it.lowercase() }
            if (searchWords.isEmpty()) {
                append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }
            val lower = text.lowercase()
            val matches = collectMatches(lower, searchWords)
            if (matches.isEmpty()) {
                append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }
            val highlightAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, list.foreground)
            var currentIndex = 0
            for (range in matches) {
                if (range.first > currentIndex) {
                    append(text.substring(currentIndex, range.first), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                append(text.substring(range.first, range.last + 1), highlightAttributes)
                currentIndex = range.last + 1
            }
            if (currentIndex < text.length) {
                append(text.substring(currentIndex), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        private fun collectMatches(lineLower: String, words: List<String>): List<IntRange> =
            collectMatchesInText(lineLower, words)
    }

    private class CaretPreviewController(
        private val editor: Editor,
        private val document: Document,
        originalPosition: LogicalPosition
    ) {
        private val originalLine = originalPosition.line
        private val originalColumn = originalPosition.column
        private val markupModel = editor.markupModel
        private val highlightAttributes = EditorColorsManager.getInstance().globalScheme
            .getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES) ?: TextAttributes()
        private var lastPreviewTarget: PreviewTarget? = null
        private var highlighter: RangeHighlighter? = null

        fun preview(target: PreviewTarget) {
            if (target.line !in 0 until document.lineCount) return
            if (lastPreviewTarget == target) return
            lastPreviewTarget = target
            val safeColumn = target.column.coerceAtLeast(0)
            val startOffset = document.getLineStartOffset(target.line) + safeColumn
            val endOffset = (startOffset + target.length).coerceAtMost(document.getLineEndOffset(target.line))
            clearHighlight()
            if (endOffset > startOffset) {
                highlighter = markupModel.addRangeHighlighter(
                    startOffset,
                    endOffset,
                    HighlighterLayer.SELECTION - 1,
                    highlightAttributes,
                    HighlighterTargetArea.EXACT_RANGE
                )
            }
            editor.caretModel.moveToLogicalPosition(LogicalPosition(target.line, safeColumn))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }

        fun restore() {
            if (document.lineCount == 0) return
            val line = originalLine.coerceIn(0, document.lineCount - 1)
            val column = originalColumn.coerceAtLeast(0)
            editor.caretModel.moveToLogicalPosition(LogicalPosition(line, column))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            clearPreview()
        }

        fun clearPreview() {
            lastPreviewTarget = null
            clearHighlight()
        }

        private fun clearHighlight() {
            highlighter?.let { markupModel.removeHighlighter(it) }
            highlighter = null
        }
    }

    private data class PreviewTarget(val line: Int, val column: Int, val length: Int)

    private class SearchHighlightManager(
        private val editor: Editor,
        private val document: Document
    ) {
        private val markupModel = editor.markupModel
        private val highlightAttributes = EditorColorsManager.getInstance().globalScheme
            .getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES) ?: TextAttributes()
        private val highlighters = mutableListOf<RangeHighlighter>()

        fun update(words: List<String>, entries: List<LineEntry>) {
            clear()
            val normalizedWords = words.filter { it.isNotBlank() }.map { it.lowercase() }
            if (normalizedWords.isEmpty() || entries.isEmpty()) return
            for (entry in entries) {
                val ranges = collectLineMatchRanges(document, entry.lineNumber, normalizedWords)
                for (range in ranges) {
                    highlighters += markupModel.addRangeHighlighter(
                        range.startOffset,
                        range.endOffset,
                        HighlighterLayer.SELECTION - 2,
                        highlightAttributes,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                }
            }
        }

        fun clear() {
            if (highlighters.isEmpty()) return
            highlighters.forEach { markupModel.removeHighlighter(it) }
            highlighters.clear()
        }
    }

    private fun computePopupWidth(project: Project, editor: Editor): Int {
        val baseWidth = WindowManager.getInstance().getIdeFrame(project)?.component?.width
            ?: editor.component.width.takeIf { it > 0 }
            ?: editor.contentComponent.width.takeIf { it > 0 }
            ?: POPUP_MIN_WIDTH
        val desired = (baseWidth * 0.5f).roundToInt()
        return desired.coerceIn(POPUP_MIN_WIDTH, POPUP_MAX_WIDTH)
    }

    private fun JScrollPane.verticalPadding(): Int {
        val baseInsets = insets?.let { it.top + it.bottom } ?: 0
        val viewportInsets = viewportBorder?.getBorderInsets(this)?.let { it.top + it.bottom } ?: 0
        return baseInsets + viewportInsets
    }

    private fun JComponent.insetsHeight(): Int =
        insets?.let { it.top + it.bottom } ?: 0

    private fun JBPopup.showAtTopOfWindow(editor: Editor, project: Project, popupWidth: Int) {
        val targetComponent = WindowManager.getInstance().getIdeFrame(project)?.component
            ?: editor.component.componentRoot()
        val location = try {
            targetComponent.locationOnScreen
        } catch (_: IllegalComponentStateException) {
            null
        }
        if (location == null) {
            showInBestPositionFor(editor)
            return
        }
        val height = size.height.takeIf { it > 0 } ?: content.preferredSize.height
        val x = location.x + (targetComponent.width - popupWidth) / 2
        val y = location.y + JBUI.scale(32)
        showInScreenCoordinates(
            targetComponent,
            Point(
                x.coerceAtLeast(location.x),
                y.coerceAtMost(location.y + targetComponent.height - height)
            )
        )
    }
}

private data class LineMatchRange(val startOffset: Int, val endOffset: Int)

private fun collectLineMatchRanges(
    document: Document,
    lineNumber: Int,
    words: List<String>
): List<LineMatchRange> {
    if (lineNumber !in 0 until document.lineCount) return emptyList()
    val startOffset = document.getLineStartOffset(lineNumber)
    val endOffset = document.getLineEndOffset(lineNumber)
    val text = document.getText(TextRange(startOffset, endOffset))
    val matches = collectMatchesInText(text.lowercase(), words)
    return matches.map { range ->
        LineMatchRange(
            startOffset + range.first,
            (startOffset + range.last + 1).coerceAtMost(endOffset)
        )
    }
}

private fun collectMatchesInText(lowerText: String, words: List<String>): List<IntRange> {
    if (lowerText.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    for (word in words) {
        var idx = lowerText.indexOf(word)
        while (idx >= 0) {
            val end = (idx + word.length - 1).coerceAtLeast(idx)
            ranges += IntRange(idx, end)
            idx = lowerText.indexOf(word, idx + word.length)
        }
    }
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    var current = sorted.first()
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        current = if (next.first <= current.last + 1) {
            IntRange(current.first, maxOf(current.last, next.last))
        } else {
            merged += current
            next
        }
    }
    merged += current
    return merged
}

private data class MatchInfo(
    val start: Int,
    val end: Int,
    val tokenRange: IntRange,
    val qualifies: Boolean
)

private fun collectMatchInfos(text: String, lowerText: String, word: String): List<MatchInfo> {
    if (word.isEmpty() || lowerText.isEmpty()) return emptyList()
    val matches = mutableListOf<MatchInfo>()
    var index = lowerText.indexOf(word)
    while (index >= 0 && index < text.length) {
        matches += createMatchInfo(text, index, word.length)
        index = lowerText.indexOf(word, index + word.length)
    }
    return matches
}

private fun createMatchInfo(text: String, start: Int, length: Int): MatchInfo {
    val safeStart = start.coerceIn(0, text.length)
    val safeEnd = (safeStart + length).coerceAtMost(text.length)
    val boundary = safeStart == 0 || !text[safeStart - 1].isLetterOrDigit()
    val startsWithCapital = text.getOrNull(safeStart)?.isUpperCase() == true
    val tokenStart = findTokenStart(text, safeStart)
    val tokenEndExclusive = findTokenEnd(text, safeEnd)
    val tokenRange = IntRange(tokenStart, (tokenEndExclusive - 1).coerceAtLeast(tokenStart))
    return MatchInfo(
        start = safeStart,
        end = safeEnd,
        tokenRange = tokenRange,
        qualifies = boundary || startsWithCapital
    )
}

private fun findTokenStart(text: String, index: Int): Int {
    var pos = index
    while (pos > 0 && text[pos - 1].isLetterOrDigit()) {
        pos--
    }
    return pos
}

private fun findTokenEnd(text: String, index: Int): Int {
    var pos = index
    while (pos < text.length && text[pos].isLetterOrDigit()) {
        pos++
    }
    return pos
}

private fun Map<String, List<MatchInfo>>.sharedTokenBonus(wordCount: Int): Int {
    if (wordCount <= 1) return 0
    var shared: Set<IntRange>? = null
    for (infos in values) {
        val tokens = infos.filter { it.qualifies }.map { it.tokenRange }.toSet()
        if (tokens.isEmpty()) return 0
        shared = shared?.intersect(tokens) ?: tokens
        if (shared.isNullOrEmpty()) return 0
    }
    return wordCount - 1
}

