# kotlinx.serialization：保留 @Serializable 生成的 serializer 与 Companion，
# 否则 R8 会把运行期反序列化需要的入口裁掉。
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.moontvplus.**$$serializer {
    *;
}
-keepclassmembers class com.moontvplus.** {
    *** Companion;
}
-keepclasseswithmembers class com.moontvplus.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# Retrofit：保留接口方法上的 HTTP 注解与泛型签名。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
