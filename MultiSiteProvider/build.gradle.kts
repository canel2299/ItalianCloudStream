version = 1

android {
    namespace = "com.attf.multisite"
}

cloudstream {
    description = "Auto-discovering multi-site streaming provider. Fetches sites from Pastebin."
    authors = listOf("attf")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon", "Anime")
    requiresResources = false
    language = "multi"
    iconUrl = "https://www.google.com/s2/favicons?domain=github.com&sz=128"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}