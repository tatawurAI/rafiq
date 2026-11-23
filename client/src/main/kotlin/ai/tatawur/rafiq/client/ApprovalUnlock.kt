package ai.tatawur.rafiq.client

/**
 * The approval lock's key. `:android` keeps it in the Android Keystore, usable only within 30 seconds of a
 * strong biometric check and destroyed by Android when a fingerprint or face is added. Using it is the proof
 * that the person holding the phone passed that check recently, whether in Rafiq or by unlocking the phone.
 */
interface ApprovalKey {
    /** Tries to use the key once. Never throws. */
    fun use(): KeyUse

    /** Makes the key. False when the phone cannot, for example with no strong biometric enrolled. */
    fun create(): Boolean

    /** Deletes the key. Never throws. */
    fun delete()
}

/** What happened when the app tried to use the [ApprovalKey]. */
enum class KeyUse {
    /** A strong biometric check passed within the key's window. */
    USABLE,

    /** No check in the window. A biometric prompt will open it. */
    NEEDS_CHECK,

    /** Android destroyed the key: a biometric was added, or all were removed. */
    INVALIDATED,

    /** There is no key, for example when the lock was turned on before the key existed. */
    MISSING,

    /** The Keystore failed in some other way. */
    BROKEN,
}

/** Whether you may answer the approval now. */
enum class Unlock {
    ALLOWED,

    /** The check was cancelled or failed, or the Keystore failed. Nothing is sent. */
    REFUSED,

    /**
     * The key was destroyed because the phone's biometrics changed, so the lock was turned off and nothing was
     * sent. The app says why and asks you to turn it on again.
     */
    LOCK_TURNED_OFF,
}

/**
 * The approval lock (issue #14). With the lock on, answering an approval needs the [key], which works only
 * within 30 seconds of a strong biometric check. One fingerprint is enough: unlocking the phone with it, then
 * tapping Approve in a notification, needs no second check. Outside the window the app asks for one.
 *
 * It fails closed except in one case. When Android destroyed the key because the phone's biometrics changed,
 * someone may have added their own fingerprint, so nothing is sent; the lock turns off through
 * [onInvalidated], and the app tells you to turn it on again. That is the trade the lock makes for never
 * locking you out of your own approvals.
 *
 * @param onInvalidated turns the lock off and records why, so Settings can say so.
 */
class ApprovalUnlock(
    private val key: ApprovalKey,
    private val onInvalidated: suspend () -> Unit,
) {
    /**
     * Decides whether an answer may go now.
     *
     * @param lockOn the setting. Off means [Unlock.ALLOWED] without touching the key.
     * @param askBiometric shows the strong biometric prompt and returns true when it passed.
     */
    suspend fun check(
        lockOn: Boolean,
        askBiometric: suspend () -> Boolean,
    ): Unlock {
        if (!lockOn) return Unlock.ALLOWED
        var use = key.use()
        // A lock turned on before this key existed gets its key now. Making one needs no check.
        if (use == KeyUse.MISSING) use = if (key.create()) key.use() else KeyUse.BROKEN
        if (use == KeyUse.NEEDS_CHECK) use = if (askBiometric()) key.use() else return Unlock.REFUSED
        return when (use) {
            KeyUse.USABLE -> {
                Unlock.ALLOWED
            }

            KeyUse.INVALIDATED -> {
                key.delete()
                onInvalidated()
                Unlock.LOCK_TURNED_OFF
            }

            KeyUse.NEEDS_CHECK, KeyUse.MISSING, KeyUse.BROKEN -> {
                Unlock.REFUSED
            }
        }
    }

    /**
     * Turning the lock on makes a fresh key, so an older one from before a biometric change never counts.
     * Returns false, leaving the lock off, when the phone cannot make one.
     */
    fun lockTurnedOn(): Boolean {
        key.delete()
        return key.create()
    }

    /** Turning the lock off deletes the key. */
    fun lockTurnedOff() {
        key.delete()
    }
}
