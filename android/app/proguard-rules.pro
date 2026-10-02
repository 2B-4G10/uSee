# OkHttp and Okio ship their own consumer rules; these silence optional
# platform integrations that are not present on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
