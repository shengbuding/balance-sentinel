package com.balancesentinel.app.data.api.balance

import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.Token
import org.mozilla.javascript.ast.AstNode
import org.mozilla.javascript.ast.ElementGet
import org.mozilla.javascript.ast.FunctionCall
import org.mozilla.javascript.ast.InfixExpression
import org.mozilla.javascript.ast.NewExpression
import org.mozilla.javascript.ast.ParenthesizedExpression
import org.mozilla.javascript.ast.PropertyGet
import java.util.IdentityHashMap

internal object SavedScriptCompatibility {
    private const val SOURCE_NAME = "usage-script-compatibility"

    fun rewrite(source: String): String {
        val operators = OperatorScanner(source).scan()
        if (operators.optionalOffsets.isEmpty() && operators.nullishOffsets.isEmpty()) {
            return source
        }

        val sanitized = source.toCharArray()
        operators.optionalOffsets.forEach { offset -> sanitized[offset] = ' ' }
        operators.nullishOffsets.forEach { offset ->
            sanitized[offset] = '|'
            sanitized[offset + 1] = '|'
        }

        val environs = CompilerEnvirons().apply {
            languageVersion = Context.VERSION_ES6
        }
        val root = Parser(environs).parse(String(sanitized), SOURCE_NAME, 1)
        val marked = IdentityHashMap<AstNode, OperatorKind>()
        val mappedOptional = mutableSetOf<Int>()
        val mappedNullish = mutableSetOf<Int>()

        root.visit { node ->
            when {
                node is PropertyGet -> {
                    val markerOffset = node.absoluteOperatorPosition() - 1
                    if (markerOffset in operators.optionalOffsets) {
                        marked[node] = OperatorKind.OPTIONAL_PROPERTY
                        mappedOptional += markerOffset
                    }
                }

                node is InfixExpression && node.operator == Token.OR -> {
                    val markerOffset = node.absoluteOperatorPosition()
                    if (markerOffset in operators.nullishOffsets) {
                        marked[node] = OperatorKind.NULLISH
                        mappedNullish += markerOffset
                    }
                }
            }
            true
        }

        require(mappedOptional == operators.optionalOffsets) {
            "Unsupported optional chaining expression"
        }
        require(mappedNullish == operators.nullishOffsets) {
            "Unsupported nullish coalescing expression"
        }

        val temporaryName = freshTemporaryName(source)
        return AstSpanRewriter(source, marked, temporaryName).rewrite()
    }

    private fun InfixExpression.absoluteOperatorPosition(): Int =
        absolutePosition + operatorPosition

    private fun freshTemporaryName(source: String): String {
        var candidate = "__usage_compat_value"
        while (candidate in source) candidate += "_"
        return candidate
    }

    private enum class OperatorKind {
        OPTIONAL_PROPERTY,
        NULLISH
    }

    private data class Operators(
        val optionalOffsets: Set<Int>,
        val nullishOffsets: Set<Int>
    )

