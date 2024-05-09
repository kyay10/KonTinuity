import Reset.Companion.lazyReset
import Suspender.Companion.startSuspendingComposition
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
  private var parent: Pair<Reset<*>, Continuation<R>>?,
) {
  private var normalContinuation: Continuation<R> = output
  private var normalContinuationAbortToken: Any? = null
  private var controlContinuation: Continuation<R> = output

  @PublishedApi
  internal fun emitter(value: Result<R>) {
    normalContinuation.resumeWith(value)
  }

  @Composable
  @PublishedApi
  internal fun runReset(body: @Composable Reset<R>.() -> Unit): Unit =
    CompositionLocalProvider(resetCompositionLocal provides this) {
      body()
    }

  internal fun <R2> awaitResult(
    target: Reset<R2>, isShift: Boolean, token: Any, cont: Continuation<R2>, overrideParent: Reset<*>?
  ) {
    if (target === this@Reset) {
      if (overrideParent == null || overrideParent === this@Reset) {
        receiveResult(isShift, token, cont)
      } else {
        val oldParent = parent
        parent = overrideParent to cont
        receiveResult(isShift, token, Continuation(EmptyCoroutineContext) {
          parent = oldParent
          cont.resumeWith(it)
        })
      }
    } else {
      val (parentReset, parentCont) = requireNotNull(parent) {
        "No parent reset found"
      }
      receiveResult(isShift, token, Continuation(EmptyCoroutineContext) {
        parentCont.resumeWith(it)
        parentReset.awaitResult(target, isShift, token, cont, overrideParent)
      })
    }
  }

  private fun receiveResult(isShift: Boolean, token: Any, continuation: Continuation<R>) {
    val previousControlContinuation = controlContinuation
    val continuation = Continuation(continuation.context) {
      controlContinuation = previousControlContinuation
      continuation.resumeWith(it)
    }
    normalContinuation = continuation
    if (isShift) {
      // Replaces the control continuation with this handler's continuation
      // This ensures that the next continuation reifier will resume this handler instead of any previous one
      // or the original output continuation. This is equivalent to wrapping the continuation in a reset
      controlContinuation = continuation
    } else {
      normalContinuationAbortToken = token
    }
  }

  @OptIn(InternalCoroutinesApi::class)
  @PublishedApi
  internal fun <T> Continuation<T>.configure(producer: suspend (Continuation<T>) -> R) {
    // Trying not to rely on Continuation equality, but it could've been used here
    normalContinuationAbortToken?.let { normalContinuation.resumeWithException(Suspended(it)) }
    normalContinuationAbortToken = null
    CoroutineStart.UNDISPATCHED(producer, this, controlContinuation)
  }

  @OptIn(InternalCoroutinesApi::class)
  @PublishedApi
  internal fun <T> Continuation<T>.configureC(
    suspender: Suspender, producer: @Composable (Continuation<T>) -> R
  ) {
    val composition = ControlledComposition(UnitApplier, suspender.compositionContext)
    // Trying not to rely on Continuation equality, but it could've been used here
    normalContinuationAbortToken?.let { normalContinuation.resumeWithException(Suspended(it)) }
    normalContinuationAbortToken = null
    composition.setContent {
      runReset {
        with(suspender) {
          startSuspendingComposition(composition, controlContinuation::resumeWith) { producer(this@configureC) }
        }
      }
    }
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
      val recomposer = scope.launchMolecule()
      val composition = ControlledComposition(UnitApplier, recomposer)
      return suspendCoroutine {
        val reset = Reset(it, null)
        composition.setContent {
          reset.runReset {
            recomposer.startSuspendingComposition(composition, clock, reset::emitter) { body() }
          }
        }
      }
    }


    // the receiver is unused. It's there to force it to be called when nested
    @Composable
    @ResetDsl
    public fun <T> Reset<*>.reset(
      body: @Composable Reset<T>.() -> T
    ): T = suspendComposition { k ->
      val suspender = currentSuspender
      val composition = ControlledComposition(UnitApplier, suspender.compositionContext)
      val reset = Reset(k, currentReset to k)
      composition.setContent {
        reset.runReset { suspender.startSuspendingComposition(composition, reset::emitter) { body() } }
      }
    }
  }
}

@ResetDsl
public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(body) }

@PublishedApi
internal class Suspended(val token: Any) : Exception("Composable was suspended up to $token")