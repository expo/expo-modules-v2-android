package expo.modules.v2.async

interface JSScheduler {
  /** Whether the caller is already on the JS thread, so [post] would be a detour. */
  val isOnJSThread: Boolean

  /**
   * Enqueues [job] for the JS thread. Never throws, and silently drops the job once the runtime
   * has been closed.
   */
  fun post(job: Runnable)

  /**
   * Runs every job queued right now and returns how many ran.
   *
   * MUST run on the JS thread, and MUST NOT be called from inside a host function: a job settles a
   * promise through the shared binary buffer, and that buffer is already claimed for the arguments
   * of a call in flight.
   */
  fun drainPending(): Int = 0

  /** Blocks until a job arrives or [timeoutNanos] elapses. Returns whether one arrived. */
  fun awaitJob(timeoutNanos: Long): Boolean = false

  /** Stops accepting jobs and discards the queue. Idempotent. */
  fun close() {}
}
