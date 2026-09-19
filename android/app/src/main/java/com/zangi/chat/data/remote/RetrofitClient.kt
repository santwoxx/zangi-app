package com.zangi.chat.data.remote

import com.zangi.chat.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Endereço oficial em produção no Render: https://zangi-app.onrender.com/
    private var currentBaseUrl: String = "https://zangi-app.onrender.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("X-Zangi-Auth-Key", BuildConfig.API_SECRET_KEY)
        
        chain.proceed(requestBuilder.build())
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor) // Injeta o Header em todas as requisições
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private var cachedRetrofit: Retrofit? = null
    private var cachedApiService: ApiService? = null

    fun getBaseUrl(): String = currentBaseUrl

    @Synchronized
    fun setBaseUrl(newUrl: String) {
        val sanitizedUrl = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        currentBaseUrl = sanitizedUrl
        cachedRetrofit = null
        cachedApiService = null
    }

    @Synchronized
    fun getApiService(): ApiService {
        if (cachedApiService == null) {
            val retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            cachedRetrofit = retrofit
            cachedApiService = retrofit.create(ApiService::class.java)
        }
        return cachedApiService!!
    }

    fun getFullUrl(relativePath: String?): String? {
        if (relativePath.isNullOrEmpty()) return null
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
        val cleanRelative = if (relativePath.startsWith("/")) relativePath.substring(1) else relativePath
        return "$currentBaseUrl$cleanRelative"
    }
}
