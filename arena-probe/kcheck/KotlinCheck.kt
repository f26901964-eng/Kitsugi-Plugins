import com.keyiflerolsun.PlayerDecoder
import java.io.File

private fun q(s: String?): String = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

fun main(args: Array<String>) {
    val dir = File(args[0])
    val manifest = File(dir, "manifest.json").readText()
    val re = Regex("\"label\": \"([^\"]+)\",\\s*\"file\": \"([^\"]+)\",\\s*\"url\": \"([^\"]+)\"")
    val out = StringBuilder("[\n")
    var first = true
    for (m in re.findAll(manifest)) {
        val (label, file, url) = m.destructured
        val html = File(dir, file).readText()
        val t0 = System.nanoTime()
        val a = try { PlayerDecoder.decode(html, url) } catch (e: Throwable) { null }
        val t1 = System.nanoTime()
        val b = try { PlayerDecoder.decode(html, url, targeted = false) } catch (e: Throwable) { null }
        val t2 = System.nanoTime()
        if (!first) out.append(",\n")
        first = false
        out.append("{\"label\": ${q(label)}, \"url\": ${q(url)}, \"file\": ${q(a?.file)}, \"method\": ${q(a?.method)}, " +
            "\"fallback\": ${q(b?.file)}, \"tracks\": ${a?.tracks?.size ?: 0}, \"ms\": ${(t1 - t0) / 1_000_000}, \"ms_fallback\": ${(t2 - t1) / 1_000_000}}")
        println("$label ${a?.method} ${a?.file?.take(110)} (${(t1 - t0) / 1_000_000}ms)")
    }
    out.append("\n]\n")
    File(dir, "kotlin_results.json").writeText(out.toString())
}
