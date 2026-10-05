# 保留无障碍服务与前台服务（清单反射实例化）
-keep class * extends android.accessibilityservice.AccessibilityService
-keep class * extends android.app.Service { *; }

# DataStore / coroutines 元数据
-dontwarn kotlinx.coroutines.**
