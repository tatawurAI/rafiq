package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.client.terminal.TerminalPort
import kotlinx.coroutines.flow.Flow

/**
 * What a session screen needs to offer Terminal: the socket port and the biometric lock setting.
 *
 * @property lock true when Terminal opens only after a strong biometric check.
 */
class TerminalAccess(
    val port: TerminalPort,
    val lock: Flow<Boolean>,
)
