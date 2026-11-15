package com.dormpanel.app.weather

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration

/** Verify a single ID against the separately published reference list. The list stays in memory. */
internal class PublicWeatherLocations(http: OkHttpClient, private val url: HttpUrl = LIST_URL) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(Duration.ofSeconds(15)).build()

    fun verify(location: WeatherSourceRef.QWeatherCity, done: (Boolean?) -> Unit): () -> Unit {
        val call = client.newCall(Request.Builder().url(url).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) done(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val found = response.use {
                    runCatching {
                        check(it.isSuccessful)
                        val source = checkNotNull(it.body).source()
                        check(!source.request(2L * 1024 * 1024 + 1))
                        containsId(source.readUtf8(), location.locationId)
                    }.getOrNull()
                }
                if (!call.isCanceled()) done(found)
            }
        })
        return { call.cancel() }
    }

    companion object {
        val LIST_URL = "https://raw.githubusercontent.com/qwd/LocationList/master/China-City-List-latest.csv".toHttpUrl()

        fun containsId(csv: String, id: String): Boolean {
            val lines = csv.lineSequence().iterator()
            check(lines.hasNext() && lines.next().trimStart('\uFEFF').startsWith("China-City-List"))
            check(lines.hasNext() && lines.next().startsWith("Location_ID,"))
            return lines.asSequence().any { it.substringBefore(',') == id }
        }
    }
}
