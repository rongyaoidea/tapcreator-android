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

    // 全局唯一 OkHttpClient：超时按最长的视频生成场景设定（300s 读），
    // 文本/图像等短请求只会提前结束，不受长超时影响。下游各处据此 newBuilder() 派生，
    // 共享连接池与线程，避免每个类各自 new 一个 client。
    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
}