# WorkshopNative ships with reflection-heavy third-party libraries.
# JavaSteam loads crypto providers by class name, so provider implementations
# must survive shrinking in release builds.
-keep class in.dragonbra.javasteam.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.spongycastle.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.spongycastle.**
-dontwarn org.bouncycastle.**
