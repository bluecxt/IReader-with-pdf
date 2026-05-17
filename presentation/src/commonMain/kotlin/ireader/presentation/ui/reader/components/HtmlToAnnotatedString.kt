package ireader.presentation.ui.reader.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Converts a simple HTML string to an AnnotatedString for Compose.
 * Supports basic tags like <i>, <em>, <b>, <strong>, <u>.
 */
fun String.htmlToAnnotatedString(): AnnotatedString {
    // If no HTML tags are present, return plain AnnotatedString for performance
    if (!this.contains("<") || !this.contains(">")) {
        return AnnotatedString(this)
    }
    
    return try {
        val doc = Ksoup.parseBodyFragment(this)
        buildAnnotatedString {
            traverse(doc.body())
        }
    } catch (e: Exception) {
        // Fallback to plain text on parsing error
        AnnotatedString(this)
    }
}

private fun AnnotatedString.Builder.traverse(node: Node) {
    when (node) {
        is TextNode -> {
            append(node.getWholeText())
        }
        is Element -> {
            val style = when (node.tagName()) {
                "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                else -> null
            }
            
            if (style != null) {
                withStyle(style) {
                    node.childNodes().forEach { traverse(it) }
                }
            } else {
                node.childNodes().forEach { traverse(it) }
            }
            
            // Note: We don't handle block elements like <p> or <div> with extra newlines here
            // because IReader's reader model is already paragraph-based.
            if (node.tagName() == "br") {
                append("\n")
            }
        }
        else -> {
            node.childNodes().forEach { traverse(it) }
        }
    }
}
