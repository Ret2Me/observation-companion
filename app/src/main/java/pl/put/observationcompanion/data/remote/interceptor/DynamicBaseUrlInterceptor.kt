package pl.put.observationcompanion.data.remote.interceptor

import pl.put.observationcompanion.data.preferences.PreferencesDataSource
import pl.put.observationcompanion.data.remote.api.HEADER_SATNOGS_HOST
import pl.put.observationcompanion.data.remote.api.HOST_DB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val preferencesDataSource: PreferencesDataSource
) : Interceptor {

    @Volatile
    private var cachedDbBaseUrl = "https://db.satnogs.org/api/"
    @Volatile
    private var cachedNetworkBaseUrl = "https://network.satnogs.org/api/"

    init {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                preferencesDataSource.userSettingsFlow.collect { settings ->
                    cachedDbBaseUrl = settings.dbBaseUrl
                    cachedNetworkBaseUrl = settings.networkBaseUrl
                }
            }
        } catch (e: Exception) {
            // fallback
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val hostHeader = request.header(HEADER_SATNOGS_HOST)

        if (hostHeader != null) {
            val baseUrlStr = if (hostHeader == HOST_DB) {
                cachedDbBaseUrl
            } else {
                cachedNetworkBaseUrl
            }

            val runtimeUrl = baseUrlStr.toHttpUrlOrNull()
            if (runtimeUrl != null) {
                // Swap scheme, host, port, and incorporate path prefixes if present
                val newUrlBuilder = request.url.newBuilder()
                    .scheme(runtimeUrl.scheme)
                    .host(runtimeUrl.host)
                    .port(runtimeUrl.port)

                // For simple subpath mirroring, path segments can be merged if mock url has custom context path (e.g. /api/)
                val baseSegments = runtimeUrl.pathSegments.filter { it.isNotEmpty() }
                val originalSegments = request.url.pathSegments

                // Re-build all path segments
                val newSegments = mutableListOf<String>()
                newSegments.addAll(baseSegments)

                if (originalSegments.isNotEmpty()) {
                    if (originalSegments[0] == "api") {
                        newSegments.addAll(originalSegments.subList(1, originalSegments.size))
                    } else {
                        newSegments.addAll(originalSegments)
                    }
                }

                // Apply the new path preserving trailing slashes properly
                val newPath = "/" + newSegments.joinToString("/")
                newUrlBuilder.encodedPath(newPath)

                request = request.newBuilder()
                    .url(newUrlBuilder.build())
                    .removeHeader(HEADER_SATNOGS_HOST)
                    .build()
            }
        }
        return chain.proceed(request)
    }
}
