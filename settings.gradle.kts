rootProject.name = "CloudstreamPlugins"

// Bu dosya hangi projelerin dahil edilecegini belirler.
//
// 2026-10 duzeltmesi:
//   Onceden depodaki TUM eklenti dizinleri derleniyordu. Uygulamada artik sadece
//   enabled_plugins.txt icindeki kaynaklar kullanilacagi icin geri kalanlar
//   burada devre disi birakiliyor. Boylece:
//     * CI sureleri kisaliyor,
//     * derlenemeyen/desteklenmeyen eklentiler tum derlemeyi bozmuyor,
//     * eklenti kaynaklari SILINMIYOR — sadece derlenmiyor.
//
//   Devre disi bir eklentiyi geri acmak icin adini enabled_plugins.txt'e eklemek yeterli.
//   enabled_plugins.txt yoksa (eski davranis) tum eklentiler derlenir.

val enabledFile = File(rootDir, "enabled_plugins.txt")

val enabledPlugins: Set<String>? =
    if (enabledFile.exists()) {
        enabledFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    } else {
        null
    }

// Bu dizinler her zaman haric tutulur (derlenebilir eklenti degiller).
val alwaysDisabled = setOf("__Temel")

File(rootDir, ".").eachDir { dir ->
    val isPlugin = File(dir, "build.gradle.kts").exists()
    val isEnabled = enabledPlugins == null || enabledPlugins.contains(dir.name)

    if (isPlugin && !alwaysDisabled.contains(dir.name) && isEnabled) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}


// Tek bir eklenti uzerinde calismak icin ustteki otomatik dahil etmeyi kapatip
// dogrudan include("PluginName") kullanabilirsiniz.
