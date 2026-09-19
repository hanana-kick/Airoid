# Airoid release rules.
-keepclassmembers class * implements com.airoid.bridge.RaopCallbackHandler {
    void on*(...);
    float onClientVolume();
}

# libairplay_native.so가 GetMethodID로 정확한 이름+시그니처로 조회한다
# (android_raop_callbacks.c android_callbacks_init, log_sink.h).
# 클래스명은 인스턴스에서 GetObjectClass하므로 리네임돼도 무방하지만 멤버명은 고정.
-keepclassmembers class * implements com.airoid.bridge.LogListener {
    void onLog(java.lang.String);
}
# AirPlay receiver networking (mDNS/Bonjour) is obfuscated-safe; keep names only if reflection is used.
# Add rules as the receiver engine is implemented.
