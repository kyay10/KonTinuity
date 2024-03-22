import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime

// Adapted from: https://gist.github.com/elizarov/9a48b9709ffd508909d34fab6786acfe
internal suspend inline fun <T> Mutex.withReentrantLock(crossinline block: suspend () -> T): T {
  val key = ReentrantMutexContext(this)
  // call block directly when this mutex is already locked in the context
  if (coroutineContext[key] != null) return block()
  // otherwise add it to the context and lock the mutex
  return withContext(key) {
    withLock { block() }
  }
}

internal data class ReentrantMutexContext(
  val mutex: Mutex
) : CoroutineContext.Key<ReentrantMutexContext>, CoroutineContext.Element {
  override val key: CoroutineContext.Key<*>
    get() = this
}

// Adapted from https://gist.github.com/paulo-raca/ef6a827046a5faec95024ff406d3a692
internal class Condition(public val mutex: Mutex) {
  private val waiting = mutableListOf<Mutex>()

  /**
   * Blocks this coroutine until the predicate is true
   *
   * The associated mutex is unlocked while this coroutine is awaiting
   *
   */
  @OptIn(ExperimentalTime::class)
  suspend inline fun awaitUntil(owner: Any? = null, predicate: () -> Boolean) {
    awaitUntil(Duration.INFINITE, owner, predicate)
  }

  /**
   * Blocks this coroutine until the predicate is true or the specified timeout has elapsed
   *
   * The associated mutex is unlocked while this coroutine is awaiting
   *
   * @return true If this coroutine was waked by signal() or signalAll(), false if the timeout has elapsed
   */
  @ExperimentalTime
  suspend inline fun awaitUntil(timeout: Duration, owner: Any? = null, predicate: () -> Boolean): Boolean {
    val start = nanoTime()
    while (!predicate()) {
      val elapsed = (nanoTime() - start).nanoseconds
      val remainingTimeout = timeout - elapsed
      if (remainingTimeout < Duration.ZERO) {
        return false  // Timeout elapsed without success
      }
      await(remainingTimeout, owner)
    }
    return true
  }

  /**
   * Blocks this coroutine until unblocked by signal() or signalAll()
   *
   * The associated mutex is unlocked while this coroutine is awaiting
   *
   */
  @OptIn(ExperimentalTime::class)
  suspend fun await(owner: Any? = null) {
    await(Duration.INFINITE, owner)
  }

  /**
   * Blocks this coroutine until unblocked by signal() or signalAll(), or the specified timeout has elapsed
   *
   * The associated mutex is unlocked while this coroutine is awaiting
   *
   * @return true If this coroutine was waked by signal() or signalAll(), false if the timeout has elapsed
   */
  @ExperimentalTime
  suspend fun await(timeout: Duration = Duration.INFINITE, owner: Any? = null): Boolean {
    ensureLocked(owner, "await")
    val waiter = Mutex(true)
    waiting.add(waiter)
    mutex.unlock(owner)
    return try {
      withTimeout(timeout) {
        waiter.lock()
      }
      true
    } catch (e: TimeoutCancellationException) {
      false
    } finally {
      mutex.lock(owner)
      waiting.remove(waiter)
    }
  }

  /**
   * Wakes up one coroutine blocked in await()
   */
  fun signal(owner: Any? = null) {
    ensureLocked(owner, "signal")
    waiting.removeLastOrNull()?.unlock()
  }

  /**
   * Wakes up all coroutines blocked in await()
   */
  fun signalAll(owner: Any? = null) {
    ensureLocked(owner, "signalAll")
    for (waiter in waiting.asReversed()) {
      waiter.unlock()
    }
    waiting.clear()
  }

  private fun ensureLocked(owner: Any?, funcName: String) {
    val isLocked = if (owner == null) mutex.isLocked else mutex.holdsLock(owner)
    if (!isLocked) {
      throw IllegalStateException("$funcName requires a locked mutex")
    }
  }
}

internal fun Mutex.newCondition(): Condition {
  return Condition(this)
}