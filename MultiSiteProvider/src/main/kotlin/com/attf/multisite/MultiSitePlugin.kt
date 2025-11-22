package com.attf.multisite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import android.content.Context
import kotlinx.coroutines.runBlocking
import com.lagradost.api.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Improved Multi-Site Plugin for Italian Streaming Sites
 * Optimized for: Eurostreamings, CB01, Altadefinizione, StreamingCommunity, etc.
 */
@CloudstreamPlugin
class MultiSitePlugin : Plugin() {
    
    companion object {
        const val SITES_LIST_URL = "https://pastebin.com/raw/KgQ4jTy6"
        private var cachedSites: List<String> = emptyList()
        private var lastFetchTime: Long = 0
        private const val CACHE_DURATION = 24 * 60 * 60 * 1000L
        const val RESPONSE_FORMAT = "text"
    }
    
    override fun load(context: Context) {
        Log.d("MultiSitePlugin", "Loading improved Italian sites plugin...")
        
        val sites = runBlocking {
            fetchStreamingSites()
        }
        
        Log.d("MultiSitePlugin", "Found ${sites.size} streaming sites")
        
        sites.forEachIndexed { index, siteUrl ->
            try {
                val provider = createDynamicProvider(siteUrl, index)
                registerMainAPI(provider)
                Log.d("MultiSitePlugin", "✓ Registered: $siteUrl")
            } catch (e: Exception) {
                Log.e("MultiSitePlugin", "✗ Failed $siteUrl: ${e.message}")
            }
        }
    }
    
