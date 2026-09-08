package com.iptv.tv.edition

/** Browser edition: a plain web browser with an address bar and recent sites. */
object EditionConfig : BrowserEdition {
    override val id: String = "browser"
    override val displayName: String = "Browser edition"
    override val featuredSite: FeaturedSite? = null
    override val startPageHint: String =
        "Sites you sign in to stay signed in on this stick. Settings → Clear browser data logs you out."
}