    private class AstSpanRewriter(
        private val source: String,
        private val marked: IdentityHashMap<AstNode, OperatorKind>,
        private val temporaryName: String
    ) {
        private data class Rewrite(
            val markedNode: AstNode,
            val spanRoot: AstNode,
            val optionalChainRoot: AstNode? = null,
            val groupedCall: FunctionCall? = null
        )

        private val rewrites = marked.keys.map { node ->
            when (marked[node]) {
                OperatorKind.OPTIONAL_PROPERTY -> {
                    val chainRoot = continuousOptionalChainRoot(node)
                    val groupedCall = groupedCallAfter(chainRoot)
                    Rewrite(
                        markedNode = node,
                        spanRoot = groupedCall ?: chainRoot,
                        optionalChainRoot = chainRoot,
                        groupedCall = groupedCall
                    )
                }

                OperatorKind.NULLISH -> Rewrite(markedNode = node, spanRoot = node)
                null -> error("Unmarked compatibility node")
            }
        }

        fun rewrite(): String = renderRange(0, source.length)

        private fun renderRange(start: Int, end: Int): String {
            val outermost = rewrites.filter { rewrite ->
                rewrite.spanRoot.start >= start &&
                    rewrite.spanRoot.end <= end &&
                    rewrites.none { parent ->
                        parent !== rewrite &&
                            parent.spanRoot.start >= start &&
                            parent.spanRoot.end <= end &&
                            parent.spanRoot.start <= rewrite.spanRoot.start &&
                            parent.spanRoot.end >= rewrite.spanRoot.end
                }
            }
            if (outermost.isEmpty()) return source.substring(start, end)

            val rendered = StringBuilder(source.substring(start, end))
            outermost.sortedByDescending { it.spanRoot.absolutePosition }.forEach { rewrite ->
                rendered.replace(
                    rewrite.spanRoot.start - start,
                    rewrite.spanRoot.end - start,
                    renderMarked(rewrite)
                )
            }
            return rendered.toString()
        }

        private fun renderMarked(rewrite: Rewrite): String = when (marked[rewrite.markedNode]) {
            OperatorKind.OPTIONAL_PROPERTY -> {
                val propertyGet = rewrite.markedNode as PropertyGet
                val target = renderNodeRange(propertyGet.target)
                val suffix = renderRange(
                    propertyGet.absoluteOperatorPosition(),
                    checkNotNull(rewrite.optionalChainRoot).end
                )
                val groupedCall = rewrite.groupedCall
                if (groupedCall == null) {
                    "((function($temporaryName){return $temporaryName == null ? " +
                        "void 0 : $temporaryName$suffix;}).call(this,($target)))"
                } else {
                    val callSuffix = renderRange(groupedCall.target.end, groupedCall.end)
                    "((function($temporaryName){return $temporaryName == null ? " +
                        "(void 0)$callSuffix : $temporaryName$suffix$callSuffix;})" +
                        ".call(this,($target)))"
                }
            }

            OperatorKind.NULLISH -> {
                val expression = rewrite.markedNode as InfixExpression
                val left = renderNodeRange(expression.left)
                val right = renderNodeRange(expression.right)
                "((function($temporaryName){return $temporaryName == null ? " +
                    "($right) : $temporaryName;}).call(this,($left)))"
            }

            null -> error("Unmarked compatibility node")
        }

        private fun continuousOptionalChainRoot(markedNode: AstNode): AstNode {
            var root = markedNode
            while (true) {
                val parent = root.parent ?: return root
                if (marked.containsKey(parent)) return root
                root = when (parent) {
                    is PropertyGet -> if (parent.target === root) parent else return root
                    is ElementGet -> if (parent.target === root) parent else return root
                    is NewExpression -> {
                        require(parent.target !== root) {
                            "Unsupported optional chaining expression"
                        }
                        return root
                    }

                    is FunctionCall -> if (
                        parent.javaClass == FunctionCall::class.java && parent.target === root
                    ) {
                        parent
                    } else {
                        return root
                    }

                    else -> return root
                }
            }
        }

        private fun groupedCallAfter(chainRoot: AstNode): FunctionCall? {
            var groupedRoot = chainRoot
            var parent = groupedRoot.parent
            var hasGrouping = false
            while (parent is ParenthesizedExpression && parent.expression === groupedRoot) {
                hasGrouping = true
                groupedRoot = parent
                parent = groupedRoot.parent
            }
            if (!hasGrouping) return null
            return (parent as? FunctionCall)?.takeIf { call ->
                call.javaClass == FunctionCall::class.java && call.target === groupedRoot
            }
        }

        private fun renderNodeRange(node: AstNode): String =
            renderRange(node.start, node.end)

        private val AstNode.start: Int
            get() = absolutePosition

        private val AstNode.end: Int
            get() = absolutePosition + length
    }

