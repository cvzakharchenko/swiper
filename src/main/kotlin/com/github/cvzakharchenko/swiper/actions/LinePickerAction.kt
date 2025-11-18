package com.github.cvzakharchenko.swiper.actions

import com.github.cvzakharchenko.swiper.MyBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
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
import kotlin.math.max

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
        }
        val rowHeight = list.getFontMetrics(list.font).height + JBUI.scale(6)
        list.fixedCellHeight = rowHeight
        val scrollPane = ScrollPaneFactory.createScrollPane(list)

        var popupRef: JBPopup? = null

        val searchField = SearchTextField(false)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter(
                    lines,
                    listModel,
                    list,
                    caretLine,
                    searchField.text,
                    searchField,
                    scrollPane,
                    popupRef
                )
            }
        })

        val container = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        val popupBuilder = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(container, searchField)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
        val popup = popupBuilder.createPopup()
        popupRef = popup

        currentPopup = popup
        val selectionPerformer = installSelectionHandlers(list, popup, editor)
        installSearchFieldNavigation(searchField, list, selectionPerformer)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (currentPopup === popup) {
                    currentPopup = null
                }
            }
        })
        installAltFMnemonicBlocker(popup)

        applyFilter(
            lines,
            listModel,
            list,
            caretLine,
            searchField.text,
            searchField,
            scrollPane,
            popup
        )
        popup.showInCenterOf(editor.component.componentRoot())
    }

    private fun resolveEditor(e: AnActionEvent): Editor? {
        e.getData(CommonDataKeys.EDITOR)?.let { return it }
        val project = e.project ?: return null
        return FileEditorManager.getInstance(project).selectedTextEditor
    }

    private fun JComponent.componentRoot(): JComponent = this.rootPane ?: this

    private fun navigateToLine(editor: Editor, lineNumber: Int) {
        if (lineNumber !in 0 until editor.document.lineCount) return
        val caretModel = editor.caretModel
        caretModel.moveToLogicalPosition(LogicalPosition(lineNumber, 0))
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
        val displayText: String = "${lineNumber + 1}: $text"
        val searchableText: String = displayText.lowercase()
        override fun toString(): String = displayText
    }

    private fun LineEntry.matches(words: List<String>): Boolean =
        words.all { searchableText.contains(it) }

    private companion object {
        private val MIN_POPUP_WIDTH = JBUI.scale(420)
        private const val MAX_VISIBLE_ROWS = 12
        private val POPUP_VERTICAL_PADDING = JBUI.scale(8) * 2 + JBUI.scale(6)
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
        searchField: SearchTextField,
        scrollPane: JScrollPane,
        popup: JBPopup?
    ) {
        val words = query.parseSearchWords()
        val filtered = if (words.isEmpty()) source else source.filter { it.matches(words) }
        model.replaceAll(filtered)
        if (filtered.isEmpty()) {
            list.clearSelection()
            resizePopupForItems(list, searchField, scrollPane, 0, popup)
            return
        }
        val caretIndex = filtered.indexOfFirst { it.lineNumber == caretLine }
        val targetIndex = if (caretIndex >= 0) caretIndex else 0
        list.selectedIndex = targetIndex
        list.ensureIndexIsVisible(targetIndex)
        resizePopupForItems(list, searchField, scrollPane, filtered.size, popup)
    }

    private fun resizePopupForItems(
        list: JBList<LineEntry>,
        searchField: SearchTextField,
        scrollPane: JScrollPane,
        itemCount: Int,
        popup: JBPopup?
    ) {
        popup ?: return
        val rows = if (itemCount <= 0) 1 else itemCount.coerceAtMost(MAX_VISIBLE_ROWS)
        list.visibleRowCount = rows
        val rowHeight = list.fixedCellHeight.takeIf { it > 0 }
            ?: (list.getFontMetrics(list.font).height + JBUI.scale(6))
        val listHeight = rowHeight * rows + list.insetsHeight()
        val searchHeight = searchField.preferredSize.height
        val scrollInsets = scrollPane.verticalPadding()
        val needsScrollbar = itemCount > rows
        scrollPane.verticalScrollBarPolicy = if (needsScrollbar) {
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        } else {
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        val scrollbarWidth = if (needsScrollbar) {
            scrollPane.verticalScrollBar?.preferredSize?.width ?: JBUI.scale(12)
        } else 0
        val minHeight = searchHeight + rowHeight + list.insetsHeight() + scrollInsets + POPUP_VERTICAL_PADDING
        val totalHeight = (listHeight + searchHeight + scrollInsets + POPUP_VERTICAL_PADDING)
            .coerceAtLeast(minHeight)
        val currentWidth = popup.size?.width?.takeIf { it > 0 }
            ?: popup.content.preferredSize.width.takeIf { it > 0 }
            ?: MIN_POPUP_WIDTH
        val baseWidth = max(currentWidth, MIN_POPUP_WIDTH)
        val width = baseWidth + scrollbarWidth
        popup.setSize(Dimension(width, totalHeight))
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
        editor: Editor
    ): () -> Unit {
        val chooseSelection = selection@{
            val entry = list.selectedValue ?: return@selection
            popup.cancel()
            navigateToLine(editor, entry.lineNumber)
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
        chooseSelection: () -> Unit
    ) {
        val editor = searchField.textEditor
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        moveSelection(list, +1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
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

    private class LineEntryRenderer : ColoredListCellRenderer<LineEntry>() {
        override fun customizeCellRenderer(
            list: JList<out LineEntry>,
            value: LineEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append("${value.lineNumber + 1}: ", SimpleTextAttributes.GRAY_ATTRIBUTES)
            append(value.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private fun JScrollPane.verticalPadding(): Int {
        val baseInsets = insets?.let { it.top + it.bottom } ?: 0
        val viewportInsets = viewportBorder?.getBorderInsets(this)?.let { it.top + it.bottom } ?: 0
        return baseInsets + viewportInsets
    }

    private fun JComponent.insetsHeight(): Int =
        insets?.let { it.top + it.bottom } ?: 0
}

