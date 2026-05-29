package pl.put.observationcompanion.di

import android.content.Context
import androidx.room.Room
import pl.put.observationcompanion.data.local.AppDatabase
import pl.put.observationcompanion.data.preferences.PreferencesDataSource
import pl.put.observationcompanion.data.remote.api.CelestrakApi
import pl.put.observationcompanion.data.remote.api.SatnogsDbApi
import pl.put.observationcompanion.data.remote.api.SatnogsNetworkApi
import pl.put.observationcompanion.data.remote.interceptor.DynamicBaseUrlInterceptor
import pl.put.observationcompanion.data.repository.SatnogsRepositoryImpl
import pl.put.observationcompanion.data.repository.SettingsRepositoryImpl
import pl.put.observationcompanion.domain.orbit.SatPropagator
import pl.put.observationcompanion.domain.repository.SatnogsRepository
import pl.put.observationcompanion.domain.repository.SettingsRepository
import pl.put.observationcompanion.domain.usecase.BuildDopplerCurveUseCase
import pl.put.observationcompanion.domain.usecase.EvaluateReceptionProbabilityUseCase
import pl.put.observationcompanion.domain.usecase.FilterSatellitesByBandUseCase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

    // 1. Preferences & Data Sources
    val preferencesDataSource: PreferencesDataSource by lazy {
        PreferencesDataSource(context)
    }

    // 2. Room Database
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "observation_companion.db"
        )
        .fallbackToDestructiveMigration() // safe for companion cache
        .build()
    }

    // 3. Network OkHttp & Retrofit with Dynamic Basename Interceptor
    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val dynamicUrlInterceptor = DynamicBaseUrlInterceptor(preferencesDataSource)
        // SatNOGS TOS asks clients to identify themselves; the default OkHttp UA gets
        // throttled aggressively.
        val userAgentInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "LSF-SatNOGS-Companion/1.2 (Android; student project, PUT)")
                .build()
            chain.proceed(req)
        }

        OkHttpClient.Builder()
            .addInterceptor(dynamicUrlInterceptor)
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://db.satnogs.org/api/") // Ignored on requests by DynamicBaseUrlInterceptor 
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val dbApi: SatnogsDbApi by lazy {
        retrofit.create(SatnogsDbApi::class.java)
    }

    val networkApi: SatnogsNetworkApi by lazy {
        retrofit.create(SatnogsNetworkApi::class.java)
    }

    val celestrakApi: CelestrakApi by lazy {
        retrofit.create(CelestrakApi::class.java)
    }

    // 4. Repositories
    val satnogsRepository: SatnogsRepository by lazy {
        SatnogsRepositoryImpl(
            dbApi = dbApi,
            networkApi = networkApi,
            celestrakApi = celestrakApi,
            satelliteDao = database.satelliteDao(),
            tleDao = database.tleDao(),
            transmitterDao = database.transmitterDao(),
            observationDao = database.observationDao()
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(preferencesDataSource)
    }

    // 5. Use cases & Orbit Propagators
    val satPropagator: SatPropagator by lazy {
        SatPropagator()
    }

    val filterSatellitesByBandUseCase: FilterSatellitesByBandUseCase by lazy {
        FilterSatellitesByBandUseCase()
    }

    val evaluateReceptionProbabilityUseCase: EvaluateReceptionProbabilityUseCase by lazy {
        EvaluateReceptionProbabilityUseCase()
    }

    val buildDopplerCurveUseCase: BuildDopplerCurveUseCase by lazy {
        BuildDopplerCurveUseCase()
    }
}
