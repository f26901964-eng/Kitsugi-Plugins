version = 103

cloudstream {
    authors     = listOf("keyiflerolsun", "muratcesmecioglu")
    language    = "tr"
    description = "en yeni dizileri güvenli ve hızlı şekilde sunar."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dizipal1593.com&sz=%size%"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    testOptions {
        // android.util.Log gibi platform çağrıları ağsız testlerde no-op dönsün
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
}
