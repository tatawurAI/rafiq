package ai.tatawur.rafiq.data

import android.app.Application

/** The app's process. It owns the one [AppContainer], built on first use from the main thread. */
class RafiqApplication : Application() {
    /** The app's repositories. Screens read it as `(application as RafiqApplication).container`. */
    val container: AppContainer by lazy { AppContainer(this) }
}
