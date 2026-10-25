version = 20

cloudstream {
    authors     = listOf("patr0n", "ByAyzen", "kraptor")
    language    = "tr"
    description = "En yeni ve popüler filmler; Türkçe dublaj ve altyazı, Full HD."
    status      = 1
    tvTypes     = listOf("Movie", "TvSeries")
    iconUrl     = "https://www.google.com/s2/favicons?domain=hdfilmdelisi.one&sz=%size%"
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
