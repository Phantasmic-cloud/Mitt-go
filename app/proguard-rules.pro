-keep class com.phantasmic.mitt.go.core.** { *; }
-keep class go.** { *; }

# JNI：GoCoreJni 的 external 方法名必须原样保留，否则 System.loadLibrary 后找不到符号。
-keep class com.phantasmic.mitt.go.GoCoreJni { *; }
-keepclasseswithmembernames class com.phantasmic.mitt.go.GoCoreJni { native <methods>; }

# 崩溃页 / 入口类由 manifest 引用，保留名字方便看 tombstone。
-keep class com.phantasmic.mitt.go.MainActivity { *; }
-keep class com.phantasmic.mitt.go.CrashActivity { *; }
-keep class com.phantasmic.mitt.go.ProxyVpnService { *; }

# 用了反射/序列化的 JSON 模型字段名
-keepclassmembers class com.phantasmic.mitt.go.** { *; }
