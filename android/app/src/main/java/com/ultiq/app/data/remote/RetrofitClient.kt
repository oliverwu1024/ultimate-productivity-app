package com.ultiq.app.data.remote

import com.ultiq.app.BuildConfig
import com.ultiq.app.util.TokenManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private var apiService: ApiService? = null

    fun create(tokenManager: TokenManager): ApiService {
        return apiService ?: synchronized(this) {
            apiService ?: buildApiService(tokenManager).also { apiService = it }
        }
    }

    private fun buildApiService(tokenManager: TokenManager): ApiService {
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenManager))
            // Coach chat turns that fetch data make two Bedrock round-trips
            // (~15-30s). OkHttp's 10s default read timeout aborted them every
            // time — surfacing as a "server error" the moment you pick a
            // clarification option (or ask about a period), while the quick
            // clarification-question turn squeaks under 10s. Generous timeouts
            // let slow LLM turns finish; the backend/ALB cap the upper bound.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
                redactHeader("Cookie")
            }
            builder.addInterceptor(logging)
        }

        val client = builder.build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
