package app.niix

import android.app.Application

class NiixApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before any screen opens, so the first one already has the right theme and icon.
        app.niix.ui.NiixTheme.applyToProcess(this)
        // The old calculator kept its memory here, and people could store their duress code in it.
        // Gone with the calculator, so it shouldn't stay behind in plain text.
        deleteSharedPreferences("calc_state")
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.lock)
    }
}
