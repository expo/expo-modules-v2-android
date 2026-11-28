package io.github.expo.modules.v2.loader

object ExpoClassLoader {
  fun loadAndInitializeClass(clazz: Class<*>): Boolean {
    // `true`: run the class's static initializers, not only load it.
    runCatching { Class.forName(clazz.name, true, clazz.classLoader) }
      .onFailure {
        return false
      }

    return true
  }
}
