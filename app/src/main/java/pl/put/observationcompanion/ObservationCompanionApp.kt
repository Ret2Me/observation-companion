package pl.put.observationcompanion

import android.app.Application
import pl.put.observationcompanion.di.AppContainer

class ObservationCompanionApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
