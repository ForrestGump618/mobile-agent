package com.mobileagent.core.security

/** Masks credentials before anything is written to the audit log, an export file or a chat transcript. */
object Redactor {

    private val patterns = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._\\-]+"),
        Regex("(?i)(api[_-]?key\\s*[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._\\-]{8,}"),
        Regex("\\bsk-[A-Za-z0-9_\\-]{12,}\\b"),
        Regex("\\bghp_[A-Za-z0-9]{20,}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}\\b"),
        Regex("(?i)(token\\s*[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._\\-]{12,}"),
    )

    fun redact(text: String): String {
        var result = text
        patterns.forEach { regex ->
            result = regex.replace(result) { match ->
                val prefix = match.groupValues[1]
                if (prefix.isBlank()) "[redacted]" else "$prefix[redacted]"
            }
        }
        return result
    }

    /** Keep a short, non-reversible hint so the user can tell which key is configured. */
    fun fingerprint(secret: String): String {
        if (secret.length < 8) return "****"
        return "****${secret.takeLast(4)}"
    }
}
