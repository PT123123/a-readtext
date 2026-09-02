# Keep sherpa-onnx native-backed classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * { @androidx.room.* <methods>; }

-dontwarn org.apache.commons.compress.**
