// use an integer for version numbers
version = 11

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "Bangla & Hindi Movies"
    authors = listOf("Redowan")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "Movie",
        "TvSeries",
		"NSFW"
    )
    language = "hi"

    iconUrl = "https://9kmovies.llc/wp-content/uploads/2026/06/9kmovies-logo1.png"
}
