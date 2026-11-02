package com.kraptor

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Yaş kapısı formu: nereye, hangi alanlarla gönderilecek. */
internal data class TrAgeGateForm(val action: String, val fields: Map<String, String>)

/**
 * +18 / yaş doğrulama kapısı.
 *
 * Sitedeki +18 uyarısı **istemci tarafında** bir modal olarak duruyor
 * (`localStorage`'da "hatırla" seçeneği); eklenti HTML/JS çalıştırmadığı için
 * bu uyarı hiçbir zaman görünmüyor. Yine de sunucu taraflı bir kapıya
 * düşülürse (form + onay) burada otomatik onaylanır.
 *
 * Kasıtlı olarak **dar** bir tespit: yalnızca gövdede `18+` geçen, yaş/reşit
 * ifadesi bulunan ve `form` içeren bir sayfada devreye girer. Arama formu gibi
 * alakasız formlara dokunmamak için form (veya ebeveyni) `age/yas/18/adult`
 * işareti taşımalı.
 */
internal object TrAgeGate {

    private val AGE_MARKER = Regex("(age|yas|18|adult|resit|reşit)", RegexOption.IGNORE_CASE)

    fun detect(html: String?, base: String): TrAgeGateForm? {
        val raw = html ?: return null
        if (raw.isBlank()) return null
        val lower = raw.lowercase()
        if (!lower.contains("18+")) return null
        val mentionsAge = lower.contains("yaş") || lower.contains("yas") ||
            lower.contains("reşit") || lower.contains("resit") || lower.contains("age verification")
        if (!mentionsAge) return null

        val doc = runCatching { Jsoup.parse(raw, base) }.getOrNull() ?: return null
        for (form in doc.select("form")) {
            val action = form.absUrl("action").ifBlank { form.attr("action").trim() }
            val marked = isAgeContainer(form) || (action.isNotEmpty() && AGE_MARKER.containsMatchIn(action))
            if (!marked) continue
            val fields = fieldsOf(form)
            return TrAgeGateForm(action.ifBlank { base }, fields)
        }
        return null
    }

    /** Form ya da 4 seviyeye kadar ebeveyni yaş kapısı işareti taşıyor mu? */
    private fun isAgeContainer(form: Element): Boolean {
        var node: Element? = form
        var depth = 0
        while (node != null && depth < 4) {
            val marker = node.id() + " " + node.className() + " " + node.attr("data-target")
            if (AGE_MARKER.containsMatchIn(marker)) return true
            node = node.parent()
            depth++
        }
        return false
    }

    /** Gönderilecek alanlar: gizli alanlar + işaretli kutu + onay düğmesi. */
    private fun fieldsOf(form: Element): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (input in form.select("input")) {
            val name = input.attr("name").trim()
            if (name.isEmpty()) continue
            when (input.attr("type").lowercase()) {
                "hidden" -> out[name] = input.attr("value")
                "radio", "checkbox" -> if (input.hasAttr("checked")) out[name] = input.attr("value")
                "submit" -> if (input.attr("value").isNotBlank()) out[name] = input.attr("value")
                else -> Unit // e-posta/yorum alanlarına dokunulmaz
            }
        }
        for (button in form.select("button")) {
            val name = button.attr("name").trim()
            if (name.isNotEmpty()) out[name] = button.attr("value")
        }
        return out
    }
}
