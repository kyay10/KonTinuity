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
import androidx.compose.runtime.withFrameNanos
import arrow.AutoCloseScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine

private val withFrameNanosUnit: suspend ((Long) -> Unit).() -> Unit = ::withFrameNanos

private class FireAndForgetContinuation(override val context: CoroutineContext) : Continuation<Unit> {
  override fun resumeWith(result: Result<Unit>) {}
}

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
internal class Suspender(private val recomposer: Recomposer) {
  private lateinit var recomposeScope: RecomposeScope
  internal lateinit var bodyRecomposeScope: RecomposeScope
  private var resumeToken: Any? = null

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  @PublishedApi
  internal fun <T> EffectState<T>.suspendComposition(
    recomposeScope: RecomposeScope, block: Recomposer.(Continuation<T>) -> Unit
  ): T {
    this@Suspender.recomposeScope = recomposeScope
    if (reachedResumePoint) {
      value = inFastPathResult
      val continuation = CompositionContinuation(this)
      recomposer.block(continuation)
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

  private inner class CompositionContinuation<T>(private val state: EffectState<T>) : Continuation<T> {
    override val context: CoroutineContext get() = recomposer.effectCoroutineContext

    override fun resumeWith(result: Result<T>) {
      val inFastPath = state.value == inFastPathResult
      state.value = result
      resumeToken = state
      if (!inFastPath) withFrameNanosUnit.startCoroutine({
        // TODO do we need both scope invalidations?
        recomposeScope.invalidate()
        bodyRecomposeScope.invalidate()
        reachedResumePoint = false
      }, FireAndForgetContinuation(context))
    }
  }
}

@OptIn(ExperimentalStdlibApi::class)
private fun AutoCloseScope.installJob(context: CoroutineContext) =
  Job(context[Job]).also { install(AutoCloseable { it.cancel() }) }


@OptIn(ExperimentalStdlibApi::class)
@PublishedApi
internal fun AutoCloseScope.recomposer(context: CoroutineContext): Recomposer =
  with(CoroutineScope(context + installJob(context) + GatedFrameClock())) {
    Recomposer(coroutineContext).also {
      launch(start = CoroutineStart.UNDISPATCHED) { it.runRecomposeAndApplyChanges() }
    }
  }

@PublishedApi
internal fun <R> Recomposer.startSuspendingComposition(
  emitter: (Result<R>) -> Unit, body: @Composable () -> R
) = Composition(UnitApplier, this).setContent {
  val suspender = Suspender(this)
  CompositionLocalProvider(suspenderCompositionLocal provides suspender) {
    suspender.bodyRecomposeScope = currentRecomposeScope
    val res = runCatchingComposable { body() }
    val exception = res.exceptionOrNull()
    if (exception !is Suspended) {
      emitter(res)
    } else {
      check(exception.token == suspender)
    }
  }
}

@OptIn(InternalCoroutinesApi::class)
@Composable
@ResetDsl
public fun <T> await(block: suspend () -> T): T = suspendComposition { cont ->
  block.startCoroutineUnintercepted(cont)
}

private fun <T> (suspend () -> T).startCoroutineUnintercepted(completion: Continuation<T>) {
  try {
    val res = startCoroutineUninterceptedOrReturn(completion)
    if (res != COROUTINE_SUSPENDED) completion.resume(res as T)
  } catch (e: Throwable) {
    completion.resumeWithException(e)
  }
}

@Composable
@PublishedApi
internal inline fun <T> suspendComposition(
  crossinline block: Recomposer.(Continuation<T>) -> Unit
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

private class Suspended(val token: Suspender) : Exception("Composable was suspended up to $token")