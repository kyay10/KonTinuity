import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import Suspender.Companion.suspender
import kotlin.coroutines.suspendCoroutine

private val suspenderCompositionLocal = staticCompositionLocalOf<Suspender> { error("No Suspender provided") }

@PublishedApi
internal val currentSuspender: Suspender
  @Composable get() = suspenderCompositionLocal.current

@PublishedApi
internal class EffectState<R> {
  @Suppress("UNCHECKED_CAST")
  var value: Result<R> = Result.success(null as R)
}

// TODO: marking this with @DisallowComposableCalls causes some crashes when early-returning. Might be Compose bug.
@Composable
@ResetDsl
public inline fun <R> effect(block: () -> R): R = with(remember { EffectState<R>() }) {
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
      val continuation = CompositionContinuation(this)
      block(continuation)
      continuation.inFastPath = false
      // Fast path: if the first call in `block` is `resumeWith`, we can use the value immediately
      if (resumeToken != this) throw Suspended(this@Suspender)
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
      }
    }
  }

  private inner class CompositionContinuation<T>(private val state: EffectState<T>) : Continuation<T> {
    var inFastPath: Boolean = true
    override val context: CoroutineContext = recomposer.effectCoroutineContext

    override fun resumeWith(result: Result<T>) {
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
    @PublishedApi
    internal suspend fun ResourceScope.suspender(): Suspender {
      val job = Job(coroutineContext[Job])
      onRelease { job.cancelAndJoin() }
      val clock = GatedFrameClock()
      val recomposer = CoroutineScope(coroutineContext + job + clock).launchRecomposer()
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

public suspend fun <R> awaitSuspendingComposition(block: @Composable () -> R): R = resourceScope {
  with(suspender()) {
    suspendCoroutine {
      startSuspendingComposition(it::resumeWith, block)
    }
  }
}

private object UnitApplier : AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}

internal class Suspended(val token: Any) : Exception("Composable was suspended up to $token")