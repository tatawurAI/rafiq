package ai.tatawur.rafiq.protocol

import ai.tatawur.rafiq.protocol.api.PairingLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
