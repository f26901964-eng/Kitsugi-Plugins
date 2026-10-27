version = 17

cloudstream {
    authors     = listOf("hexated", "byayzen")
    language    = "tr"
    description = "Anizm (anizm.com.tr / anizm.net) anime arşivi: isim eşleştirmeli arama, kategoriler, detay ve bölümler; gerçek site dökümleriyle doğrulanmış tam fansub/video kaynağı kapsamı ve packed FirePlayer (AnizmPlayer) çözücü."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status      = 1
    tvTypes     = listOf("Anime")
    iconUrl     = "https://www.google.com/s2/favicons?domain=anizm.net&sz=%size%"
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Ana kaynak coroutines-android kullanıyor; JVM birim testlerinde runBlocking
    // için çekirdek JVM sınıfları test çalışma zamanına ayrıca eklenir.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

// Canlı prob (LiveChainProbeTest) çıktısı CI günlüğünde/PR yorumunda görünsün.
tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    outputs.upToDateWhen { false }
}
