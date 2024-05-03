import Reset.Companion.lazyReset
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
  private val clock: GatedFrameClock,
  private val composition: ControlledComposition,
  private val recomposer: Recomposer
) {
  private var currentContinuation: Continuation<R> = output
  private lateinit var recomposeScope: RecomposeScope
  private lateinit var bodyRecomposeScope: RecomposeScope
  private var resumeToken: Any? = null

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  private fun emitter(res: Result<R>) {
    clock.isRunning = false
    CoroutineScope(recomposer.effectCoroutineContext).launch {
      recomposer.awaitIdle()
      val exception = res.exceptionOrNull()
      if (exception is Suspended && exception.reset === this@Reset) {
        return@launch
      }
      currentContinuation.resumeWith(res)
    }
  }

  @Composable
  private fun runReset(body: @Composable Reset<R>.() -> R): Result<R> {
    bodyRecomposeScope = currentRecomposeScope
    return runCatchingComposable { body() }
  }

  internal suspend fun resumeAt(token: Any): R {
    resumeToken = token
    // If composition is composing, we're likely on the fast path, so no need for invalidations
    if (!composition.isComposing) {
      // TODO do we need both scope invalidations?
      recomposeScope.invalidate()
      bodyRecomposeScope.invalidate()
      reachedResumePoint = false
      clock.isRunning = true
    }
    return suspendCoroutine { currentContinuation = it }
  }

  @OptIn(InternalCoroutinesApi::class)
  public fun <T> ShiftState<T, R>.configure(recomposeScope: RecomposeScope, producer: suspend (Shift<T, R>) -> R): T {
    this@Reset.recomposeScope = recomposeScope
    if (reachedResumePoint) {
      val cont = currentContinuation
      CoroutineStart.UNDISPATCHED(producer, this, cont)
      // Fast path: if the first call in `producer` is `resumeAt`, we can use the value immediately
      if (resumeToken != this) throw Suspended(this@Reset)
    }
    if (resumeToken == this) {
      reachedResumePoint = true
    }
    return state
  }

  @Composable
  public fun <T> reset(
    body: @Composable Reset<T>.() -> T
  ): T = await {
    val composition = ControlledComposition(UnitApplier, recomposer)
    suspendCoroutine {
      val reset = Reset(it, clock, composition, recomposer)
      composition.setContent {
        reset.emitter(reset.runReset(body))
      }
    }
  }

  override fun equals(other: Any?): Boolean {
    return false
  }

  public companion object {
    public suspend fun <R> ResourceScope.lazyReset(
      body: @Composable Reset<R>.() -> R
    ): R {
      val job = Job(coroutineContext[Job])
      onRelease { job.cancelAndJoin() }

      val clock = GatedFrameClock()
      val scope = CoroutineScope(coroutineContext + job + clock)
      val (composition, recomposer) = scope.launchMolecule()
      lateinit var reset: Reset<R>
      val result = runCatching {
        suspendCoroutine {
          reset = Reset(it, clock, composition, recomposer)
          composition.setContent {
            reset.emitter(reset.runReset(body))
          }
        }
      }
      return result.getOrThrow()
    }
  }
}

public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(body) }

private class Suspended(val reset: Reset<*>) : CancellationException("Composable was suspended")