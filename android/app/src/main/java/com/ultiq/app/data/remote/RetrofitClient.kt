package com.ultiq.app.data.remote

import com.ultiq.app.BuildConfig
import com.ultiq.app.util.TokenManager
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
