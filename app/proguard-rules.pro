# Keep Shizuku API, provider, and our AIDL UserService intact under R8 minification.
-keep class rikka.shizuku.** { *; }
-keep class com.vitalauncher.app.recents.IRecentsService { *; }
-keep class com.vitalauncher.app.recents.IRecentsService$** { *; }
-keep class com.vitalauncher.app.recents.ShizukuRecentsUserService { *; }

