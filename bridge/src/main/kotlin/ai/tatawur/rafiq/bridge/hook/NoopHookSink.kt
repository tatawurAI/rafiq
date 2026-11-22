package ai.tatawur.rafiq.bridge.hook

/**
 * A [HookSink] that drops every event and sends every permission request back to the desk. The bridge uses it
 * while no reader is wired in, so an installed hook changes nothing at the desk.
 */
object NoopHookSink : HookSink {
    override suspend fun onEvent(envelope: HookEnvelope) = Unit

    override suspend fun onPermission(envelope: HookEnvelope): HookReply? = null
}
