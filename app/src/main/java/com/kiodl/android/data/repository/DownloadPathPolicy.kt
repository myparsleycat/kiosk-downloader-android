package com.kiodl.android.data.repository

import java.text.Normalizer

internal fun sanitizeDownloadPath(path: String, asciiFilenames: Boolean): String = path
    .replace('\\', '/')
    .split('/')
    .filter(String::isNotBlank)
    .map { sanitizeDownloadPathSegment(it, asciiFilenames) }
    .joinToString("/")

// Match desktop path rules so cross-platform destinations stay consistent (Windows reserved names, etc.).
internal fun sanitizeDownloadPathSegment(input: String, asciiFilenames: Boolean): String {
    val source = if (asciiFilenames) {
        toAsciiFilename(input)
    } else input
    var sanitized = source.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_").trim()
        .trimEnd('.')
    if (sanitized.isEmpty()) sanitized = "Untitled"
    if (Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", RegexOption.IGNORE_CASE).matches(sanitized)) {
        sanitized = "_$sanitized"
    }
    return sanitized
}

// Skip wrapping when the tree already has a single root folder named like the collection.
internal fun shouldCreateCollectionSubfolder(
    tree: com.kiodl.android.domain.model.DirectoryNode,
    collectionName: String,
    enabled: Boolean,
): Boolean {
    if (!enabled || tree.entries.size <= 1) return false
    val only = tree.entries.singleOrNull() as? com.kiodl.android.domain.model.DirectoryNode
    return only?.name?.equals(collectionName, ignoreCase = true) != true
}

internal fun applyTreeRenames(path: String, renames: Map<String, String>): String {
    val original = path.replace('\\', '/').split('/').filter(String::isNotBlank)
    val display = mutableListOf<String>()
    original.forEachIndexed { index, name ->
        val originalPrefix = original.take(index + 1).joinToString("/")
        display += renames[originalPrefix]?.takeIf(String::isNotBlank) ?: name
    }
    return display.joinToString("/")
}

internal fun com.kiodl.android.domain.model.DirectoryNode.applyTreeRenames(
    renames: Map<String, String>,
    originalPrefix: List<String> = emptyList(),
): com.kiodl.android.domain.model.DirectoryNode = renameNode(this, renames, originalPrefix)
    as com.kiodl.android.domain.model.DirectoryNode

private fun renameNode(
    node: com.kiodl.android.domain.model.CollectionNode,
    renames: Map<String, String>,
    originalPrefix: List<String>,
): com.kiodl.android.domain.model.CollectionNode {
    val path = if (node.name.isEmpty()) originalPrefix else originalPrefix + node.name
    val renamed = renames[path.joinToString("/")] ?: node.name
    return when (node) {
        is com.kiodl.android.domain.model.DirectoryNode -> node.copy(
            name = renamed,
            entries = node.entries.map { renameNode(it, renames, path) },
        )
        is com.kiodl.android.domain.model.FileNode -> node.copy(name = renamed)
        is com.kiodl.android.domain.model.ZipNode -> node.copy(
            name = renamed,
            entries = node.entries?.map { renameNode(it, renames, path) },
        )
    }
}

private fun toAsciiFilename(input: String): String {
    val output = StringBuilder(input.length)
    var index = 0
    while (index < input.length) {
        val codePoint = input.codePointAt(index)
        index += Character.charCount(codePoint)
        if (codePoint in 0xac00..0xd7a3) {
            output.append(romanizeHangulSyllable(codePoint))
            continue
        }
        val normalized = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFKD)
        normalized.codePoints().forEach { normalizedCodePoint ->
            when {
                normalizedCodePoint in 0x20..0x7e -> output.appendCodePoint(normalizedCodePoint)
                normalizedCodePoint < 0x20 || normalizedCodePoint == 0x7f -> output.append('_')
                Character.getType(normalizedCodePoint) == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.getType(normalizedCodePoint) == Character.COMBINING_SPACING_MARK.toInt() -> Unit
                Character.getType(normalizedCodePoint) == Character.FORMAT.toInt() -> Unit
                else -> Unit
            }
        }
    }
    return output.toString()
}

private fun romanizeHangulSyllable(codePoint: Int): String {
    val offset = codePoint - 0xac00
    val initial = offset / (21 * 28)
    val vowel = (offset % (21 * 28)) / 28
    val final = offset % 28
    return (HANGUL_INITIALS[initial] + HANGUL_VOWELS[vowel] + HANGUL_FINALS[final])
        .replaceFirstChar(Char::uppercaseChar)
}

private val HANGUL_INITIALS = arrayOf(
    "G", "Gg", "N", "D", "Dd", "L", "M", "B", "Bb", "S", "Ss", "", "J", "Jj", "Ch", "K", "T", "P", "H",
)
private val HANGUL_VOWELS = arrayOf(
    "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "weo", "we", "wi", "yu", "eu", "yi", "i",
)
private val HANGUL_FINALS = arrayOf(
    "", "g", "gg", "gs", "n", "nj", "nh", "d", "l", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "h",
)
