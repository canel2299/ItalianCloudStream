package com.attf.multisite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.content.Context
import kotlinx.coroutines.runBlocking
import com.lagradost.api.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Auto-discovering Multi-Site Plugin
 * Fetches streaming sites from a URL and creates a provider for each one
 */
@CloudstreamPlugin
class MultiSitePlugin : Plugin() {
    
    companion object {
        // Your Pastebin link that returns streaming sites in raw text format
        const val SITES_LIST_URL = "https://pastebin.com/raw/KgQ4jTy6"
        
        // Cache the fetched sites
        private var cachedSites: List<String> = emptyList()
        private var lastFetchTime: Long = 0
        private const val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
        
        // Response format: "text" (one URL per line from Pastebin)
        const val RESPONSE_FORMAT = "text"
    }
    
    override fun load(context: Context) {
        Log.d("MultiSitePlugin", "Loading plugin and fetching sites...")
        
        // Fetch sites from your link
        val sites = runBlocking {
            fetchStreamingSites()
        }
        
        Log.d("MultiSitePlugin", "Found ${sites.size} streaming sites")
        
        // Create and register a provider for each site
        sites.forEachIndexed { index, siteUrl ->
            try {
                val provider = createDynamicProvider(siteUrl, index)
                registerMainAPI(provider)
                Log.d("MultiSitePlugin", "Registered provider for: $siteUrl")
            } catch (e: Exception) {
                Log.e("MultiSitePlugin", "Failed to register $siteUrl: ${e.message}")
            }
        }
    }
    
    /**
     * Fetches the list of streaming sites from your URL using GET request
     * Supports multiple response formats: plain text, JSON array, or HTML
     */
    private suspend fun fetchStreamingSites(): List<String> {
        // Check cache first
        val currentTime = System.currentTimeMillis()
        if (cachedSites.isNotEmpty() && (currentTime - lastFetchTime) < CACHE_DURATION) {
            Log.d("MultiSitePlugin", "Using cached sites list")
            return cachedSites
        }
        
        return try {
            Log.d("MultiSitePlugin", "Fetching sites from: $SITES_LIST_URL")
            val response = app.get(SITES_LIST_URL)
            val rawText = response.text
            
            Log.d("MultiSitePlugin", "Raw response preview: ${rawText.take(200)}")
            
            val sites = when (RESPONSE_FORMAT.lowercase()) {
                "json" -> parseJsonResponse(rawText)
                "html" -> parseHtmlResponse(rawText)
                else -> parseTextResponse(rawText)
            }
            
            // Update cache
            cachedSites = sites
            lastFetchTime = currentTime
            
            Log.d("MultiSitePlugin", "Successfully fetched ${sites.size} sites")
            sites
        } catch (e: Exception) {
            Log.e("MultiSitePlugin", "Error fetching sites: ${e.message}")
            e.printStackTrace()
            // Return cached sites if available, otherwise empty list
            cachedSites
        }
    }
    
