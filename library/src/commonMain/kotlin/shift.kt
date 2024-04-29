import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

public class Shift<T, R> internal constructor(private val reset: Reset<R>) : RememberObserver {
  @Suppress("UNCHECKED_CAST")
  private var state: T = null as T

  private var job: Job? = null

  public suspend operator fun invoke(value: T): R {
    state = value
    return reset.resumeAt(this)
  }

  internal fun configure(producer: suspend (Shift<T, R>) -> R): T = with(reset) {
    if (reachedResumePoint) {
      val previousContinuation = currentContinuation
      job?.cancel()
      val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
        previousContinuation.resume(producer(this@Shift))
      }
      this@Shift.job = job
      suspendComposition(job)
    } else {
      reachedResumeToken(this@Shift)
      state
    }
  }

  override fun onAbandoned() {
    job?.cancel()
  }

  override fun onForgotten() {
    job?.cancel()
  }

  override fun onRemembered() {}
}

@NonRestartableComposable
@Composable
public fun <T, R> Reset<R>.shift(block: suspend (Shift<T, R>) -> R): T = remember { Shift<T, R>(this) }.configure(block)

@Composable
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

// TODO: investigate if we can reuse Recomposer and GatedFrameClock here
@Composable
public fun <T, R> Reset<R>.reset(
  body: @Composable Reset<T>.() -> T
): T = await { resetAliased(body) }

private suspend fun <R> resetAliased(
  body: @Composable Reset<R>.() -> R
): R = reset(body)

@Composable
public inline fun <T, R> Reset<R>.await(crossinline block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}