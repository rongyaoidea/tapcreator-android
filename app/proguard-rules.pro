# Retrofit / OkHttp（consumer 规则已自带，此处仅保留泛型签名）
-keepattributes Signature, Exceptions

# kotlinx.serialization：AGP/R8 官方插件已处理大部分，此处仅保留多态与 Companion
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.tapcreator.app.**$$serializer { *; }
-keepclasseswithmembers class com.tapcreator.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room：consumer 规则已保留 Entity/Dao 实现，仅保留 Database 子类构造
-keep class * extends androidx.room.RoomDatabase { *; }

# Hilt：官方 Gradle 插件 + bytecode transform 已保留入口点/模块语义，
# 此处不再整包 -keep（原 -keep dagger.hilt.** / ViewModel / di.** 会架空 R8）。
# 仅保留反射巡检需要的注解属性。
-keepattributes *Annotation*
-keepattributes Metadata
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.hilt.android.HiltAndroidApp class *