
# FFmpeg audio renderer is created by reflection from DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Fast resume of .ts movies switches off the extractor's step-by-step search by reflection
-keepclassmembers class androidx.media3.extractor.ts.TsExtractor { *** tsBinarySearchSeeker; }
