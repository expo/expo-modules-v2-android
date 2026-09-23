package io.github.expo.modules.v2

open class ExpoContext : AutoCloseable {
  /** Set by [close]. An object bound to a closed context is free to be bound to another one. */
  @Volatile
  var isClosed: Boolean = false
    private set

  /**
   * Closes the context. A runtime closes the context it created itself; a context passed to one
   * or more runtimes belongs to the caller, who closes it once the last of them is gone.
   */
  override fun close() {
    isClosed = true
  }
}