    private suspend fun fetchStreamingSites(): List<String> {
        val currentTime = System.currentTimeMillis()
        if (cachedSites.isNotEmpty() && (currentTime - lastFetchTime) < CACHE_DURATION) {
            return cachedSites
        }
        
        return try {
            val response = app.get(SITES_LIST_URL)
            val sites = response.text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("http") }
                .filterNot { it.contains("1337x.to") || it.contains("ilcorsaronero") } // Skip torrent sites
                .distinct()
            
            cachedSites = sites
            lastFetchTime = currentTime
            Log.d("MultiSitePlugin", "✓ Fetched ${sites.size} sites successfully")
            sites
        } catch (e: Exception) {
            Log.e("MultiSitePlugin", "✗ Fetch error: ${e.message}")
            cachedSites
        }
    }
    
    private fun createDynamicProvider(siteUrl: String, index: Int): MainAPI {
        val siteName = extractSiteName(siteUrl)
        val language = "it" // All Italian sites
        
        return object : MainAPI() {
            override var mainUrl = siteUrl.removeSuffix("/")
            override var name = "$siteName"
            override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
            override var lang = language
            override val hasMainPage = true
            
            private var actualMainUrl = ""
            
            override val mainPage = mainPageOf(
                mainUrl to "Home",
                "$mainUrl/film" to "Film",
                "$mainUrl/serie-tv" to "Serie TV",
                "$mainUrl/anime" to "Anime"
            )
            
            private fun fixTitle(title: String): String {
                return title
                    .replace(Regex("""\[HD\]|\[4K\]|\[FULL HD\]"""), "")
                    .replace(Regex("""\(\d{4}\)$"""), "")
                    .replace(Regex("""[-–] Stagione \d+"""), "")
                    .replace(Regex("""[-–] ITA"""), "")
                    .replace(Regex("""[-–] Season \d+"""), "")
                    .replace(Regex("""\d+[x×]\d+"""), "")
                    .trim()
            }
            
            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
                val url = if (page > 1) "${request.data}/page/$page/" else request.data
                
                return try {
                    val response = app.get(url, timeout = 15)
                    
                    if (actualMainUrl.isEmpty()) {
                        actualMainUrl = response.okhttpResponse.request.url.toString()
                            .substringBeforeLast('/')
                    }
                    
                    val document = response.document
                    val items = findPostItems(document)
                    
                    Log.d("Provider:$siteName", "Found ${items.size} items on page $page")
                    
                    val searchResponses = items.mapNotNull { card ->
                        parsePostToSearchResponse(card, request.data)
                    }
                    
                    val hasNext = hasNextPage(document, page)
                    
                    newHomePageResponse(
                        HomePageList(request.name, searchResponses, false),
                        hasNext
                    )
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "MainPage error: ${e.message}")
                    newHomePageResponse(emptyList())
                }
            }
            
            /**
             * Enhanced selector list for Italian streaming sites
             */
            private fun findPostItems(document: Document): List<Element> {
                val italianSelectors = listOf(
                    // Eurostreamings, CB01 style
                    "li.post",
                    ".post",
                    
                    // Altadefinizione style  
                    ".box-movies .movie",
                    ".movies-list .movie-item",
                    ".film-list .film",
                    
                    // StreamingCommunity style
                    ".film-card",
                    ".content-item",
                    ".show-card",
                    
                    // Generic Italian patterns
                    "article.post",
                    ".sequex-one-columns .post",
                    ".movie-block",
                    ".item-film",
                    
                    // Fallback generic
                    ".movie", ".item", ".card", "article"
                )
                
                for (selector in italianSelectors) {
                    val items = document.select(selector)
                    if (items.size >= 3) {
                        Log.d("Provider:$siteName", "✓ Using selector: $selector (${items.size} items)")
                        return items
                    }
                }
                
                Log.w("Provider:$siteName", "✗ No items found with any selector")
                return emptyList()
            }
            
            private fun parsePostToSearchResponse(element: Element, requestData: String): SearchResponse? {
                return try {
                    // Find link - try multiple patterns
                    val link = element.selectFirst("a[href]")?.attr("abs:href")
                        ?: element.attr("abs:href")
                        ?: return null
                    
                    if (link.isEmpty() || !link.startsWith("http")) return null
                    
                    // Find title - try multiple locations
                    val title = element.selectFirst("h2 a, h3 a, h2, h3")?.text()
                        ?: element.selectFirst("a")?.attr("title")
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: element.selectFirst(".post-title, .title, .name")?.text()
                        ?: return null
                    
                    // Find poster - try multiple attributes
                    val poster = element.selectFirst("img")?.let { img ->
                        img.attr("abs:src").takeIf { it.isNotEmpty() }
                            ?: img.attr("abs:data-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("abs:data-lazy-src").takeIf { it.isNotEmpty() }
                    }
                    
                    // Determine type
                    val isSeries = requestData.contains(Regex("serie|serietv|tv-shows", RegexOption.IGNORE_CASE))
                        || link.contains(Regex("serie|serietv|tv-shows|season|episod", RegexOption.IGNORE_CASE))
                        || title.contains(Regex("stagione|season", RegexOption.IGNORE_CASE))
                    
                    val quality = when {
                        title.contains("4K", ignoreCase = true) -> SearchQuality.FourK
                        title.contains("HD", ignoreCase = true) -> SearchQuality.HD
                        else -> null
                    }
                    
                    if (isSeries) {
                        newTvSeriesSearchResponse(
                            fixTitle(title),
                            link,
                            TvType.TvSeries
                        ) {
                            this.posterUrl = poster
                        }
                    } else {
                        newMovieSearchResponse(
                            fixTitle(title),
                            link,
                            TvType.Movie
                        ) {
                            this.posterUrl = poster
                            this.quality = quality
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "Parse error: ${e.message}")
                    null
                }
            }
            
            private fun hasNextPage(document: Document, currentPage: Int): Boolean {
                return try {
                    val pagination = document.selectFirst(".pagination, .nav-links, .page-numbers")
                    if (pagination != null) {
                        val nextButton = pagination.selectFirst(".next, [rel=next]")
                        if (nextButton != null) return true
                        
                        val pageNumbers = pagination.select("a, span").mapNotNull {
                            it.text().replace(".", "").toIntOrNull()
                        }
                        if (pageNumbers.isNotEmpty() && pageNumbers.maxOrNull()!! > currentPage) {
                            return true
                        }
                    }
                    false
                } catch (e: Exception) {
                    false
                }
            }
            
            override suspend fun search(query: String): List<SearchResponse> {
                val searchUrls = listOf(
                    "$mainUrl/?s=$query",
                    "$mainUrl/search?q=$query",
                    "$mainUrl/cerca/$query"
                )
                
                return searchUrls.amap { url ->
                    try {
                        val response = app.get(url, timeout = 15)
                        val document = response.document
                        val items = findPostItems(document)
                        
                        Log.d("Provider:$siteName", "Search '$query': ${items.size} results")
                        
                        items.mapNotNull { card ->
                            parsePostToSearchResponse(card, url)
                        }
                    } catch (e: Exception) {
                        Log.e("Provider:$siteName", "Search error: ${e.message}")
                        null
                    }
                }.filterNotNull().flatten().distinctBy { it.url }
            }
            
            override suspend fun load(url: String): LoadResponse {
                return try {
                    val document = app.get(url, timeout = 15).document
                    
                    // Find title
                    val title = document.selectFirst("h1, .entry-title, .post-title")?.text()
                        ?: document.title()
                    
                    // Find poster
                    val poster = document.selectFirst("img.wp-post-image, .poster img, .featured-image img")
                        ?.attr("abs:src")
                    
                    // Find plot/description
                    val plot = document.selectFirst(".description, .plot, .synopsis, [itemprop=description], .entry-content p")
                        ?.text()
                        ?.take(300)
                    
                    // Find year
                    val year = Regex("\\b(19|20)\\d{2}\\b").find(
                        document.selectFirst(".year, .date, .meta")?.text() ?: title
                    )?.value?.toIntOrNull()
                    
                    // Check if series
                    val isSeries = url.contains(Regex("serie|season|episod", RegexOption.IGNORE_CASE))
                        || document.select(".season, .episode, .seasons-list").isNotEmpty()
                        || title.contains(Regex("stagione|season", RegexOption.IGNORE_CASE))
                    
                    // Find streaming links - improved patterns for Italian sites
                    val links = document.select("""
                        a[href*="supervideo"],
                        a[href*="mixdrop"],
                        a[href*="streamtape"],
                        a[href*="doodstream"],
                        a[href*="voe.sx"],
                        a[href*="streamhide"],
                        iframe[src*="supervideo"],
                        iframe[src*="mixdrop"],
                        iframe[src*="streamtape"],
                        iframe[src*="embed"]
                    """.trimIndent()).mapNotNull {
                        it.attr("abs:href").takeIf { url -> url.isNotEmpty() && url.startsWith("http") }
                            ?: it.attr("abs:src").takeIf { url -> url.isNotEmpty() && url.startsWith("http") }
                    }.distinct()
                    
                    Log.d("Provider:$siteName", "Found ${links.size} video links")
                    
                    val data = links.toJson()
                    
                    if (isSeries) {
                        // Parse episodes if available
                        val episodes = parseEpisodes(document, url)
                        
                        newTvSeriesLoadResponse(
                            fixTitle(title),
                            url,
                            TvType.TvSeries,
                            episodes
                        ) {
                            this.posterUrl = poster
                            this.plot = plot
                            this.year = year
                        }
                    } else {
                        newMovieLoadResponse(
                            fixTitle(title),
                            url,
                            TvType.Movie,
                            data
                        ) {
                            this.posterUrl = poster
                            this.plot = plot
                            this.year = year
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "Load error: ${e.message}")
                    newMovieLoadResponse("Error", url, TvType.Movie, null)
                }
            }
            
            private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
                return try {
                    val episodes = mutableListOf<Episode>()
                    
                    // Try Italian streaming site patterns
                    document.select(".episode-item, .episodio, .ep-item").forEachIndexed { index, ep ->
                        val epLink = ep.selectFirst("a")?.attr("abs:href")
                        val epTitle = ep.selectFirst(".title, .ep-title")?.text() ?: "Episodio ${index + 1}"
                        
                        if (epLink != null) {
                            episodes.add(
                                newEpisode(epLink) {
                                    this.name = epTitle
                                    this.episode = index + 1
                                }
                            )
                        }
                    }
                    
                    episodes
                } catch (e: Exception) {
                    emptyList()
                }
            }
            
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                if (data == "null" || data == "[]") {
                    Log.w("Provider:$siteName", "No links available")
                    return false
                }
                
                return try {
                    val links = parseJson<List<String>>(data)
                    
                    Log.d("Provider:$siteName", "Extracting ${links.size} links")
                    
                    var extracted = 0
                    links.forEach { link ->
                        try {
                            val success = loadExtractor(link, mainUrl, subtitleCallback, callback)
                            if (success) extracted++
                        } catch (e: Exception) {
                            Log.e("Provider:$siteName", "Extractor error for $link: ${e.message}")
                        }
                    }
                    
                    Log.d("Provider:$siteName", "✓ Extracted $extracted/${links.size} links")
                    extracted > 0
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "LoadLinks error: ${e.message}")
                    false
                }
            }
        }
    }
    
    private fun extractSiteName(url: String): String {
        return try {
            url.substringAfter("://")
                .substringBefore("/")
                .replace("www.", "")
                .substringBeforeLast(".")
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Site${url.hashCode()}"
        }
    }
}
