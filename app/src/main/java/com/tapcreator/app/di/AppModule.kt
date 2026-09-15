package com.tapcreator.app.di

import android.content.Context
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.prefs.SettingsStore
import com.tapcreator.app.data.security.KeyStoreManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.get(context)

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    fun provideKeyStoreManager(): KeyStoreManager = KeyStoreManager()

    // 基础 OkHttpClient：短超时面向文本/图片等常规请求（10s 连接/60s 读/60s 写），
    // 失败快速暴露而非 hang 5 分钟。长时场景（视频段下载/大文件）由下游经 newBuilder()
    // 派生覆盖超时，共享连接池与调度器，避免各自 new client 泄漏线程池。
    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
        // 仅 Debug 打印请求行/耗时，避免 Release 泄露 Authorization 与提示词
        if (com.tapcreator.app.BuildConfig.DEBUG) {
            builder.addInterceptor(
                okhttp3.logging.HttpLoggingInterceptor().apply {
                    level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                }
            )
        }
        return builder.build()
    }

    /** 视频/大文件下载专用：在基础 client 上派生长读超时（300s），共享连接池。 */
    @Provides
    @Singleton
    @javax.inject.Named("download")
    fun provideDownloadClient(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
        base.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
}