    /**
     * Parses plain text response (one URL per line)
     * Example:
     * https://site1.com
     * https://site2.net
     */
    private fun parseTextResponse(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("http") }
            .distinct()
    }
    
    /**
     * Parses JSON response
     * Supports formats:
     * ["https://site1.com", "https://site2.com"]
     * or
     * [{"url": "https://site1.com"}, {"url": "https://site2.com"}]
     * or
     * {"sites": ["https://site1.com", "https://site2.com"]}
     */
    private fun parseJsonResponse(json: String): List<String> {
        return try {
            val sites = mutableListOf<String>()
            
            // Try parsing as JSON array of strings
            if (json.trim().startsWith("[")) {
                val jsonArray = org.json.JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.get(i)
                    when (item) {
                        is String -> {
                            if (item.startsWith("http")) sites.add(item)
                        }
                        is org.json.JSONObject -> {
                            // Try common keys
                            val url = item.optString("url")
                                ?: item.optString("site")
                                ?: item.optString("link")
                                ?: item.optString("domain")
                            if (url.startsWith("http")) sites.add(url)
                        }
                    }
                }
            } 
            // Try parsing as JSON object with array
            else if (json.trim().startsWith("{")) {
                val jsonObj = org.json.JSONObject(json)
                // Try common keys for the array
                val arrayKeys = listOf("sites", "urls", "links", "domains", "data")
                for (key in arrayKeys) {
                    if (jsonObj.has(key)) {
                        val array = jsonObj.getJSONArray(key)
                        for (i in 0 until array.length()) {
                            val url = array.optString(i)
                            if (url.startsWith("http")) sites.add(url)
                        }
                        break
                    }
                }
            }
            
            sites.distinct()
        } catch (e: Exception) {
            Log.e("MultiSitePlugin", "Error parsing JSON: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Parses HTML response - extracts all valid URLs from links
     * Useful if your link returns an HTML page with site links
     */
    private fun parseHtmlResponse(html: String): List<String> {
        return try {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("a[href]")
                .mapNotNull { it.attr("abs:href") }
                .filter { it.startsWith("http") && it.contains(".") }
                .distinct()
        } catch (e: Exception) {
            Log.e("MultiSitePlugin", "Error parsing HTML: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Creates a dynamic provider for a streaming site
     */
    private fun createDynamicProvider(siteUrl: String, index: Int): MainAPI {
        val siteName = extractSiteName(siteUrl)
        val language = detectLanguage(siteUrl)
        
        return object : MainAPI() {
            override var mainUrl = siteUrl.removeSuffix("/")
            override var name = "$siteName (Auto)"
            override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime)
            override var lang = language
            override val hasMainPage = true
            
            // Track actual URL after redirects
            private var actualMainUrl = ""
            
            override val mainPage = mainPageOf(
                mainUrl to "Home",
                "$mainUrl/movies" to "Movies",
                "$mainUrl/series" to "Series",
                "$mainUrl/tv-shows" to "TV Shows",
                "$mainUrl/serietv" to "Serie TV"
            )
            
            private fun fixTitle(title: String, isMovie: Boolean): String {
                var cleaned = title
                if (isMovie) {
                    cleaned = cleaned.replace(Regex("""(\[HD] )*\(\d{4}\)$"""), "")
                } else {
                    cleaned = cleaned
                        .replace(Regex("""[-–] Stagione \d+"""), "")
                        .replace(Regex("""[-–] ITA"""), "")
                        .replace(Regex("""[-–] Season \d+"""), "")
                        .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*"""), "")
                        .replace(Regex("""[-–] COMPLETA"""), "")
                }
                return cleaned.trim()
            }
            
            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
                val url = if (page > 1) "${request.data}/page/$page/" else request.data
                
                return try {
                    val response = app.get(url)
                    
                    // Update actual URL after redirects
                    if (actualMainUrl.isEmpty()) {
                        actualMainUrl = response.okhttpResponse.request.url.toString()
                            .substringBeforeLast('/')
                    }
                    
                    val document = response.document
                    val items = findPostItems(document)
                    
                    val searchResponses = items.mapNotNull { card ->
                        parsePostToSearchResponse(card, request.data)
                    }
                    
                    val hasNext = hasNextPage(document, page)
                    
                    newHomePageResponse(
                        HomePageList(request.name, searchResponses, false),
                        hasNext
                    )
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "Error in getMainPage: ${e.message}")
                    newHomePageResponse(emptyList())
                }
            }
            
            /**
             * Intelligently finds post/movie items on the page
             * Tries multiple common selectors
             */
            private fun findPostItems(document: Document): List<Element> {
                val selectors = listOf(
                    ".post", ".movie", ".item", ".card", ".video-item",
                    ".movie-item", ".tv-item", ".content-item", "article",
                    ".sequex-one-columns .post", ".movies-list .movie",
                    ".grid-item", ".list-item"
                )
                
                for (selector in selectors) {
                    val items = document.select(selector)
                    if (items.size > 3) { // Found a good container
                        Log.d("Provider:$siteName", "Found ${items.size} items with selector: $selector")
                        return items
                    }
                }
                
                return emptyList()
            }
            
            /**
             * Parses a post element into a SearchResponse
             */
            private fun parsePostToSearchResponse(element: Element, requestData: String): SearchResponse? {
                return try {
                    // Try to find link
                    val link = element.selectFirst("a")?.attr("abs:href") ?: return null
                    if (link.isEmpty() || !link.startsWith("http")) return null
                    
                    // Try to find title
                    val title = element.selectFirst("h1, h2, h3, h4, .title, .name")?.text()
                        ?: element.selectFirst("a")?.attr("title")
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: "Unknown"
                    
                    // Try to find poster
                    val poster = element.selectFirst("img")?.attr("abs:src")
                        ?: element.selectFirst("img")?.attr("abs:data-src")
                    
                    // Determine if it's a series
                    val isSeries = requestData.contains(Regex("series|serietv|tv-shows", RegexOption.IGNORE_CASE))
                        || link.contains(Regex("series|serietv|tv-shows|episode", RegexOption.IGNORE_CASE))
                    
                    val quality = if (title.contains("HD", ignoreCase = true)) SearchQuality.HD else null
                    
                    if (isSeries) {
                        newTvSeriesSearchResponse(
                            fixTitle(title, false),
                            link,
                            TvType.TvSeries
                        ) {
                            addPoster(poster)
                        }
                    } else {
                        newMovieSearchResponse(
                            fixTitle(title, true),
                            link,
                            TvType.Movie
                        ) {
                            addPoster(poster)
                            this.quality = quality
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "Error parsing post: ${e.message}")
                    null
                }
            }
            
            /**
             * Checks if there's a next page
             */
            private fun hasNextPage(document: Document, currentPage: Int): Boolean {
                return try {
                    val paginationSelectors = listOf(
                        ".pagination", ".page-numbers", ".pager", ".nav-links"
                    )
                    
                    for (selector in paginationSelectors) {
                        val pagination = document.selectFirst(selector)
                        if (pagination != null) {
                            val nextButton = pagination.selectFirst(".next, [rel=next]")
                            if (nextButton != null) return true
                            
                            // Check if there's a higher page number
                            val pageNumbers = pagination.select("a, span").mapNotNull {
                                it.text().toIntOrNull()
                            }
                            if (pageNumbers.isNotEmpty() && pageNumbers.maxOrNull()!! > currentPage) {
                                return true
                            }
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
                    "$mainUrl/search/$query",
                    "$mainUrl/movies/?s=$query",
                    "$mainUrl/series/?s=$query"
                )
                
                return searchUrls.amap { url ->
                    try {
                        val response = app.get(url)
                        val document = response.document
                        val items = findPostItems(document)
                        
                        items.mapNotNull { card ->
                            parsePostToSearchResponse(card, url)
                        }
                    } catch (e: Exception) {
                        Log.e("Provider:$siteName", "Error searching $url: ${e.message}")
                        null
                    }
                }.filterNotNull().flatten().distinctBy { it.url }
            }
            
            override suspend fun load(url: String): LoadResponse {
                val urlPath = url.substringAfter("//").substringAfter('/')
                if (actualMainUrl.isEmpty()) {
                    val r = app.get(url)
                    actualMainUrl = r.okhttpResponse.request.url.toString().substringBeforeLast('/')
                }
                val actualUrl = if (url.startsWith("http")) url else "$actualMainUrl/$urlPath"
                
                return try {
                    val document = app.get(actualUrl).document
                    
                    // Try to find title
                    val title = document.selectFirst("h1, .title, .entry-title")?.text() ?: "Unknown"
                    
                    // Try to find poster
                    val poster = document.selectFirst("img.poster, .featured-image img, img[itemprop=image]")
                        ?.attr("abs:src")
                    
                    // Try to find plot
                    val plot = document.selectFirst(".plot, .description, .synopsis, [itemprop=description]")
                        ?.text()
                    
                    // Try to find year
                    val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
                        ?: document.selectFirst(".year, [itemprop=datePublished]")?.text()?.toIntOrNull()
                    
                    // Check if it's a series
                    val isSeries = actualUrl.contains(Regex("series|serietv|tv-shows|episode", RegexOption.IGNORE_CASE))
                        || document.select(".season, .episode, .seasons").isNotEmpty()
                    
                    // Find streaming links
                    val links = document.select("a[href*=stream], a[href*=embed], a[href*=player], iframe[src*=stream], iframe[src*=embed]")
                        .mapNotNull { 
                            it.attr("abs:href").takeIf { url -> url.isNotEmpty() }
                                ?: it.attr("abs:src").takeIf { url -> url.isNotEmpty() }
                        }
                        .filter { it.startsWith("http") }
                        .distinct()
                    
                    val data = links.toJson()
                    
                    if (isSeries) {
                        newTvSeriesLoadResponse(
                            fixTitle(title, false),
                            actualUrl,
                            TvType.TvSeries,
                            emptyList() // Episodes would need more complex parsing
                        ) {
                            addPoster(poster)
                            this.plot = plot
                            this.year = year
                        }
                    } else {
                        newMovieLoadResponse(
                            fixTitle(title, true),
                            actualUrl,
                            TvType.Movie,
                            data
                        ) {
                            addPoster(poster)
                            this.plot = plot
                            this.year = year
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "Error loading $actualUrl: ${e.message}")
                    newMovieLoadResponse("Error", actualUrl, TvType.Movie, null)
                }
            }
            
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                if (data == "null") return false
                
                return try {
                    val links = parseJson<List<String>>(data)
                    
                    links.forEach { link ->
                        try {
                            // Try to load with built-in extractors
                            loadExtractor(link, mainUrl, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("Provider:$siteName", "Error extracting $link: ${e.message}")
                        }
                    }
                    true
                } catch (e: Exception) {
                    Log.e("Provider:$siteName", "Error in loadLinks: ${e.message}")
                    false
                }
            }
        }
    }
    
    /**
     * Extracts a readable site name from URL
     */
    private fun extractSiteName(url: String): String {
        return try {
            url.substringAfter("://")
                .substringBefore("/")
                .substringBefore("www.")
                .substringBeforeLast(".")
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Site${url.hashCode()}"
        }
    }
    
    /**
     * Attempts to detect language from URL
     */
    private fun detectLanguage(url: String): String {
        return when {
            url.contains(Regex("\\.it/|\\.it$")) -> "it"
            url.contains(Regex("\\.es/|\\.es$")) -> "es"
            url.contains(Regex("\\.fr/|\\.fr$")) -> "fr"
            url.contains(Regex("\\.de/|\\.de$")) -> "de"
            url.contains(Regex("\\.pt/|\\.pt$")) -> "pt"
            url.contains(Regex("\\.ru/|\\.ru$")) -> "ru"
            else -> "en"
        }
    }
}