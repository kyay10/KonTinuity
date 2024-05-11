import Reset.Companion.lazyReset
import Suspender.Companion.startSuspendingComposition
import androidx.compose.runtime.*
import arrow.fx.coroutines.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@Suppress("EqualsOrHashCode")
@Stable
public class Reset<R> internal constructor(
  output: Continuation<R>,
) {
  private var normalContinuation: Continuation<R> = output
  private var controlContinuation: Continuation<R> = output

  internal fun receiveResult(isShift: Boolean, continuation: Continuation<R>) {
    if (isShift) {
      val previousControlContinuation = controlContinuation
      val continuation = continuation.onResume {
        controlContinuation = previousControlContinuation
      }
      normalContinuation = continuation
      // Replaces the control continuation with this handler's continuation
      // This ensures that the next continuation reifier will resume this handler instead of any previous one
      // or the original output continuation. This is equivalent to wrapping the continuation in a reset
      controlContinuation = continuation
    } else {
      normalContinuation = continuation
    }
  }

  @OptIn(InternalCoroutinesApi::class)
  @PublishedApi
  internal fun <T> Continuation<T>.configure(producer: suspend (Continuation<T>) -> R) {
    CoroutineStart.UNDISPATCHED(producer, this, Continuation(controlContinuation.context) {
      controlContinuation.resumeWith(it)
    })
  }

  @OptIn(InternalCoroutinesApi::class)
  @PublishedApi
  internal fun <T> Continuation<T>.configureC(
    suspender: Suspender, producer: @Composable (Continuation<T>) -> R
  ) {
    val composition = ControlledComposition(UnitApplier, suspender.compositionContext)
    composition.setContent {
      with(suspender) {
        startSuspendingComposition(composition, { controlContinuation.resumeWith(it) }) {
          producer(
            this@configureC
          )
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
        val reset = Reset(it)
        composition.setContent {
          recomposer.startSuspendingComposition(composition, clock, { reset.normalContinuation.resumeWith(it) }) {
            body(reset)
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
      val reset = Reset(k)
      composition.setContent {
        suspender.startSuspendingComposition(composition, { reset.normalContinuation.resumeWith(it) }) { body(reset) }
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