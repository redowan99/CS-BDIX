package com.redowan

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

open class PasteUrlExtractor : ExtractorApi() {
    override var name = "PasteURL"
    override var mainUrl = "https://pasteurl.net"
    override val requiresReferer = false

    private val tag = "PasteUrlExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = if (url.startsWith("http")) url else "https://$url"
        Log.d(tag, "getUrl: pageUrl='$pageUrl'")

        val doc = try {
            app.get(pageUrl).document
        } catch (e: Exception) {
            Log.e(tag, "Error fetching pageUrl '$pageUrl': ${e.message}")
            return
        }

        val formParams = doc.select("form input[name]").associateBy({ it.attr("name") }, { it.attr("value") })
        Log.d(tag, "formParams count=${formParams.size}: $formParams")

        val unlockedDoc = if (formParams.isNotEmpty()) {
            try {
                val postRes = app.post(pageUrl, referer = pageUrl, data = formParams).document
                Log.d(tag, "Successfully unlocked links via POST")
                postRes
            } catch (e: Exception) {
                Log.e(tag, "Error unlocking form: ${e.message}")
                doc
            }
        } else {
            doc
        }

        val extractedLinks = unlockedDoc.select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") && !it.contains("pasteurl") && !it.contains("pastetot") }
            .distinct()

        Log.d(tag, "Extracted ${extractedLinks.size} links from $pageUrl: $extractedLinks")

        extractedLinks.forEach { link ->
            Log.d(tag, "Passing link to loadExtractor: '$link'")
            loadExtractor(link, pageUrl, subtitleCallback, callback)
        }
    }
}

class PasteTotExtractor : PasteUrlExtractor() {
    override var name = "PasteTot"
    override var mainUrl = "https://pastetot.com"
}

open class StreamHG : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = false

    private val tag = "StreamHG"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val fetchUrl = when {
            pageUrl.contains("hgcloud.to") -> pageUrl.replace("hgcloud.to", "vibuxer.com")
            pageUrl.contains("streamhg.com") -> pageUrl.replace("streamhg.com", "vibuxer.com")
            pageUrl.contains("streamhg.to") -> pageUrl.replace("streamhg.to", "vibuxer.com")
            else -> pageUrl
        }
        Log.d(tag, "getUrl: pageUrl='$pageUrl', fetchUrl='$fetchUrl'")

        val response = try {
            app.get(fetchUrl, referer = referer ?: mainUrl)
        } catch (e: Exception) {
            Log.e(tag, "Error fetching StreamHG '$fetchUrl': ${e.message}")
            return
        }

        val html = response.text
        val unpackedText = getAndUnpack(html)
        val textToSearch = (unpackedText.ifBlank { html }).replace("\\/", "/")

        Log.d(tag, "html len=${html.length}, unpacked len=${unpackedText.length}")

        // Subtitles extraction (.vtt)
        Regex("""https?://[^\s"'<>]+\.vtt[^\s"'<>]*""").findAll(textToSearch).forEach { match ->
            val subUrl = match.value.trim()
            val lang = if (subUrl.contains("_eng", ignoreCase = true)) "English" else "Subtitle"
            Log.d(tag, "Found subtitle: $subUrl ($lang)")
            subtitleCallback(newSubtitleFile(lang, subUrl))
        }

        // Extract m3u8 or mp4 URLs
        val streamUrls = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
            .findAll(textToSearch)
            .map { it.value.trim() }
            .distinct()
            .toList()

        Log.d(tag, "Found ${streamUrls.size} streamUrls: $streamUrls")

        val finalUrls = streamUrls.ifEmpty {
            Regex("""https?://[^\s"'<>]*(?:master|index|playlist|hls)[^\s"'<>]*""")
                .findAll(textToSearch)
                .map { it.value.trim() }
                .filter { it.contains(".m3u8") || it.contains(".mp4") || it.contains("/master") }
                .distinct()
                .toList()
        }

        Log.d(tag, "Emitting ${finalUrls.size} extractor links")
        finalUrls.forEach { streamUrl ->
            val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("/master") || streamUrl.contains("hls")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = fetchUrl
                }
            )
        }
    }
}

class Vibuxer : StreamHG() {
    override var name = "Vibuxer"
    override var mainUrl = "https://vibuxer.com"
}

