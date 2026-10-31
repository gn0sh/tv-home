# R8 rules for the release build (architecture section 4, security findings #3).

# 1. No android.util.Log call survives in release: strips ours and every library's. (System.out/err and java.util.logging
#    are not covered; the only System.err line is the crash handler's class-names-only text.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# 2. OkHttp optional platform classes that are not on the device (its own consumer rules cover most; these are harmless repeats).
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
