import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import arrow.AutoCloseScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

private val suspenderCompositionLocal = staticCompositionLocalOf<Suspender> { error("No Suspender provided") }

@PublishedApi
internal val currentSuspender: Suspender
  @Composable get() = suspenderCompositionLocal.current

private val inFastPathResult = Result.failure<Nothing>(IllegalStateException("Effect has not been run yet"))
private val unsetResult = Result.failure<Nothing>(IllegalStateException("Effect has not been set yet"))

@PublishedApi
internal class EffectState<R> {
  var value: Result<R> = inFastPathResult
}

// TODO: marking this with @DisallowComposableCalls causes some crashes when early-returning. Might be Compose bug.
@Composable
@ResetDsl
public inline fun <R> effect(block: @DisallowComposableCalls () -> R): R = with(remember { EffectState<R>() }) {
  if (currentSuspender.reachedResumePoint) value = Result.success(block())
  value.getOrThrow()
}

@PublishedApi
internal class Suspender(
  private val clock: GatedFrameClock,
  private val recomposer: Recomposer,
) {
  private lateinit var recomposeScope: RecomposeScope
  private lateinit var bodyRecomposeScope: RecomposeScope
  private var resumeToken: Any? = null

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  @PublishedApi
  internal fun <T> EffectState<T>.suspendComposition(
    recomposeScope: RecomposeScope, block: (Continuation<T>) -> Unit
  ): T {
    this@Suspender.recomposeScope = recomposeScope
    if (reachedResumePoint) {
      value = inFastPathResult
      val continuation = CompositionContinuation(this)
      block(continuation)
      // Fast path: if the first "suspending" call in `block` is `resumeWith`, we can use the value immediately
      if (resumeToken != this) {
        value = unsetResult
        throw Suspended(this@Suspender)
      }
    }
    if (resumeToken == this) {
      reachedResumePoint = true
    }
    return value.getOrThrow()
  }

  @PublishedApi
  internal fun <R> startSuspendingComposition(
    emitter: (Result<R>) -> Unit, body: @Composable () -> R
  ) = Composition(UnitApplier, recomposer).setContent {
    val suspender = Suspender(clock, recomposer)
    CompositionLocalProvider(suspenderCompositionLocal provides suspender) {
      suspender.bodyRecomposeScope = currentRecomposeScope
      val res = runCatchingComposable { body() }
      clock.isRunning = false
      val exception = res.exceptionOrNull()
      if (exception !is Suspended) {
        CoroutineScope(recomposer.effectCoroutineContext).launch {
          recomposer.awaitIdle()
          emitter(res)
        }
      } else {
        check(exception.token == suspender || exception.token == null)
      }
    }
  }

  private inner class CompositionContinuation<T>(private val state: EffectState<T>) : Continuation<T> {
    override val context: CoroutineContext get() = recomposer.effectCoroutineContext

    override fun resumeWith(result: Result<T>) {
      val inFastPath = state.value == inFastPathResult
      state.value = result
      resumeToken = state
      if (!inFastPath) CoroutineScope(context).launch {
        recomposer.awaitIdle()
        // TODO do we need both scope invalidations?
        recomposeScope.invalidate()
        bodyRecomposeScope.invalidate()
        reachedResumePoint = false
        clock.isRunning = true
      }
    }
  }

  companion object {
    @OptIn(ExperimentalStdlibApi::class)
    @PublishedApi
    internal fun AutoCloseScope.suspender(context: CoroutineContext): Suspender {
      val job = Job(context[Job])
      install(AutoCloseable { job.cancel() })
      val clock = GatedFrameClock()
      val recomposer = CoroutineScope(context + job + clock).launchRecomposer()
      return Suspender(clock, recomposer)
    }
  }
}

@OptIn(InternalCoroutinesApi::class)
@Composable
@ResetDsl
public fun <T> await(block: suspend () -> T): T = suspendComposition { cont ->
  CoroutineStart.UNDISPATCHED({ block() }, Unit, cont)
}

@Composable
@PublishedApi
internal inline fun <T> suspendComposition(
  crossinline block: Suspender.(Continuation<T>) -> Unit
): T = with(currentSuspender) {
  remember { EffectState<T>() }.suspendComposition(currentRecomposeScope) @DontMemoize {
    block(it)
  }
}

private object UnitApplier : AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}

internal class Suspended(val token: Suspender?) : Exception("Composable was suspended up to $token")