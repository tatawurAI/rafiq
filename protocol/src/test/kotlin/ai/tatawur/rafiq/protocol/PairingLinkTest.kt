package ai.tatawur.rafiq.protocol

import ai.tatawur.rafiq.protocol.api.PairingLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkTest {
    @Test
    fun `a built link parses back to the same url and code`() {
        val link = PairingLink.build("https://desk.example.ts.net/", "ABCD-EFGH")

        val parsed = PairingLink.parse(link)

        assertEquals(PairingLink.Parsed("https://desk.example.ts.net", "ABCD-EFGH"), parsed)
    }

    @Test
    fun `a link to a plain http server is refused`() {
        assertNull(PairingLink.parse("rafiq://pair?url=http://desk.example.ts.net&code=ABCD-EFGH"))
    }

    @Test
    fun `a link without a code or with a broken escape is refused`() {
        assertNull(PairingLink.parse("rafiq://pair?url=https://desk.example.ts.net"))
        assertNull(PairingLink.parse("rafiq://pair?url=https://desk.example.ts.net&code=%G1"))
    }

    @Test
    fun `a url that hides its real host behind user info is refused`() {
        val link = PairingLink.build("https://desk.example.ts.net@evil.example", "ABCD-EFGH")

        assertNull(PairingLink.parse(link))
    }

    @Test
    fun `a url with a query, a fragment or no host is refused`() {
        for (url in listOf("https://desk.example.ts.net/?x=1", "https://desk.example.ts.net/#x", "https:///path")) {
            assertNull(PairingLink.parse(PairingLink.build(url, "ABCD-EFGH")), url)
        }
    }

    @Test
    fun `the host is shown and only a ts net name counts as a Tailscale name`() {
        val tailnet = PairingLink.parse(PairingLink.build("https://desk.example.ts.net", "ABCD-EFGH"))!!
        val other = PairingLink.parse(PairingLink.build("https://desk.example.com:8443", "ABCD-EFGH"))!!

        assertEquals("desk.example.ts.net", tailnet.host)
        assertTrue(tailnet.isTailscaleName)
        assertEquals("desk.example.com", other.host)
        assertFalse(other.isTailscaleName)
    }
}
