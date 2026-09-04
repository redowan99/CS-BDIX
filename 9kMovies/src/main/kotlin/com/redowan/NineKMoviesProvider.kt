package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class NineKMoviesProvider : MainAPI() {
    override var mainUrl = "https://9kmovies.llc"
    open val backupUrl = "https://9kmovies.int.in"
    override var name = "9kMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/category/bollywood-top-movies/" to "Bollywood",
        "/category/dual-audio-hindi-in-english/" to "Dual Audio",
        "/category/hindi-dubbed-in-tamil-telugu/" to "Hindi Dubbed",
        "/category/hollywood-latest-movies/" to "Hollywood",
        "/category/bengali-hd-movie/" to "Bengali",
        "/category/tamil-in-hindi-movies/" to "Tamil",
        "/category/telugu-all-latest-movie/" to "Telugu",
        "/category/web-series-nf-amzn-zee5/" to "Web Series",
        "/category/tv-shows-indian-tv/" to "TV Shows & WWE",
        "/category/18-movie-hd/" to "18+ Movies"
    )

    private suspend fun fetchDoc(urlPath: String): Document {
        val targetUrl = if (urlPath.startsWith("http")) urlPath else "$mainUrl$urlPath"
        return try {
            app.get(targetUrl, referer = "$mainUrl/").document
        } catch (_: Exception) {
            val fallbackUrl = if (urlPath.startsWith("http")) {
                urlPath.replace("9kmovies.llc", "9kmovies.int.in")
            } else {
                "$backupUrl$urlPath"
            }
            app.get(fallbackUrl, referer = "$backupUrl/").document
        }
    }

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val path = if (request.data.isBlank()) "/page/$page/" else "${request.data}page/$page/"
        val doc = fetchDoc(path)
        val home = doc.select(".thumb").mapNotNull { toResult(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun toResult(post: Element): SearchResponse? {
        val aTag = post.selectFirst("figcaption a, a") ?: return null
        val url = fixUrl(aTag.attr("href"))
        val title = aTag.text().ifBlank { post.selectFirst("img")?.attr("alt") ?: "" }
        if (title.isBlank() || url.isBlank()) return null
        val imgTag = post.selectFirst("img")
        val imageUrl = imgTag?.attr("src")?.ifBlank { imgTag.attr("data-src") }
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = imageUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchDoc("/search/$query")
        val searchResponse = doc.select(".thumb")
        return searchResponse.mapNotNull { toResult(it) }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)
        val title = doc.selectFirst("h1.page-title > span.material-text, h1.page-title, h1, h2, .entry-title")?.text()?.trim() ?: name
        val imageUrl = doc.selectFirst(".page-body img, .entry-content img, article img")?.attr("src")
        
        val story = doc.select(".page-body p, .entry-content p, article p").mapNotNull { p ->
            val cleanHtml = p.html()
                .replace(Regex("(?i)<br\\s*/?>"), "<br>")
                .replace(Regex("""</?(?!br\b)[a-z1-6]+[^>]*>""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleanHtml.isNotBlank() && !cleanHtml.startsWith("1080") && !cleanHtml.startsWith("720") && !cleanHtml.startsWith("480")) {
                cleanHtml
            } else null
        }.joinToString("<br><br>")

        val episodesData = mutableListOf<Episode>()
        
        // Find all quality download links (pointing to uptobhai or direct download links)
        val qualityLinks = doc.select("a[href*='uptobhai.blog'], a[href*='view'], a.buttn.direct")
        
        qualityLinks.forEach { aTag ->
            val href = aTag.attr("href")
            val linkText = aTag.text().trim()
            if (href.isNotBlank()) {
                episodesData.add(
                    newEpisode(href) {
                        this.name = if (linkText.isNotBlank()) linkText else "Download Link"
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
            this.posterUrl = imageUrl
            this.plot = story.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("uptobhai.blog")) {
            val pageRes = try {
                app.get(data, referer = "$mainUrl/")
            } catch (_: Exception) {
                app.get(data, referer = "$backupUrl/")
            }
            val form = pageRes.document.selectFirst("form")
            
            val params = mutableMapOf<String, String>()
            form?.select("input")?.forEach { input ->
                val name = input.attr("name")
                val value = input.attr("value")
                if (name.isNotBlank()) {
                    params[name] = value
                }
            }

            val unlockedDoc = if (params.isNotEmpty()) {
                app.post(data, params = params, referer = data).document
            } else {
                pageRes.document
            }

            unlockedDoc.select("a[href]").forEach { aTag ->
                val link = aTag.attr("href")
                if (link.startsWith("http") && !link.contains("uptobhai.blog")) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            }
        } else {
            val doc = try {
                app.get(data, referer = "$mainUrl/").document
            } catch (_: Exception) {
                app.get(data, referer = "$backupUrl/").document
            }
            doc.select("a[href]").forEach { aTag ->
                val link = aTag.attr("href")
                if (link.startsWith("http")) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}