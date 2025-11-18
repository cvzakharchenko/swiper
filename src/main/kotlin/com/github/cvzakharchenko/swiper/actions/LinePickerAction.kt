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
        val previewController = CaretPreviewController(editor, originalCaret)
        var committedNavigation = false
        var previewArmed = false
        var currentSearchWords: List<String> = emptyList()
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

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = LineEntryRenderer()
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
            val column = findFirstMatchColumn(document, entry.lineNumber, currentSearchWords)
            previewController.preview(entry.lineNumber, column)
        }

        var popupRef: JBPopup? = null

        val searchField = SearchTextField(false).apply {
            border = JBUI.Borders.empty(2, 4)
        }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                previewArmed = true
                currentSearchWords = applyFilter(
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
                }
            }
        })
        installAltFMnemonicBlocker(popup)

        currentSearchWords = applyFilter(
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
    ): List<String> {
        val words = query.parseSearchWords()
        val filtered = if (words.isEmpty()) source else source.filter { it.matches(words) }
        model.replaceAll(filtered)
        if (filtered.isEmpty()) {
            list.clearSelection()
            resizePopupForItems(list, searchField, scrollPane, 0, popup, popupWidth, collapsed = true)
            return words
        }
        val caretIndex = filtered.indexOfFirst { it.lineNumber == caretLine }
        val targetIndex = if (caretIndex >= 0) caretIndex else 0
        list.selectedIndex = targetIndex
        list.ensureIndexIsVisible(targetIndex)
        resizePopupForItems(list, searchField, scrollPane, filtered.size, popup, popupWidth, collapsed = false)
        return words
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
            val column = findFirstMatchColumn(document, entry.lineNumber, getSearchWords())
            onCommit()
            popup.cancel()
            navigateToLine(editor, entry.lineNumber, column)
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

    private fun findFirstMatchColumn(document: Document, lineNumber: Int, words: List<String>): Int {
        if (lineNumber !in 0 until document.lineCount) return 0
        val startOffset = document.getLineStartOffset(lineNumber)
        val endOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(TextRange(startOffset, endOffset))
        if (lineText.isEmpty()) return 0
        val searchTargets = if (words.isEmpty()) emptyList() else words
        val lower = lineText.lowercase()
        var best = Int.MAX_VALUE
        for (word in searchTargets) {
            if (word.isEmpty()) continue
            val idx = lower.indexOf(word)
            if (idx >= 0 && idx < best) {
                best = idx
            }
        }
        if (best != Int.MAX_VALUE) {
            return best
        }
        val firstNonSpace = lineText.indexOfFirst { !it.isWhitespace() }
        return if (firstNonSpace >= 0) firstNonSpace else 0
    }

    private class LineEntryRenderer : ColoredListCellRenderer<LineEntry>() {
        override fun customizeCellRenderer(
            list: JList<out LineEntry>,
            value: LineEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append(value.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private class CaretPreviewController(
        private val editor: Editor,
        originalPosition: LogicalPosition
    ) {
        private val originalLine = originalPosition.line
        private val originalColumn = originalPosition.column
        private var lastPreviewPosition: Pair<Int, Int>? = null

        fun preview(lineNumber: Int, column: Int) {
            val document = editor.document
            if (lineNumber !in 0 until document.lineCount) return
            val safeColumn = column.coerceAtLeast(0)
            val position = lineNumber to safeColumn
            if (lastPreviewPosition == position) return
            lastPreviewPosition = position
            editor.caretModel.moveToLogicalPosition(LogicalPosition(lineNumber, safeColumn))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }

        fun restore() {
            val document = editor.document
            if (document.lineCount == 0) return
            val line = originalLine.coerceIn(0, document.lineCount - 1)
            val column = originalColumn.coerceAtLeast(0)
            editor.caretModel.moveToLogicalPosition(LogicalPosition(line, column))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            lastPreviewPosition = null
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

