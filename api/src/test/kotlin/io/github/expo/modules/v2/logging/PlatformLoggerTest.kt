package io.github.expo.modules.v2.logging

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What [block] writes to `System.err`. */
private fun capturingStdErr(block: () -> Unit): String {
  val original = System.err
  val captured = ByteArrayOutputStream()
  System.setErr(PrintStream(captured, true))
  try {
    block()
  } finally {
    System.setErr(original)
  }
  return captured.toString()
}

class PlatformLoggerTest {
  @Test
  fun `on the desktop, an error goes to System err with its stack trace`() {
    val log = capturingStdErr {
      platformLogger.error("something failed", IllegalStateException("the cause"))
    }
    assertTrue("expo-modules-v2: something failed" in log, "unexpected log: $log")
    assertTrue("java.lang.IllegalStateException: the cause" in log, "unexpected log: $log")
    assertTrue("\tat " in log, "expected a stack trace: $log")
  }

  @Test
  fun `on the desktop, a warning without a throwable is one line`() {
    val log = capturingStdErr { platformLogger.warn("something looks off") }
    assertEquals("expo-modules-v2: something looks off", log.trim())
  }
}