class StreamHGCom : StreamHG() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.com"
}

class StreamHGTo : StreamHG() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.to"
}

open class GDFlix : ExtractorApi() {
    override var name = "GDFlix"
    override var mainUrl = "https://gdflix.io"
    override val requiresReferer = false

    private val tag = "GDFlix"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val domain = try {
            val uri = URI(pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }

        Log.d(tag, "getUrl: pageUrl='$pageUrl', domain='$domain'")

        val doc = try {
            app.get(pageUrl, referer = referer ?: mainUrl).document
        } catch (e: Exception) {
            Log.e(tag, "Error fetching GDFlix pageUrl '$pageUrl': ${e.message}")
            return
        }

        val allLinks = doc.select("div.text-center a[href]")
        Log.d(tag, "Found ${allLinks.size} links on GDFlix page")

        allLinks.forEach { a ->
            val text = a.text().trim()
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach

            val isPixeldrain = href.contains("pixeldrain.com") ||
                               href.contains("pixeldra.in") ||
                               text.contains("pixeldrain", ignoreCase = true) ||
                               text.contains("pixel", ignoreCase = true)

            val isCloudR2 = text.contains("Cloud R2", ignoreCase = true) ||
                            text.contains("R2 Cloud", ignoreCase = true) ||
                            (text.contains("Cloud", ignoreCase = true) && text.contains("R2", ignoreCase = true))

            if (isPixeldrain) {
                val targetUrl = if (href.startsWith("http")) href else "$domain$href"
                Log.d(tag, "Found Pixeldrain link text='$text', targetUrl='$targetUrl'")

                if (targetUrl.contains("pixeldrain.com") || targetUrl.contains("pixeldra.in")) {
                    loadExtractor(targetUrl, pageUrl, subtitleCallback, callback)
                } else {
                    try {
                        val targetRes = app.get(targetUrl, referer = pageUrl, timeout = 30L)
                        val targetDoc = targetRes.document
                        val pixelLink = targetDoc.select("a[href]").map { it.attr("href") }.find {
                            it.contains("pixeldrain.com") || it.contains("pixeldra.in")
                        } ?: targetRes.url

                        Log.d(tag, "Extracted Pixeldrain URL: '$pixelLink'")
                        loadExtractor(pixelLink, pageUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e(tag, "Error resolving Pixeldrain link '$targetUrl': ${e.message}")
                    }
                }
            } else if (isCloudR2) {
                val targetUrl = if (href.startsWith("http")) href else "$domain$href"
                Log.d(tag, "Found Cloud R2 link text='$text', targetUrl='$targetUrl'")

                try {
                    val targetRes = app.get(targetUrl, referer = pageUrl, timeout = 30L)
                    val targetDoc = targetRes.document

                    val directUrl = targetDoc.selectFirst("a.btn-success, a.btn-primary, a[href*='download'], a.btn")?.attr("href")
                        ?.ifBlank { null }
                        ?: targetRes.url

                    Log.d(tag, "Extracted Cloud R2 directUrl: '$directUrl'")

                    if (directUrl.isNotBlank() && directUrl.startsWith("http")) {
                        if (directUrl.contains("pixeldrain.com") || directUrl.contains("pixeldra.in")) {
                            loadExtractor(directUrl, pageUrl, subtitleCallback, callback)
                        } else {
                            callback(
                                newExtractorLink(
                                    source = "$name [Cloud R2]",
                                    name = "$name [Cloud R2]",
                                    url = directUrl,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error resolving Cloud R2 link '$targetUrl': ${e.message}")
                }
            }
        }
    }
}

class GDFlixNew1 : GDFlix() { override var mainUrl = "https://new1.gdflix.io" }
class GDFlixNew2 : GDFlix() { override var mainUrl = "https://new2.gdflix.io" }
class GDFlixNew3 : GDFlix() { override var mainUrl = "https://new3.gdflix.io" }
class GDFlixNew4 : GDFlix() { override var mainUrl = "https://new4.gdflix.io" }
class GDFlixNew10 : GDFlix() { override var mainUrl = "https://new10.gdflix.io" }
class GDFlixDev : GDFlix() { override var mainUrl = "https://gdflix.dev" }
class GDFlixCfd : GDFlix() { override var mainUrl = "https://gdflix.cfd" }
