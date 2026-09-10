package com.redowan

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

open class BanglaPlexProvider : MainAPI() {
    override var mainUrl = "https://banglaplex.biz"
    override var name = "BanglaPlex"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val tag = "BanglaPlexProvider"

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "/genre/bengali-movies.html" to "Bengali Movies",
        "/genre/bengali-web-series.html" to "Bengali Web Series",
        "/genre/bollywood-movies.html" to "Bollywood Movies",
        "/genre/south-indian-movies.html" to "South Indian Movies",
        "/genre/hollywood-movies.html" to "Hollywood Movies",
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val path = if (request.data.isBlank()) {
            if (page > 1) "/page/$page/" else "/"
        } else {
            if (page > 1) "${request.data}?page=$page" else request.data
        }
        val url = fixUrl(path)

        Log.d(tag, "getMainPage request: name='${request.name}', url='$url'")
        val doc = try {
            app.get(url, referer = "$mainUrl/", timeout = 15L, cacheTime = 60).document
        } catch (e: Exception) {
            Log.e(tag, "Error in getMainPage for url '$url': ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = doc.select(".latest-movie-img-container, .movie-img, div.movie")
            .mapNotNull { toResult(it) }
            .distinctBy { it.url }

        Log.d(tag, "getMainPage success: name='${request.name}', found ${items.size} items")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toResult(post: Element): SearchResponse? {
        val aTag = post.selectFirst("a[href*='/watch/']") ?: return null
        val url = fixUrl(aTag.attr("href"))

        val title = post.selectFirst(".movie-title, h3, h2, h1")?.text()?.trim()
            ?.ifBlank { aTag.attr("title").trim() }
            ?.ifBlank { aTag.text().trim() }
            ?.ifBlank { post.selectFirst("img")?.attr("alt")?.trim() }
            ?.takeIf { it.isNotBlank() } ?: return null

        val style = post.attr("style").ifBlank { post.selectFirst("div[style]")?.attr("style") ?: "" }
        val posterUrl = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            ?: post.selectFirst("img")?.attr("src")?.takeIf { !it.contains("logo") && !it.contains("preloader") }

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        Log.d(tag, "search request: query='$query', url='$url'")
        val doc = try {
            app.get(url, referer = "$mainUrl/", timeout = 15L).document
        } catch (e: Exception) {
            Log.e(tag, "Error in search for query '$query': ${e.message}")
            return emptyList()
        }

        val items = doc.select(".latest-movie-img-container, .movie-img, div.movie, article, div.latest-movie, div[class*='col']")
            .mapNotNull { toResult(it) }
            .distinctBy { it.url }

        Log.d(tag, "search success: query='$query', found ${items.size} items")
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(tag, "load request: url='$url'")
        val doc = try {
            app.get(url, referer = "$mainUrl/", timeout = 15L, cacheTime = 60).document
        } catch (e: Exception) {
            Log.e(tag, "Error in load for url '$url': ${e.message}")
            throw e
        }

        val title = doc.selectFirst("h1, h2.title, .movie-details h1, .single-title")?.text()?.trim() ?: name
        val posterUrl = doc.selectFirst("img.img-responsive")?.attr("src")

        // YouTube Trailer (validated to ensure non-empty video ID)
        val rawTrailer = doc.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")?.trim()
        val trailerUrl = rawTrailer?.takeIf { href ->
            when {
                href.contains("v=") -> href.substringAfter("v=").substringBefore("&").length >= 5
                href.contains("youtu.be/") -> href.substringAfter("youtu.be/").substringBefore("?").length >= 5
                else -> false
            }
        }

        // Plot / Description
        val plot = doc.select("p")
            .map { it.text().trim() }
            .find { p ->
                p.length > 20 &&
                !p.startsWith("Genre:", ignoreCase = true) &&
                !p.startsWith("Actor:", ignoreCase = true) &&
                !p.startsWith("Director:", ignoreCase = true) &&
                !p.startsWith("Writer:", ignoreCase = true) &&
                !p.startsWith("Country:", ignoreCase = true) &&
                !p.startsWith("Watch", ignoreCase = true) &&
                !p.startsWith("Quality:", ignoreCase = true) &&
                !p.startsWith("Subscribe", ignoreCase = true) &&
                !p.startsWith("Copyright", ignoreCase = true)
            }

        // Actors
        val actors = doc.select("a[href*='/star/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Year
        val year = doc.select("a[href*='/year/']")
            .firstNotNullOfOrNull { it.text().trim().toIntOrNull() }
            ?: doc.selectFirst(".label-year, .year")?.text()?.trim()?.toIntOrNull()

        // Duration
        val durationText = doc.select("p").find { it.text().contains("Duration:", ignoreCase = true) }?.text()
        val duration = durationText?.let { Regex("""(\d+)\s*Min""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Download / PasteURL links
        val downloadSection = doc.selectFirst("#download, .download, div[id*='download']") ?: doc
        val pasteLinks = downloadSection.select("a[href]")
            .map { fixUrl(it.attr("href")) to it.text().trim() }
            .filter { (href, _) -> href.contains("pasteurl") || href.contains("pastetot") || href.contains("/view/") }
            .distinctBy { it.first }

        val episodesData = mutableListOf<Episode>()

        if (pasteLinks.isNotEmpty() && pasteLinks.size > 1 && (url.contains("series", ignoreCase = true) || url.contains("season", ignoreCase = true) || doc.html().contains("Season", ignoreCase = true))) {
            pasteLinks.forEachIndexed { index, (pasteUrl, linkText) ->
                val name = linkText.ifBlank { "Episode ${index + 1}" }
                var seasonNum = 1
                var epNum = index + 1

                val seasonMatch = Regex("""S(?:eason)?\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
                if (seasonMatch != null) {
                    seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                    epNum = 1
                }

                val epMatch = Regex("""E(?:pisode)?\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
                if (epMatch != null) {
                    epNum = epMatch.groupValues[1].toIntOrNull() ?: (index + 1)
                }

                episodesData.add(
                    newEpisode(pasteUrl) {
                        this.name = name
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
        }

        val isTvSeries = episodesData.isNotEmpty()

        return if (isTvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.duration = duration
                addActors(actors)
                if (!trailerUrl.isNullOrBlank()) {
                    addTrailer(trailerUrl)
                }
            }
        } else {
            val movieData = if (pasteLinks.isNotEmpty()) pasteLinks.joinToString(",") { it.first } else url
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.duration = duration
                addActors(actors)
                if (!trailerUrl.isNullOrBlank()) {
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(tag, "loadLinks request: data='$data'")

        data.split(",").forEach { pasteUrl ->
            val cleanUrl = pasteUrl.trim()
            if (cleanUrl.isNotBlank()) {
                loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
