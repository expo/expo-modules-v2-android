-keepclassmembers @io.github.expo.modules.v2.ExpoModule class * {
  <init>();
  static ** INSTANCE;
}

-keepclassmembers class * implements io.github.expo.modules.v2.records.Record {
  static ** recordCodec$ExpoModulesV2;
}

# A union's codec registers itself while the union type initializes. A sealed class holds it in a
# static field of its own; a sealed interface holds it in its companion, which its `Companion`
# field initializes.
-keepclassmembers class * implements io.github.expo.modules.v2.unions.Union {
  static ** unionCodec$ExpoModulesV2;
  static ** Companion;
}
-keepclassmembers class **$Companion {
  static ** unionCodec$ExpoModulesV2;
}

-keepclassmembers enum * implements io.github.expo.modules.v2.Enumerable {
  public static **[] values();
}
-keepclassmembers,allowobfuscation enum * implements io.github.expo.modules.v2.Enumerable {
  !static <fields>;
}
