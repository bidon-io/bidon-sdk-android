object Versions {
    private val major = 0
    private val minor = 2
    private val patch = 1
    private val semantic: String? = "-beta.21"

    val BidonVersionName = mainVersion + semanticVersion

    object Adapters {
        val Admob = "$mainVersion.11"
        val Applovin = "$mainVersion.11"
        val BidMachine = "$mainVersion.12"
        val DTExchange = "$mainVersion.11"
        val UnityAds = "$mainVersion.11"

        val IronSource = "$mainVersion.0"
        val Appsflyer = "$mainVersion.0"
        val Fyber = "$mainVersion.0"
    }

    private val mainVersion get() = "$major.$minor.$patch"
    private val semanticVersion get() = semantic.takeIf { !it.isNullOrBlank() }.orEmpty()
}
