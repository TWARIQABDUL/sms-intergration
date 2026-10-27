package com.smsgateway.app.network

import com.smsgateway.app.data.SecurePrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a Retrofit client against whatever server URL / device token are
 * currently saved in [SecurePrefs]. A new client is built per call site
 * since the server URL can change from Settings; this is cheap and avoids
 * stale base URLs living in a long-lived singleton.
 */
object ApiClient {

    fun create(prefs: SecurePrefs): ApiService {
        val baseUrl = prefs.serverUrl?.trimEnd('/')?.plus("/")
            ?: error("Server URL is not configured")

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = prefs.deviceToken ?: ""
                val request: Request = chain.request().newBuilder()
                    .addHeader("X-Device-Token", token)
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
