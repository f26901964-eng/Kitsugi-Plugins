package com.patron

/** Extracts the VidMody iframe and HLS references embedded in provider/player HTML. */
internal object HDFilmDelisiPlayerParser {
    private val vidModyUrlPattern = Regex(
        """(https?:[\\/]+player\.vidmody\.com[\\/][^"'\s<>]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val hlsUrlPattern = Regex(
        """["']?file["']?\s*:\s*["'](https?:[\\/]+[^"']+)""",
        RegexOption.IGNORE_CASE,
    )

    fun extractVidModyUrl(source: String): String? = vidModyUrlPattern
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.trimEnd(',', ';', ')')
        ?.normalizeEscapedUrl()

    fun extractHlsUrl(decryptedPlayerScript: String): String? = hlsUrlPattern
        .find(decryptedPlayerScript)
        ?.groupValues
        ?.getOrNull(1)
        ?.normalizeEscapedUrl()

    private fun String.normalizeEscapedUrl(): String = replace("\\/", "/")
        .replace("\\", "")
}
