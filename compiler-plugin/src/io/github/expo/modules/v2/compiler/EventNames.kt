package io.github.expo.modules.v2.compiler

/**
 * The JavaScript name of an `@Event` property that names none itself: a leading `on` followed by
 * an upper-case letter is dropped and the letter lower-cased, so `onChanged` is subscribed to as
 * `changed`. Any other name is used as it is. Shared by the frontend (duplicate-name check) and the
 * backend (the name it binds), so both agree.
 */
fun eventJsName(propertyName: String): String {
  if (propertyName.length > 2 && propertyName.startsWith("on") && propertyName[2].isUpperCase()) {
    return propertyName.substring(2).replaceFirstChar { it.lowercaseChar() }
  }
  return propertyName
}
