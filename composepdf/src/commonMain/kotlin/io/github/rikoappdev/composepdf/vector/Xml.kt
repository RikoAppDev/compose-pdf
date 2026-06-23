package io.github.rikoappdev.composepdf.vector

/**
 * A parsed XML element: its [name] (kept verbatim, including any `android:`/namespace prefix),
 * its [attrs] (attribute names verbatim, values entity-unescaped) and child elements. Text content
 * is ignored — VectorDrawable and SVG carry all geometry in attributes, not text nodes.
 */
internal class XmlNode(
    val name: String,
    val attrs: Map<String, String>,
    val children: List<XmlNode>,
) {
    /** Attribute by exact name, or by local name ignoring any `prefix:` (so `android:width` matches `width`). */
    fun attr(name: String): String? =
        attrs[name] ?: attrs.entries.firstOrNull { it.key.substringAfter(':') == name }?.value
}

/**
 * A minimal, dependency-free XML reader sufficient for VectorDrawable and simple SVG: elements,
 * attributes (single- or double-quoted), self-closing tags, comments, the XML/PI prolog, CDATA and
 * DOCTYPE are skipped. Namespaces are not resolved — prefixed names are read literally. Returns the
 * single root element. Throws [IllegalArgumentException] on malformed markup.
 */
internal fun parseXml(input: String): XmlNode {
    val s = input
    var i = 0
    if (s.isNotEmpty() && s[0] == '﻿') i = 1 // strip UTF-8 BOM marker

    class Builder(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<XmlNode>()
        fun build(): XmlNode = XmlNode(name, attrs, children)
    }

    val stack = ArrayList<Builder>()
    var root: XmlNode? = null

    fun skipUntil(marker: String) {
        val idx = s.indexOf(marker, i)
        i = if (idx < 0) s.length else idx + marker.length
    }

    fun isNameChar(c: Char) = !c.isWhitespace() && c != '>' && c != '/' && c != '='

    while (i < s.length) {
        // Advance to the next tag.
        if (s[i] != '<') { i++; continue }
        when {
            s.startsWith("<!--", i) -> { i += 4; skipUntil("-->") }
            s.startsWith("<![CDATA[", i) -> { i += 9; skipUntil("]]>") }
            s.startsWith("<!", i) -> {                            // DOCTYPE / declarations
                // A DOCTYPE with an internal subset ("<!DOCTYPE svg [ <!ENTITY ...> ]>") contains
                // '>' inside the brackets, so skip to "]>" in that case; otherwise to the first '>'.
                i += 2
                val gt = s.indexOf('>', i)
                val br = s.indexOf('[', i)
                if (br in 0 until gt) skipUntil("]>") else skipUntil(">")
            }
            s.startsWith("<?", i) -> { i += 2; skipUntil("?>") }  // <?xml ...?>
            s.startsWith("</", i) -> {                            // closing tag
                i += 2
                val end = s.indexOf('>', i)
                require(end >= 0) { "Unterminated closing tag" }
                i = end + 1
                val done = stack.removeAt(stack.lastIndex).build()
                if (stack.isEmpty()) { root = done } else { stack.last().children.add(done) }
            }
            else -> {                                            // opening / self-closing tag
                i++ // past '<'
                val nameStart = i
                while (i < s.length && isNameChar(s[i])) i++
                val name = s.substring(nameStart, i)
                val attrs = LinkedHashMap<String, String>()
                var selfClose = false
                // Attributes until '>' or '/>'.
                while (i < s.length) {
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i >= s.length) break
                    when (s[i]) {
                        '>' -> { i++; break }
                        '/' -> {
                            // Self-close only if the next non-space char is '>'; a stray '/' (e.g. in
                            // an unquoted value) must not flip selfClose. Consume just the '/'.
                            var j = i + 1
                            while (j < s.length && s[j].isWhitespace()) j++
                            if (j < s.length && s[j] == '>') selfClose = true
                            i++
                        }
                        else -> {
                            val aStart = i
                            while (i < s.length && isNameChar(s[i])) i++
                            val aName = s.substring(aStart, i)
                            while (i < s.length && s[i].isWhitespace()) i++
                            if (i < s.length && s[i] == '=') {
                                i++
                                while (i < s.length && s[i].isWhitespace()) i++
                                val quote = if (i < s.length) s[i] else '"'
                                if (quote == '"' || quote == '\'') {
                                    i++
                                    val vStart = i
                                    while (i < s.length && s[i] != quote) i++
                                    val raw = s.substring(vStart, minOf(i, s.length))
                                    if (i < s.length) i++ // past closing quote
                                    if (aName.isNotEmpty()) attrs[aName] = unescape(raw)
                                }
                            } else if (aName.isNotEmpty()) {
                                attrs[aName] = "" // valueless attribute
                            }
                        }
                    }
                }
                val b = Builder(name, attrs)
                if (selfClose) {
                    val done = b.build()
                    if (stack.isEmpty()) { if (root == null) root = done } else { stack.last().children.add(done) }
                } else {
                    stack.add(b)
                }
            }
        }
    }
    return root ?: throw IllegalArgumentException("No XML root element found")
}

/** Unescapes the five predefined XML entities plus numeric character references. */
private fun unescape(s: String): String {
    if (s.indexOf('&') < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') {
            val semi = s.indexOf(';', i)
            if (semi > i) {
                val ent = s.substring(i + 1, semi)
                val rep = when {
                    ent == "amp" -> "&"
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") || ent.startsWith("#X") ->
                        ent.substring(2).toIntOrNull(16)?.let { cpToString(it) }
                    ent.startsWith("#") ->
                        ent.substring(1).toIntOrNull()?.let { cpToString(it) }
                    else -> null
                }
                if (rep != null) { sb.append(rep); i = semi + 1; continue }
            }
        }
        sb.append(c); i++
    }
    return sb.toString()
}

private fun cpToString(cp: Int): String =
    if (cp <= 0xFFFF) cp.toChar().toString()
    else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
