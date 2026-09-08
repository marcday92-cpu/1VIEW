package com.iptv.tv.edition

/** DeeTV edition: the Web tab opens straight onto DeeTV and keeps its login. */
object EditionConfig : BrowserEdition {
    override val id: String = "deetv"
    override val displayName: String = "DeeTV edition"
    override val featuredSite: FeaturedSite = FeaturedSite(
        label = "DeeTV",
        url = "https://deetv.dundeefc.co.uk/",
    )
    override val startPageHint: String =
        "DeeTV stays signed in on this stick. Only Settings → Clear browser data logs you out."
}
