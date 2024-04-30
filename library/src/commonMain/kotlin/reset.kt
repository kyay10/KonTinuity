import androidx.compose.runtime.*
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.fx.coroutines.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ResetDsl

@ResetDsl
public class Reset<R> internal constructor(output: Continuation<R>, internal val clock: GatedFrameClock) {
  private var resumeCoroutine: Continuation<Unit>? = null
  private var currentContinuation: Continuation<R> = output
  private var resumeToken: Any? = null

  private lateinit var recomposeScope: RecomposeScope

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  internal fun emitter(res: Result<R>) {
    clock.isRunning = false
    res.fold(currentContinuation::resume) {
      if (it is Suspended && it.reset == this@Reset) {
        resumeCoroutine!!.resume(Unit)
      } else {
        currentContinuation.resumeWithException(it)
      }
    }
  }

  @Composable
  internal fun runReset(body: @Composable Reset<R>.() -> R): Result<R> {
    recomposeScope = currentRecomposeScope
    return runCatchingComposable { body() }
  }

  internal suspend fun resumeAt(token: Any) = suspendCoroutine { continuation ->
    recomposeScope.invalidate()
    reachedResumePoint = false
    currentContinuation = continuation
    resumeToken = token
    clock.isRunning = true
  }

  internal fun <T> ShiftState<T, R>.configure(producer: suspend (Shift<T, R>) -> R): T =
    if (reachedResumePoint) {
      resumeCoroutine = producer.createCoroutine(this@ShiftState, currentContinuation)
      throw Suspended(this@Reset)
    } else {
      if (resumeToken == this) {
        reachedResumePoint = true
      }
      state
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
    val reset = Reset(cont, clock)
    val scope = CoroutineScope(cont.context + job + clock)
    scope.launchMolecule(RecompositionMode.ContextClock, reset::emitter) {
      reset.runReset(body)
    }
  }
}

private class Suspended(val reset: Reset<*>) : CancellationException("Composable was suspended")