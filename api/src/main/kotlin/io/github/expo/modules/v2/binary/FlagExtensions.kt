package io.github.expo.modules.v2.binary

fun Boolean.toBinaryFlag(trueFlag: Int = 1): Int = if (this) {
  trueFlag
} else {
  0
}
