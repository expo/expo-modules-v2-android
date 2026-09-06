package io.github.expo.modules.v2.loader

object ExpoClassLoader {
  fun loadAndInitializeClass(clazz: Class<*>): Boolean {
    runCatching { Class.forName(clazz.name, /* initialize = */ true, clazz.classLoader) }
      .onFailure {
        return false
      }

    return true
  }
}
