-keepclassmembers @io.github.expo.modules.v2.ExpoModule class * {
  <init>();
  static ** INSTANCE;
}

-keepclassmembers class * implements io.github.expo.modules.v2.records.Record {
  static ** recordCodec$ExpoModulesV2;
}

-keepclassmembers enum * implements io.github.expo.modules.v2.Enumerable {
  public static **[] values();
}
-keepclassmembers,allowobfuscation enum * implements io.github.expo.modules.v2.Enumerable {
  !static <fields>;
}
