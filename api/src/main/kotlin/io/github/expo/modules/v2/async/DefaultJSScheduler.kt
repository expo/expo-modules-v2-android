package io.github.expo.modules.v2.async

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class DefaultJSScheduler : JSScheduler {
  private val owner: Thread = Thread.currentThread()
  private val jobs = ConcurrentLinkedQueue<Runnable>()

  // Permits track queued jobs, so a waiter wakes as soon as one arrives instead of polling.
  private val arrivals = Semaphore(0)

  @Volatile
  private var closed = false

  override val isOnJSThread: Boolean
    get() = Thread.currentThread() === owner

  override fun post(job: Runnable) {
    if (closed) {
      return
    }
    jobs.add(job)
    arrivals.release()
  }

  override fun drainPending(): Int {
    check(isOnJSThread) {
      "drainPending() ran on ${Thread.currentThread().name}, but the JS thread is ${owner.name}"
    }

    var ran = 0
    while (true) {
      val job = jobs.poll()
        ?: return ran
      arrivals.tryAcquire()
      ran += 1
      // One failing job must not strand the rest of the queue, and a settle already converts
      // everything it can into a rejection — so anything reaching here is a bug worth printing.
      try {
        job.run()
      } catch (throwable: Throwable) {
        System.err.println("expo-modules-v2: a JS-thread job failed")
        throwable.printStackTrace()
      }
    }
  }

  override fun awaitJob(timeoutNanos: Long): Boolean =
    !closed && arrivals.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS).also { acquired ->
      // The permit only signalled arrival; drainPending() owns the queue, so give it back.
      if (acquired) {
        arrivals.release()
      }
    }

  override fun close() {
    closed = true
    jobs.clear()
    // Wake anything blocked in awaitJob so a close cannot leave a thread parked forever.
    arrivals.release(Int.MAX_VALUE / 2)
  }
}
