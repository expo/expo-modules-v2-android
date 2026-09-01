package io.github.expo.modules.v2.compiler

enum class BufferChoice {
  AUTO,
  YES,
  NO;

  fun orElse(wider: BufferChoice): BufferChoice = if (this == AUTO) {
    wider
  } else {
    this
  }
}