    private class OperatorScanner(
        private val source: String
    ) {
        private val optionalOffsets = linkedSetOf<Int>()
        private val nullishOffsets = linkedSetOf<Int>()
        private var index = 0
        private var canEndExpression = false

        fun scan(): Operators {
            while (index < source.length) {
                when (val character = source[index]) {
                    ' ', '\t', '\r', '\n' -> index++
                    '\'', '"' -> {
                        scanQuoted(character)
                        canEndExpression = true
                    }

                    '`' -> {
                        scanQuoted(character)
                        canEndExpression = true
                    }

                    '/' -> scanSlash()
                    '?' -> scanQuestion()
                    else -> when {
                        character.isIdentifierStart() -> scanIdentifier()
                        character.isDigit() -> scanNumber()
                        character == ')' || character == ']' || character == '}' -> {
                            index++
                            canEndExpression = true
                        }

                        character == '+' || character == '-' -> scanPlusOrMinus(character)
                        else -> {
                            index++
                            canEndExpression = false
                        }
                    }
                }
            }
            return Operators(optionalOffsets, nullishOffsets)
        }

        private fun scanQuoted(quote: Char) {
            index++
            while (index < source.length) {
                when (source[index]) {
                    '\\' -> index = minOf(index + 2, source.length)
                    quote -> {
                        index++
                        return
                    }

                    else -> index++
                }
            }
        }

        private fun scanSlash() {
            when (source.getOrNull(index + 1)) {
                '/' -> {
                    index += 2
                    while (index < source.length && source[index] !in setOf('\r', '\n')) index++
                }

                '*' -> {
                    index += 2
                    while (index + 1 < source.length &&
                        !(source[index] == '*' && source[index + 1] == '/')
                    ) {
                        index++
                    }
                    index = minOf(index + 2, source.length)
                }

                else -> if (canEndExpression) {
                    index++
                    canEndExpression = false
                } else {
                    scanRegularExpression()
                    canEndExpression = true
                }
            }
        }

        private fun scanRegularExpression() {
            index++
            var inCharacterClass = false
            while (index < source.length) {
                when (source[index]) {
                    '\\' -> index = minOf(index + 2, source.length)
                    '[' -> {
                        inCharacterClass = true
                        index++
                    }

                    ']' -> {
                        inCharacterClass = false
                        index++
                    }

                    '/' -> if (!inCharacterClass) {
                        index++
                        while (index < source.length && source[index].isIdentifierPart()) index++
                        return
                    } else {
                        index++
                    }

                    '\r', '\n' -> return
                    else -> index++
                }
            }
        }

        private fun scanQuestion() {
            when (source.getOrNull(index + 1)) {
                '?' -> {
                    require(source.getOrNull(index + 2) != '=') {
                        "Nullish assignment is not supported"
                    }
                    nullishOffsets += index
                    index += 2
                    canEndExpression = false
                }

                '.' -> {
                    var propertyStart = index + 2
                    while (source.getOrNull(propertyStart)?.isWhitespace() == true) propertyStart++
                    require(source.getOrNull(propertyStart)?.isIdentifierStart() == true) {
                        "Only optional property access is supported"
                    }
                    optionalOffsets += index
                    index += 2
                    canEndExpression = false
                }

                else -> {
                    index++
                    canEndExpression = false
                }
            }
        }

        private fun scanIdentifier() {
            val start = index
            index++
            while (source.getOrNull(index)?.isIdentifierPart() == true) index++
            canEndExpression = source.substring(start, index) !in EXPRESSION_PREFIX_KEYWORDS
        }

        private fun scanNumber() {
            index++
            while (index < source.length &&
                (source[index].isLetterOrDigit() || source[index] in setOf('.', '_'))
            ) {
                index++
            }
            canEndExpression = true
        }

        private fun scanPlusOrMinus(character: Char) {
            if (source.getOrNull(index + 1) == character) {
                index += 2
                canEndExpression = true
            } else {
                index++
                canEndExpression = false
            }
        }

        private fun Char.isIdentifierStart(): Boolean =
            this == '$' || this == '_' || Character.isJavaIdentifierStart(this)

        private fun Char.isIdentifierPart(): Boolean =
            this == '$' || this == '_' || Character.isJavaIdentifierPart(this)

        private companion object {
            val EXPRESSION_PREFIX_KEYWORDS = setOf(
                "await",
                "case",
                "catch",
                "const",
                "delete",
                "do",
                "else",
                "for",
                "function",
                "if",
                "in",
                "instanceof",
                "let",
                "new",
                "of",
                "return",
                "switch",
                "throw",
                "typeof",
                "var",
                "void",
                "while",
                "with",
                "yield"
            )
        }
    }
}
