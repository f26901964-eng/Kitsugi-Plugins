version = 10

cloudstream {
    authors     = listOf("nikyokki")
    language    = "tr"
    description = "HDFilmİzle'de Türkçe film ve dizi kataloğu."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilmizle.to&sz=%size%"
}

android {
    namespace = "com.nikyokki"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
