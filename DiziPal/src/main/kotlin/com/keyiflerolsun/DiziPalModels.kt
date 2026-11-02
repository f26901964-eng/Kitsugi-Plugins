// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

/**
 * Sitenin RSC (`self.__next_f.push`) akışından çıkarılan ham modeller.
 * Ayrıştırma `DiziPalParser` içinde; bu dosya yalnızca veri taşır.
 */
data class DiziPalSource(
    val label: String?,
    val quality: String?,
    val href: String?,
    val dub: Boolean?,
)

data class DiziPalPlayer(
    val kind: String?,
    val contentId: Long?,
    val title: String?,
    val language: String?,
    val sources: List<DiziPalSource>,
    val ciphertext: String?,
    val iv: String?,
    val salt: String?,
    val key: String?,
)

data class DiziPalCard(
    val title: String,
    val href: String,
    val poster: String?,
    val year: Int?,
    val meta: String?,
    val isMovie: Boolean,
)

data class DiziPalEpisodeRef(
    val title: String,
    val href: String,
    val season: Int?,
    val episode: Int?,
    val duration: Int?,
)
