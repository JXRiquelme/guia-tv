
# FFmpeg audio renderer is created by reflection from DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Fast resume of .ts movies switches off the extractor's step-by-step search by reflection
-keepclassmembers class androidx.media3.extractor.ts.TsExtractor { *** tsBinarySearchSeeker; }

# Sign in with Google (Credential Manager finds its Play services provider by reflection)
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
