package app.ink.music

/**
 * A feed's XML read into elements (2026-11-07, shared with the iPhone, which has no DOM): elements, attributes, text and
 * CDATA; comments, processing instructions and the doctype skipped (a feed is someone else's file: nothing fetched or
 * expanded from it, only XML's own five entities and numbered characters). Forgiving the way a feed reader must be: an
 * unclosed element closed where its parent ends.
 */
class Xml private constructor(val tag: String, val attrs: Map<String, String>) {
    val children = ArrayList<Xml>()
    private val parts = ArrayList<Any>()   // text (String) and elements, in order

    /** The name without its namespace prefix ("itunes:image" is "image"). */
    val name get() = tag.substringAfter(':')

    fun attr(name: String) = attrs[name] ?: attrs.entries.firstOrNull { it.key.substringAfter(':') == name }?.value.orEmpty()

    /** The first child of that name, any namespace. */
    fun child(name: String): Xml? = children.firstOrNull { it.name == name }

    /** Every element below of that exact tag, in document order (DOM's getElementsByTagName). */
    fun all(tag: String): List<Xml> = ArrayList<Xml>().also { out -> fun walk(e: Xml) { e.children.forEach { if (it.tag == tag) out += it; walk(it) } }; walk(this) }

    /** Its text and its children's, as DOM's textContent. */
    val text: String get() = buildString { fun walk(e: Xml) { e.parts.forEach { if (it is String) append(it) else walk(it as Xml) } }; walk(this@Xml) }

    companion object {
        /** The document's root element; an error if there's none. */
        fun parse(s: String): Xml {
            val top = Xml("", emptyMap())
            val stack = ArrayList<Xml>().apply { add(top) }
            var i = 0
            val n = s.length
            while (i < n) {
                val lt = s.indexOf('<', i)
                val end = if (lt < 0) n else lt
                if (end > i && stack.size > 1) stack.last().parts += unescape(s.substring(i, end))
                if (lt < 0) break
                when {
                    s.startsWith("<!--", lt) -> i = s.indexOf("-->", lt + 4).let { if (it < 0) n else it + 3 }
                    s.startsWith("<![CDATA[", lt) -> {
                        val close = s.indexOf("]]>", lt + 9).let { if (it < 0) n else it }
                        if (stack.size > 1) stack.last().parts += s.substring(lt + 9, close)
                        i = minOf(n, close + 3)
                    }
                    s.startsWith("<?", lt) -> i = s.indexOf("?>", lt + 2).let { if (it < 0) n else it + 2 }
                    s.startsWith("<!", lt) -> i = skipDeclaration(s, lt)
                    s.startsWith("</", lt) -> {
                        val gt = s.indexOf('>', lt).let { if (it < 0) n else it }
                        val tag = s.substring(lt + 2, gt).trim()
                        // closed up to the nearest open element of that tag; a stray close ignored
                        val at = stack.indexOfLast { it.tag == tag }
                        if (at > 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                        i = gt + 1
                    }
                    else -> {
                        val (e, selfClosed, next) = openTag(s, lt)
                        stack.last().children += e; stack.last().parts += e
                        if (!selfClosed) stack += e
                        i = next
                    }
                }
            }
            return top.children.firstOrNull() ?: error("no element")
        }

        private fun skipDeclaration(s: String, at: Int): Int {
            // <!DOCTYPE ... [ internal subset ] >
            var i = at + 2
            var depth = 0
            while (i < s.length) {
                when (s[i]) { '[' -> depth++; ']' -> depth--; '>' -> if (depth <= 0) return i + 1 }
                i++
            }
            return s.length
        }

        private fun openTag(s: String, lt: Int): Triple<Xml, Boolean, Int> {
            var i = lt + 1
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            val tag = s.substring(lt + 1, i)
            val attrs = LinkedHashMap<String, String>()
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                if (s[i] == '>') return Triple(Xml(tag, attrs), false, i + 1)
                if (s[i] == '/' ) { val gt = s.indexOf('>', i).let { if (it < 0) s.length else it }; return Triple(Xml(tag, attrs), true, gt + 1) }
                val ns = i
                while (i < s.length && s[i] != '=' && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
                val key = s.substring(ns, i)
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == '=') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    val q = s.getOrNull(i)
                    if (q == '"' || q == '\'') {
                        val close = s.indexOf(q, i + 1).let { if (it < 0) s.length else it }
                        attrs[key] = unescape(s.substring(i + 1, close)); i = close + 1
                    } else {
                        val vs = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                        attrs[key] = unescape(s.substring(vs, i))
                    }
                } else if (key.isNotEmpty()) attrs[key] = ""
                else i++
            }
            return Triple(Xml(tag, attrs), false, s.length)
        }

        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos);")

        /** XML's five entities and numbered characters; anything else left as it was. */
        fun unescape(t: String): String = if ('&' !in t) t else ENTITY.replace(t) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> (if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull())?.let(::codePoint) ?: ""
            }
        }
    }
}

/** A Unicode code point as text (its surrogate pair above the first plane); nothing if it isn't one. */
fun codePoint(cp: Int): String = when {
    cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> ""
    cp < 0x10000 -> cp.toChar().toString()
    else -> (cp - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
}
