package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.PairingRepository
import ai.tatawur.rafiq.protocol.api.PairingLink
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a `rafiq://pair` link the app was opened with, for example from the phone's own camera app, until the pairing
 * screen puts it in its form. A link that [PairingLink.parse] refuses, such as one with a plain `http://` address, is
 * dropped. While the phone is already paired a link is dropped too, with a message saying how to pair again, so a link
 * never replaces your server behind your back.
 */
class PairingLinkInbox(
    private val pairing: PairingRepository,
) {
    private val pending = MutableStateFlow<PairingLink.Parsed?>(null)

    /** The link waiting for the pairing screen, or null. */
    val link: StateFlow<PairingLink.Parsed?> = pending.asStateFlow()

    /** Takes the link from [intent], if it carries one. */
    fun accept(
        intent: Intent?,
        context: Context,
    ) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(PairingLink::parse) ?: return
        if (pairing.server.value != null) {
            Toast.makeText(context, R.string.setup_link_already_paired, Toast.LENGTH_LONG).show()
        } else {
            pending.value = link
        }
    }

    /** Called once the pairing screen has the link, so it is not applied twice. */
    fun used() {
        pending.value = null
    }
}
