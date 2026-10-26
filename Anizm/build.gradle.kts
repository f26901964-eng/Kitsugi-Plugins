version = 7

cloudstream {
    authors     = listOf("hexated", "byayzen")
    language    = "tr"
    description = "Anizm (anizm.net / puffytr.com) anime arşivi: isim eşleştirmeli arama, kategoriler, detay ve bölümler; tüm fansub/video kaynaklarıyla oynatıcı çözücü."

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
}
