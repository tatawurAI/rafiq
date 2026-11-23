package ai.tatawur.rafiq.bridge.setup

import ai.tatawur.rafiq.protocol.api.PairingLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TailscaleAndQrTest {
    @Test
    fun the_ts_net_name_comes_from_self_without_its_final_dot() {
        val status =
            TailscaleStatus.parse(
                """{"BackendState":"Running","Self":{"DNSName":"Box.example.ts.net.","Online":true}}""",
            )

        assertEquals(TailscaleStatus(running = true, dnsName = "box.example.ts.net"), status)
    }

    @Test
    fun a_stopped_tailscale_or_output_that_is_not_json_gives_no_name_and_no_error() {
        assertEquals(false, TailscaleStatus.parse("""{"BackendState":"Stopped","Self":{"DNSName":""}}""").running)
        assertEquals(
            TailscaleStatus(running = false, dnsName = null),
            TailscaleStatus.parse("tailscaled is not running"),
        )
    }

    @Test
    fun a_name_that_is_not_a_plain_host_name_never_reaches_the_settings_file() {
        val status =
            TailscaleStatus.parse(
                """{"BackendState":"Running","Self":{"DNSName":"box.example.ts.net\nRAFIQ_AGENTS=x=sh"}}""",
            )

        assertNull(status.dnsName)
    }

    @Test
    fun the_printed_qr_code_reads_back_as_the_pairing_link() {
        val link = PairingLink.build("https://box.example.ts.net", "ABCD-EFGH")

        val printed = TerminalQr.render(link)

        assertEquals(link, decodeTerminalQr(printed))
        assertEquals(
            PairingLink.Parsed("https://box.example.ts.net", "ABCD-EFGH"),
            PairingLink.parse(decodeTerminalQr(printed)),
        )
    }
}
