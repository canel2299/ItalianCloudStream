package com.attf.multisite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Document

/**
 * Base provider that can be configured for any streaming site
 * Extend this class and override the configuration for each site
 */
abstract class GenericStreamProvider : MainAPI() {
    
    // Site Configuration - Override these in your implementations
    abstract val siteConfig: SiteConfig
    
    data class SiteConfig(
        val baseUrl: String,
        val siteName: String,
        val language: String,
        val iconUrl: String?,
        val supportedTypes: Set<TvType>,
        
        // Selectors for parsing
        val selectors: SiteSelectors,
        
        // URL patterns
        val urlPatterns: UrlPatterns,
        
        // Custom parsers (optional)
        val customTitleParser: ((String, Boolean) -> String)? = null,
        val customSearchParser: ((Document) -> List<SearchResponse>)? = null
    )
    
    data class SiteSelectors(
        // Main page selectors
        val mainContainer: String = ".sequex-one-columns",
        val postItem: String = ".post",
        val posterImg: String = "img",
        val postScript: String = "script",
        
        // Pagination
        val pagination: String = ".pagination",
        val pageItem: String = ".page-item",
        
        // Load page selectors
        val contentContainer: String = ".sequex-main-container",
        val titleSelector: String = "h1",
        val posterSelector: String = "img.responsive-locandina",
        val bannerSelector: String = "#sequex-page-title-img",
        val plotSelector: String = ".ignore-css > p:nth-child(2)",
        val tagsSelector: String = ".ignore-css > p:nth-child(1) > strong:nth-child(1)",
        
        // Link tables
        val linkTable: String = "table.cbtable",
        val linkSelector: String = "a",
        
        // Series selectors
        val seasonDropdowns: String = ".sp-wrap",
        val seasonHead: String = "div.sp-head",
        val seasonBody: String = "div.sp-body",
        val episodeItem: String = "strong p"
    )
    
    data class UrlPatterns(
        val mainPages: Map<String, String>, // URL to Name mapping
        val seriesIdentifier: String = "serietv",
        val searchPath: String = "/?s=",
        val pageParam: String = "/page/"
    )
    
    // Initialize from config
    override var mainUrl: String
        get() = siteConfig.baseUrl
        set(_) {}
    
    override var name: String
        get() = siteConfig.siteName
        set(_) {}
    
    override val supportedTypes: Set<TvType>
        get() = siteConfig.supportedTypes
    
    override var lang: String
        get() = siteConfig.language
        set(_) {}
    
    override val hasMainPage = true
    
    override val mainPage: List<MainPageData>
        get() = siteConfig.urlPatterns.mainPages.map { (url, name) ->
            MainPageData(name, url, false)
        }
    
    // Dynamic main URL tracking
    protected var actualMainUrl = ""
    
