package com.attf.multisite

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Bypasses uprot.net short links to get the real streaming URL
 */
class UprotBypass : ExtractorApi() {
    override var name = "Uprot"
    override var mainUrl = "https://uprot.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UprotBypass", "Bypassing: $url")

            var currentUrl = url
            var attempts = 0
            val maxAttempts = 5

            while (currentUrl.contains("uprot.net") && attempts < maxAttempts) {
                currentUrl = bypassUprot(currentUrl) ?: break
                attempts++
                Log.d("UprotBypass", "Attempt $attempts: $currentUrl")
            }

            if (currentUrl.contains("uprot.net")) {
                Log.e("UprotBypass", "Failed to bypass after $attempts attempts")
                return
            }

            Log.d("UprotBypass", "Final URL: $currentUrl")

            loadExtractor(currentUrl, referer, subtitleCallback, callback)

        } catch (e: Exception) {
            Log.e("UprotBypass", "Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun bypassUprot(link: String): String? {
        return try {
            val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )

            val response = app.get(updatedLink, headers = headers, timeout = 10_000)
            val document = Jsoup.parse(response.text)

            val nextUrl = document.selectFirst("a[href]")?.attr("abs:href")

            if (nextUrl != null && nextUrl.startsWith("http")) {
                Log.d("UprotBypass", "Found next URL: $nextUrl")
                nextUrl
            } else {
                Log.e("UprotBypass", "No URL found in response")
                null
            }
        } catch (e: Exception) {
            Log.e("UprotBypass", "Bypass error: ${e.message}")
            null
        }
    }
}

/**
 * Bypasses clicka.cc/stayonline short links
 */
class ClickaBypass : ExtractorApi() {
    override var name = "Clicka"
    override var mainUrl = "https://clicka.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("PROVA", "Sono entrato")
            Log.d("PROVA ClickaBypass", "Bypassing: $url")

            val realUrl = bypassClicka(url)

            if (realUrl != null) {
                Log.d("PROVA ClickaBypass", "Final URL: $realUrl")
                loadExtractor(realUrl, referer, subtitleCallback, callback)
            } else {
                Log.e("PROVA ClickaBypass", "Failed to bypass")
            }

        } catch (e: Exception) {
            Log.e("PROVA ClickaBypass", "Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun bypassClicka(link: String): String? {
        return try {
            val id = link.split("/").dropLast(1).lastOrNull() ?: return null

            val headers = mapOf(
                "origin" to "https://clicka.cc",
                "referer" to link,
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
                "x-requested-with" to "XMLHttpRequest"
            )

            val data = "id=$id&ref="

            val response = app.post(
                "https://clicka.cc/ajax/linkEmbedView.php",
                headers = headers,
                requestBody = data.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()),
                timeout = 10_000
            )
            //TODO: Capire cosa voleva fare claude
            val jsonResponse = response.text
            Log.d("PROVA ClickaBypass", "Response: $jsonResponse")

            val json = JSONObject(jsonResponse)
            //Qui ti ritorna una stringa che cerca di convertire in object
            val realUrl = json.optJSONObject("data")?.optString("value")

            if (realUrl != null && realUrl.startsWith("http")) {
                Log.d("PROVA ClickaBypass", "Found URL: $realUrl")
                realUrl
            } else {
                Log.e("PROVA ClickaBypass", "No URL in response")
                null
            }
        } catch (e: Exception) {
            Log.e("PROVA ClickaBypass", "Bypass error: ${e.message}")
            null
        }
    }
}