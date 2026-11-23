package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.protocol.api.PushRegistration

/** Each device's push registration, for `PUT` and `DELETE /devices/me/push`. Revoking a device removes its entry. */
interface PushRegistry {
    /**
     * Stores [registration] for [deviceId], replacing any earlier one.
     *
     * @throws ai.tatawur.rafiq.bridge.sessions.SessionException.Invalid with the code `invalid_push_registration`
     *   when the endpoint is not an https URL or a key has the wrong length.
     */
    suspend fun register(
        deviceId: String,
        registration: PushRegistration,
    )

    /** Removes [deviceId]'s registration. Does nothing when there is none. */
    suspend fun unregister(deviceId: String)
}
