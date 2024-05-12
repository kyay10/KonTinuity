import Suspender.Companion.startSuspendingComposition
import androidx.compose.runtime.*
import arrow.fx.coroutines.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import _Reset.Companion._lazyReset

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@Suppress("EqualsOrHashCode")
@Stable
public class _Reset<R> internal constructor() {
  internal lateinit var continuation: Continuation<R>
  internal val continuationOrNull: Continuation<R>? get() = if (::continuation.isInitialized) continuation else null

  @PublishedApi
  internal fun receiveResult(cont: Continuation<R>) {
    val previousContinuation = this.continuation
    this.continuation = cont.onResume {
      this.continuation = previousContinuation
    }
  }

  @OptIn(InternalCoroutinesApi::class)
  @PublishedApi
  internal fun configure(
    suspender: Suspender, producer: @Composable () -> R
  ) {
    val composition = ControlledComposition(UnitApplier, suspender.compositionContext)
    composition.setContent {
      suspender.startSuspendingComposition({ continuation.resumeWith(it) }, producer)
    }
  }

  override fun equals(other: Any?): Boolean {
    return false
  }

  public companion object {
    @ResetDsl
    public suspend fun <R> ResourceScope._lazyReset(
      body: @Composable _Reset<R>.() -> R
    ): R {
      val job = Job(coroutineContext[Job])
      onRelease { job.cancelAndJoin() }

      val clock = GatedFrameClock()
      val scope = CoroutineScope(coroutineContext + job + clock)
      val recomposer = scope.launchMolecule()
      val composition = ControlledComposition(UnitApplier, recomposer)
      return suspendCoroutine {
        val reset = _Reset<R>()
        reset.continuation = it
        composition.setContent {
          recomposer.startSuspendingComposition(clock, { reset.continuation.resumeWith(it) }) {
            body(reset)
          }
        }
      }
    }

    @Composable
    @ResetDsl
    @PublishedApi
    internal fun <T> _nestedReset(
      tag: _Reset<T>? = null, body: @Composable _Reset<T>.() -> T
    ): T {
      val suspender = currentSuspender
      return suspendComposition { k ->
        val previousContinuation = tag?.continuationOrNull
        val composition = ControlledComposition(UnitApplier, suspender.compositionContext)
        val reset = tag ?: _Reset<T>()
        reset.continuation = k.onResume {
          if (tag != null && previousContinuation != null) tag.continuation = previousContinuation
        }
        composition.setContent {
          suspender.startSuspendingComposition({ reset.continuation.resumeWith(it) }) { body(reset) }
        }
      }
    }
  }
}

public suspend fun <T> _reset(body: @Composable _Reset<T>.() -> T): T =
  resourceScope { _lazyReset(body) }