    // Generic title cleaner
    protected open fun fixTitle(title: String, isMovie: Boolean): String {
        return siteConfig.customTitleParser?.invoke(title, isMovie) ?: run {
            var cleaned = title
            if (isMovie) {
                cleaned = cleaned.replace(Regex("""(\[HD] )*\(\d{4}\)$"""), "")
            } else {
                cleaned = cleaned
                    .replace(Regex("""[-–] Stagione \d+"""), "")
                    .replace(Regex("""[-–] ITA"""), "")
                    .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*"""), "")
                    .replace(Regex("""[-–] COMPLETA"""), "")
            }
            cleaned.trim()
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}${siteConfig.urlPatterns.pageParam}$page/"
        } else {
            request.data
        }
        
        val response = app.get(url)
        
        if (actualMainUrl.isEmpty()) {
            actualMainUrl = response.okhttpResponse.request.url.toString()
                .substringBeforeLast('/')
        }
        
        val document = response.document
        val items = document.selectFirst(siteConfig.selectors.mainContainer)
            ?.select(siteConfig.selectors.postItem) ?: return newHomePageResponse(emptyList())
        
        val posts = items.mapNotNull { card ->
            parsePostItem(card)
        }
        
        val pagination = document.selectFirst(siteConfig.selectors.pagination)
            ?.select(siteConfig.selectors.pageItem)
        val hasNext = pagination?.let { 
            page < it[it.size - 2].text().replace(".", "").toIntOrNull() ?: page 
        } ?: false
        
        val searchResponses = posts.map { post ->
            createSearchResponse(post, request.data.contains(siteConfig.urlPatterns.seriesIdentifier))
        }
        
        return newHomePageResponse(
            HomePageList(request.name, searchResponses, false),
            hasNext
        )
    }
    
    protected open fun parsePostItem(element: org.jsoup.nodes.Element): PostData? {
        val poster = element.selectFirst(siteConfig.selectors.posterImg)?.attr("src")
        val scriptData = element.selectFirst(siteConfig.selectors.postScript)?.data()
        val jsonData = scriptData?.substringAfter("=")?.substringBefore(";")
        
        return try {
            parseJson<PostData>(jsonData ?: return null).apply {
                this.poster = poster
            }
        } catch (e: Exception) {
            null
        }
    }
    
    protected open fun createSearchResponse(post: PostData, isSeries: Boolean): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(
                fixTitle(post.title, false),
                post.permalink,
                TvType.TvSeries
            ) {
                addPoster(post.poster)
            }
        } else {
            val quality = if (post.title.contains("HD")) SearchQuality.HD else null
            newMovieSearchResponse(
                fixTitle(post.title, true),
                post.permalink,
                TvType.Movie
            ) {
                addPoster(post.poster)
                this.quality = quality
            }
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchLinks = siteConfig.urlPatterns.mainPages.keys.map { url ->
            "$url${siteConfig.urlPatterns.searchPath}$query"
        }
        
        return searchLinks.amap { link ->
            val response = app.get(link)
            siteConfig.customSearchParser?.invoke(response.document) ?: run {
                val items = response.document.selectFirst(siteConfig.selectors.mainContainer)
                    ?.select(siteConfig.selectors.postItem)
                
                items?.mapNotNull { card ->
                    parsePostItem(card)?.let { post ->
                        createSearchResponse(
                            post,
                            link.contains(siteConfig.urlPatterns.seriesIdentifier)
                        )
                    }
                }
            }
        }.filterNotNull().flatten()
    }
    
    override suspend fun load(url: String): LoadResponse {
        val urlPath = url.substringAfter("//").substringAfter('/')
        if (actualMainUrl.isEmpty()) {
            val r = app.get(url)
            actualMainUrl = r.okhttpResponse.request.url.toString().substringBeforeLast('/')
        }
        val actualUrl = "$actualMainUrl/$urlPath"
        
        val document = app.get(actualUrl).document
        val mainContainer = document.selectFirst(siteConfig.selectors.contentContainer)
            ?: return newMovieLoadResponse("Error", actualUrl, TvType.Movie, null)
        
        return parseLoadResponse(mainContainer, actualUrl, document)
    }
    
    protected abstract suspend fun parseLoadResponse(
        container: org.jsoup.nodes.Element,
        url: String,
        document: Document
    ): LoadResponse
    
    protected abstract suspend fun extractLinks(
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return extractLinks(data, callback)
    }
    
    // Common data class
    data class PostData(
        val id: String,
        val title: String,
        val permalink: String,
        var poster: String? = null
    )
}

// ============================================================================
// EXAMPLE IMPLEMENTATION: CB01 using the generic provider
// ============================================================================

class CB01Provider : GenericStreamProvider() {
    override val siteConfig = SiteConfig(
        baseUrl = "https://cb01.uno",
        siteName = "CB01",
        language = "it",
        iconUrl = "https://cb01.uno/apple-icon-180x180px.png",
        supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon),
        selectors = SiteSelectors(),
        urlPatterns = UrlPatterns(
            mainPages = mapOf(
                "https://cb01.uno" to "Film",
                "https://cb01.uno/serietv" to "Serie TV"
            )
        ),
        customTitleParser = { title, isMovie ->
            if (isMovie) {
                title.replace(Regex("""(\[HD] )*\(\d{4}\)$"""), "")
            } else {
                title.replace(Regex("""[-–] Stagione \d+"""), "")
                    .replace(Regex("""[-–] ITA"""), "")
                    .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*"""), "")
                    .replace(Regex("""[-–] COMPLETA"""), "").trim()
            }
        }
    )
    
    override suspend fun parseLoadResponse(
        container: org.jsoup.nodes.Element,
        url: String,
        document: Document
    ): LoadResponse {
        val poster = container.selectFirst(siteConfig.selectors.posterSelector)?.attr("src")
        val banner = container.selectFirst(siteConfig.selectors.bannerSelector)?.attr("data-img")
        val title = container.selectFirst(siteConfig.selectors.titleSelector)?.text() ?: "Unknown"
        val isMovie = !url.contains(siteConfig.urlPatterns.seriesIdentifier)
        
        return if (isMovie) {
            val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
            val plot = container.selectFirst(siteConfig.selectors.plotSelector)?.text()
            val links = container.selectFirst(siteConfig.selectors.linkTable)
                ?.select(siteConfig.selectors.linkSelector)
                ?.mapNotNull { it.attr("href") }
                ?.takeLast(2)
            
            newMovieLoadResponse(
                fixTitle(title, true),
                url,
                TvType.Movie,
                links?.toJson() ?: "null"
            ) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.year = year
            }
        } else {
            val plot = container.selectFirst(".ignore-css > p:nth-child(1)")?.text()
            // Add your series parsing logic here
            newTvSeriesLoadResponse(
                fixTitle(title, false),
                url,
                TvType.TvSeries,
                emptyList()
            ) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
            }
        }
    }
    
    override suspend fun extractLinks(
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Add your link extraction logic here
        return true
    }
}