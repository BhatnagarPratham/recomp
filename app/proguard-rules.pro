# Anthropic Java SDK (M0 spike): Jackson maps the SDK's own request/response models by reflection.
# Start broad; narrow these once the spike shows which rules R8 actually needs.
-keep class com.anthropic.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Referenced only by the SDK's class-based structured outputs, which we don't use because Android
# has no java.lang.reflect.AnnotatedType (see ClaudeSpikeClient), and by OpenTelemetry's AutoValue.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn com.google.auto.value.AutoValue
