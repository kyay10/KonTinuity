import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

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
  private val composition: ControlledComposition,
  private val recomposer: Recomposer,
) {
  @PublishedApi
  internal lateinit var recomposeScope: RecomposeScope

  @PublishedApi
  internal lateinit var bodyRecomposeScope: RecomposeScope

  @PublishedApi
  internal var resumeToken: Any? = null

  @PublishedApi
  internal var reachedResumePoint: Boolean = true

  internal val compositionContext: CompositionContext
    get() = recomposer

  @PublishedApi
  internal inline fun <T> EffectState<T>.suspendComposition(
    recomposeScope: RecomposeScope, block: (Continuation<T>) -> Unit
  ): T {
    this@Suspender.recomposeScope = recomposeScope
    if (reachedResumePoint) {
      val continuation = CompositionContinuation(this, this@Suspender)
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

  @Composable
  internal fun <R> startSuspendingComposition(
    composition: ControlledComposition, emitter: suspend (Result<R>) -> Unit, body: @Composable () -> R
  ) = recomposer.startSuspendingComposition(composition, clock, emitter, body)

  @PublishedApi
  internal class CompositionContinuation<T>(
    private val state: EffectState<T>, private val suspender: Suspender
  ) : Continuation<T> {
    @PublishedApi
    internal var inFastPath: Boolean = true
    override val context: CoroutineContext = suspender.recomposer.effectCoroutineContext

    override fun resumeWith(result: Result<T>) {
      state.value = result
      suspender.resumeToken = state
      if (!inFastPath)
        CoroutineScope(context).launch {
          suspender.recomposer.awaitIdle()
          // TODO do we need both scope invalidations?
          suspender.recomposeScope.invalidate()
          suspender.bodyRecomposeScope.invalidate()
          suspender.reachedResumePoint = false
          suspender.clock.isRunning = true
        }
    }
  }

  companion object {
    @PublishedApi
    @Composable
    internal fun <R> Recomposer.startSuspendingComposition(
      composition: ControlledComposition,
      clock: GatedFrameClock,
      emitter: suspend (Result<R>) -> Unit,
      body: @Composable () -> R
    ) {
      val suspender = Suspender(clock, composition, this)
      CompositionLocalProvider(suspenderCompositionLocal provides suspender) {
        suspender.bodyRecomposeScope = currentRecomposeScope
        val res = runCatchingComposable { body() }
        clock.isRunning = false
        CoroutineScope(effectCoroutineContext).launch {
          awaitIdle()
          val exception = res.exceptionOrNull()
          if (exception is Suspended) {
            return@launch
          }
          emitter(res)
        }
      }
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
  block: (Continuation<T>) -> Unit
): T = with(currentSuspender) { remember { EffectState<T>() }.suspendComposition(currentRecomposeScope, block) }

internal object UnitApplier : AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}