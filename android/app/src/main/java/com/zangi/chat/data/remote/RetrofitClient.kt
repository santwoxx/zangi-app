package com.zangi.chat.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Endereço padrão apontando para o IP do seu computador na rede local (Wi-Fi/Ethernet)
    // Para testar em emulador: http://10.0.2.2:3000/
    // Para produção no Render: https://seu-backend.onrender.com/
    private var currentBaseUrl: String = "http://192.168.18.74:3000/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
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
