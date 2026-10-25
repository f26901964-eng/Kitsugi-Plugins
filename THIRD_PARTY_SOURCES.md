# Vendored plugin source and attribution

These provider implementations are checked into this repository and are built from the Kotlin files in their plugin modules. The repository's `LICENSE` is GPL-3.0.

## FilmEkseni

- Source: [blackhope01/cloudstream-plugins](https://github.com/blackhope01/cloudstream-plugins), `FilmEkseni/` module.
- Vendored revision: `0cf7b0a7ff33364590b2a367e9e51ff85b9c5b49`.
- The upstream README declares GPL-3.0-or-later; the source is redistributed under the compatible GPL-3.0 terms in this repository.
- The provider and its EksenLoad/VidMoly extractors are in `FilmEkseni/src/main/kotlin/`.
- Local version/domain configuration: `FilmEkseni/build.gradle.kts` and `FilmEkseni/src/main/kotlin/com/blackhope01/FilmEkseniConfig.kt`; keep the domain in sync with `eklenti_domainleri.txt`.

## HDFilmDelisi

- Source: [burak-ayd/cloudstream-repo](https://github.com/burak-ayd/cloudstream-repo), `HDFilmDelisi/` module.
- Vendored revision: `0485f6d55e3157d861e712a67f1c09d719c8800e`.
- The upstream repository is GPL-3.0; its notice and original Kraptor attribution are retained.
- Provider, VidMody extractor, subtitle helpers, models, and TMDB helper are in `HDFilmDelisi/src/main/kotlin/com/patron/`.
- The upstream runtime domain lookup was removed. The locally maintained domain is `HDFilmDelisi/src/main/kotlin/com/patron/HDFilmDelisiConfig.kt`, and it should stay in sync with `eklenti_domainleri.txt`.

## Runtime network calls

Keeping the implementation in this repository removes dependence on another plugin repository for source code or domain-list updates. Playback still has to contact the movie sites, their video-hosting/player endpoints, and (where configured) metadata and subtitle services; those endpoints are where the actual media and subtitle files are served.

## Verification limits

The added unit tests exercise local parsing helpers with synthetic snippets; they are not fixtures captured from today's live pages. Current HTML selectors, real video URLs, delivered quality/audio tracks, and subtitle responses have not been live-verified. This checkout also has no Java runtime, so the Gradle compile and unit-test tasks could not be run here; CI should run them after these changes are pushed.
