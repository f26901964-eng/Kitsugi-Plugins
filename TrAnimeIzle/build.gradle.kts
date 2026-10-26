version = 31

cloudstream {
    authors     = listOf("kraptor", "byayzen")
    language    = "tr"
    description = "TrAnimeIzle anime arşivi: isim eşleştirmeli arama, ana sayfa kategorileri, Kitsu ile zenginleştirilmiş detay ve bölümler; tüm fansub/kalite kaynakları ve OpTraco/Hdvid/Hexload oynatıcı çözücüleri."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=tranimeizle.io&sz=%size%"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
