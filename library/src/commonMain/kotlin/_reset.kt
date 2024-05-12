import Suspender.Companion.suspender
import _Reset.Companion._lazyReset
import androidx.compose.runtime.*
import arrow.fx.coroutines.*
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@Suppress("EqualsOrHashCode")
@Stable
public class _Reset<R> internal constructor() : Continuation<R> {
  private var metaContinuation: Continuation<R>? = null

  @PublishedApi
  internal fun receiveResult(continuation: Continuation<R>) {
    val previousContinuation = metaContinuation
    metaContinuation = continuation.onResume { metaContinuation = previousContinuation }
  }

  override val context: CoroutineContext get() = metaContinuation?.context ?: error("Missing reset")
  override fun resumeWith(result: Result<R>): Unit = metaContinuation?.resumeWith(result) ?: error("Missing reset")

  override fun equals(other: Any?): Boolean = false

  public companion object {
    @ResetDsl
    public suspend fun <R> ResourceScope._lazyReset(
      tag: _Reset<R> = _Reset<R>(), body: @Composable _Reset<R>.() -> R
    ): R = with(suspender()) {
      suspendCoroutine { k ->
        tag.receiveResult(k)
        startSuspendingComposition(tag::resumeWith) { body(tag) }
      }
    }

    @Composable
    @ResetDsl
    @PublishedApi
    internal fun <R> _nestedReset(
      tag: _Reset<R> = _Reset<R>(), body: @Composable _Reset<R>.() -> R
    ): R = suspendComposition { k ->
      tag.receiveResult(k)
      startSuspendingComposition(tag::resumeWith) { body(tag) }
    }
  }
}

public suspend fun <R> _reset(tag: _Reset<R> = _Reset<R>(), body: @Composable _Reset<R>.() -> R): R =
  resourceScope { _lazyReset(tag, body) }