import androidx.compose.runtime.*
import arrow.fx.coroutines.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ResetDsl

@Suppress("EqualsOrHashCode")
@ResetDsl
@Stable
public class Reset<R> internal constructor(
  output: Continuation<R>,
  internal val clock: GatedFrameClock,
  private val composition: ControlledComposition,
  internal val recomposer: Recomposer
) {
  private var currentContinuation: Continuation<R> = output
  private var resumeToken: Any? = null

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  internal fun emitter(res: Result<R>) {
    clock.isRunning = false
    res.fold(currentContinuation::resume) {
      if (it !is Suspended || it.reset !== this@Reset) {
        currentContinuation.resumeWithException(it)
      }
    }
  }

  @Composable
  internal fun runReset(body: @Composable Reset<R>.() -> R): Result<R> {
    return runCatchingComposable { body() }
  }

  internal suspend fun resumeAt(token: Any) = suspendCoroutine { continuation ->
    composition.invalidateAll()
    reachedResumePoint = false
    currentContinuation = continuation
    resumeToken = token
    clock.isRunning = true
  }

  internal fun <T> ShiftState<T, R>.configure(producer: suspend (Shift<T, R>) -> R): T {
    if (reachedResumePoint) {
      val cont = currentContinuation
      CoroutineScope(currentContinuation.context).launch(start = CoroutineStart.UNDISPATCHED) {
        cont.resumeWith(runCatching { producer(this@configure) })
      }
      if (resumeToken != this) {
        throw Suspended(this@Reset)
      }
    }
    if (resumeToken == this) {
      reachedResumePoint = true
    }
    return state
  }

  override fun equals(other: Any?): Boolean {
    return false
  }
}

public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(body) }

public suspend fun <R> ResourceScope.lazyReset(
  body: @Composable Reset<R>.() -> R
): R {
  val job = Job(coroutineContext[Job])
  onRelease { job.cancelAndJoin() }
  return suspendCoroutine { cont ->
    val clock = GatedFrameClock()
    val scope = CoroutineScope(cont.context + job + clock)
    val (composition, recomposer) = scope.launchMolecule()
    val reset = Reset(cont, clock, composition, recomposer)
    composition.setContent {
      reset.emitter(reset.runReset(body))
    }
  }
}

private class Suspended(val reset: Reset<*>) : CancellationException("Composable was suspended")