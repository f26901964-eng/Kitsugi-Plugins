version = 53

cloudstream {
    authors     = listOf("blackhope01", "kraptor", "ByAyzen")
    language    = "tr"
    description = "Film Ekseni — yeni ve güncel filmler, Türkçe dublaj ve altyazı seçenekleri."
    status      = 1
    tvTypes     = listOf("Movie", "TvSeries")
    iconUrl     = "https://www.google.com/s2/favicons?domain=filmekseni.vip&sz=%size%"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
