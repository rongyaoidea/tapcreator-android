# Retrofit / OkHttp
-keepattributes Signature, Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.tapcreator.app.**$$serializer { *; }
-keepclassmembers class com.tapcreator.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.tapcreator.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-keep class dagger.hilt.android.internal.lifecycle.HiltViewModel { *; }
-keep class * extends dagger.hilt.android.AndroidEntryPoint
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keep class dagger.hilt.android.HiltAndroidApp
-keepattributes @dagger.hilt.android.AndroidEntryPoint
-keepattributes @dagger.hilt.android.HiltAndroidApp
-keepattributes @dagger.Module
-keepattributes @dagger.Provides
-keepattributes @dagger.hilt.InstallIn
-keepattributes @javax.inject.Inject
-keepattributes kotlinx.coroutines.CoroutineContext
-keepclassmembers class * {
    @javax.inject.Inject *;
    @dagger.Provides *;
    @dagger.Module *;
    @dagger.hilt.InstallIn *;
}
-keepclasseswithmembers class * {
    @javax.inject.Inject *;
}
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Keep Hilt generated component classes
-keep class com.tapcreator.app.di.** { *; }
-keepattributes Metadata
-keep class androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Prevent ProGuard from stripping Hilt's generated code
-keep class * @dagger.hilt.android.AndroidEntryPoint { *; }
-keep class * @dagger.hilt.android.HiltAndroidApp { *; }
-keep class * @dagger.Module { *; }
-keep class * @dagger.Provides { *; }