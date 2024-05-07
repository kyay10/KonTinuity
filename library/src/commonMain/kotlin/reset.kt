import Reset.Companion.lazyReset
import androidx.compose.runtime.*
import arrow.fx.coroutines.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

private val resetCompositionLocal = staticCompositionLocalOf<Reset<*>> { error("No Reset provided") }

@PublishedApi
internal val currentReset: Reset<*>
  @Composable get() = resetCompositionLocal.current

@Suppress("EqualsOrHashCode")
@Stable
public class Reset<R> internal constructor(
  output: Continuation<R>,
  private val clock: GatedFrameClock,
  private val composition: ControlledComposition,
  private val recomposer: Recomposer,
  private val parent: Pair<Reset<*>, EffectState<R>>?,
) {
  private var normalContinuation: Continuation<R> = output
  private var normalContinuationAbortToken: Any? = null
  private var controlContinuation: Continuation<R> = output

  private lateinit var recomposeScope: RecomposeScope
  private lateinit var bodyRecomposeScope: RecomposeScope
  private var resumeToken: Any? = null

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  @Composable
  @PublishedApi
  internal fun runReset(body: @Composable Reset<R>.() -> R): Unit =
    CompositionLocalProvider(resetCompositionLocal provides this) {
      bodyRecomposeScope = currentRecomposeScope
      val res = runCatchingComposable { body(this) }
      clock.isRunning = false
      CoroutineScope(recomposer.effectCoroutineContext).launch {
        recomposer.awaitIdle()
        val exception = res.exceptionOrNull()
        if (exception is Suspended) {
          return@launch
        }
        normalContinuation.resumeWith(res)
      }
    }

  internal suspend fun <T, R2> EffectState<T>.resume(value: T, target: Reset<R2>, isShift: Boolean): R2 {
    this.value = value
    resumeToken = this
    // If composition is composing, we're likely on the fast path, so no need for invalidations
    if (!composition.isComposing) {
      // TODO do we need both scope invalidations?
      recomposeScope.invalidate()
      bodyRecomposeScope.invalidate()
      reachedResumePoint = false
      clock.isRunning = true
    }
    return if (target === this@Reset) {
      receiveResult(isShift, this)
    } else {
      if (parent != null) {
        val result = receiveResult(isShift, this)
        val (parentReset, parentEffect) = parent
        with(parentReset) {
          parentEffect.resume(result, target, isShift)
        }
      } else {
        with(target) {
          println("Warning: no parent reset found, resuming directly, which may lead to unexpected behavior")
          resume(value, target, isShift)
          throw Suspended(this@Reset)
        }
      }
    }
  }

  private suspend fun receiveResult(isShift: Boolean, token: Any): R {
    val previousControlContinuation = controlContinuation
    return try {
      suspendCoroutine {
        normalContinuation = it
        if (isShift) {
          // Replaces the control continuation with this handler's continuation
          // This ensures that the next continuation reifier will resume this handler instead of any previous one
          // or the original output continuation. This is equivalent to wrapping the continuation in a reset
          controlContinuation = it
        } else {
          normalContinuationAbortToken = token
        }
      }
    } finally {
      controlContinuation = previousControlContinuation
    }
  }

  @OptIn(InternalCoroutinesApi::class)
  @PublishedApi
  internal fun <T, R2> EffectState<T>.configure(
    recomposeScope: RecomposeScope, target: Reset<R2>, producer: suspend EffectState<T>.() -> R2
  ): T {
    this@Reset.recomposeScope = recomposeScope
    if (reachedResumePoint) {
      // Trying not to rely on Continuation equality, but it could've been used here
      target.normalContinuationAbortToken?.let { target.normalContinuation.resumeWithException(Suspended(it)) }
      target.normalContinuationAbortToken = null
      CoroutineStart.UNDISPATCHED(producer, this, target.controlContinuation)
      // Fast path: if the first call in `producer` is `resumeAt`, we can use the value immediately
      if (resumeToken != this) throw Suspended(this@Reset)
    }
    if (resumeToken == this) {
      reachedResumePoint = true
    }
    return value
  }

  @Composable
  @ResetDsl
  public fun <T> reset(
    body: @Composable Reset<T>.() -> T
  ): T = gimmeAllControl { k ->
    k.resume(suspendCoroutine {
      val composition = ControlledComposition(UnitApplier, recomposer)
      val reset = Reset(it, clock, composition, recomposer, this@Reset to k)
      composition.setContent { reset.runReset(body) }
    }, this@Reset, true)
  }

  override fun equals(other: Any?): Boolean {
    return false
  }

  public companion object {
    @ResetDsl
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
          reset = Reset(it, clock, composition, recomposer, null)
          composition.setContent { reset.runReset(body) }
        }
      }
      return result.getOrThrow()
    }
  }
}

@ResetDsl
public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(body) }

@PublishedApi
internal class Suspended(val token: Any) : Exception("Composable was suspended up to $token")