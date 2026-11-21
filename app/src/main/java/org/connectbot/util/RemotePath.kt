/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.util

/** Remote filesystem paths pulled out of terminal text, selections, and sftp/file links. */
object RemotePath {
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '\'', '"', '`', '$', '#')
    private val webUrl = Regex("""(?i)\b(?:https?|ftp)://\S+""")
    private val token = Regex("""(?:~|\.\./|\./|/)[^\s<>"'`|]+""")

    fun extract(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        normalize(trimmed)?.let { return it }
        return findAll(trimmed).firstOrNull()
    }

    fun findAll(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return token.findAll(webUrl.replace(text, " "))
            .mapNotNull { normalize(it.value) }
            .distinct()
            .toList()
    }

    private fun normalize(raw: String): String? {
        var value = raw.trim().trim('"', '\'', '`')
        if (value.isEmpty()) return null
        val schemeSep = value.indexOf("://")
        if (schemeSep >= 0) {
            val scheme = value.substring(0, schemeSep)
            if (!scheme.equals("sftp", true) && !scheme.equals("file", true)) return null
            val rest = value.substring(schemeSep + 3)
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            value = rest.substring(slash)
        }
        while (value.length > 1 && value.last() in trailing) {
            value = value.dropLast(1)
        }
        val absolute = value.startsWith("/")
        val home = value == "~" || value.startsWith("~/")
        val relative = value.startsWith("./") || value.startsWith("../")
        if (!absolute && !home && !relative) return null
        if (value == "." || value == ".." || value == "./" || value == "../") return null
        return value
    }
}
