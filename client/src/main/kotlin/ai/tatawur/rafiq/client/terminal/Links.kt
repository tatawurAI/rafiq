package ai.tatawur.rafiq.client.terminal

import java.net.URI
import java.net.URISyntaxException

/**
 * The link a tap in the pane may open, or null when it must not open. Only an absolute `https` URL
 * with a host and no user info qualifies. The view shows the returned text in a confirmation before
 * it opens anything, so what you confirm is the real target, not the text the pane drew over it.
 */
fun httpsLinkTarget(raw: String): String? {
    val text = raw.trim()
    val clean = text.isNotEmpty() && text.none { it.isISOControl() || it.isWhitespace() }
    val uri =
        try {
            if (clean) URI(text) else null
        } catch (_: URISyntaxException) {
            null
        }
    return uri
        ?.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() && it.rawUserInfo == null }
        ?.toASCIIString()
}
