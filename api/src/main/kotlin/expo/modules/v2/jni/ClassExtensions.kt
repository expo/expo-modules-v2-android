package expo.modules.v2.jni

val <T : Any>  Class<T>.jniDescriptor: String
  get() = "L" + name.replace('.', '/') + ";"
