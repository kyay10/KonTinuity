import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<T, R> internal constructor(
  private val ekFragment: Continuation<T>,
  private val upTo: Hole<R>,
) {
  @ResetDsl
  public suspend fun pushSubContWith(
    value: Result<T>, delimiter: Prompt<R>? = null, extraContext: CoroutineContext = EmptyCoroutineContext
  ): R = suspendCoroutineUnintercepted { k ->
    ekFragment.clone(upTo, Hole(k, delimiter, extraContext)).resumeWith(value)
  }

  @ResetDsl
  public suspend fun pushSubCont(
    delimiter: Prompt<R>? = null, extraContext: CoroutineContext = EmptyCoroutineContext, value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    value.startCoroutine(ekFragment.clone(upTo, Hole<R>(k, delimiter, extraContext)))
  }
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(
  extraContext: CoroutineContext = EmptyCoroutineContext, body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, this, extraContext))
}

@ResetDsl
public suspend fun <R> pushContext(context: CoroutineContext, body: suspend () -> R): R =
  suspendCoroutineUnintercepted { k ->
    body.startCoroutine(Hole(k, null, context))
  }

internal class Hole<R>(
  private val ultimateCont: Continuation<R>,
  private val prompt: Prompt<R>?,
  private val extraContext: CoroutineContext = EmptyCoroutineContext
) : CloneableContinuation<R>, CoroutineContext.Element {
  override val key: CoroutineContext.Key<*> get() = prompt ?: error("should never happen")
  override val context: CoroutineContext =
    (if (prompt != null) ultimateCont.context + this else ultimateCont.context) + extraContext

  override fun resumeWith(result: Result<R>) {
    val exception = result.exceptionOrNull()
    if (exception is UnwindCancellationException) {
      context.startHandler(exception.function, exception.ekFragment, exception.deleteDelimiter, exception.prompt)
    } else ultimateCont.intercepted().resumeWith(result)
  }

  override fun clone(upTo: Hole<*>, replacement: Hole<*>): CloneableContinuation<R> =
    Hole(ultimateCont.clone(upTo, replacement), prompt, extraContext)

  internal fun withoutDelimiter(): Hole<R> = Hole(ultimateCont, null, extraContext)
}

private fun <T, R> CoroutineContext.startHandler(
  handler: suspend (SubCont<T, R>?) -> R, ekFragment: Continuation<T>?, deleteDelimiter: Boolean, prompt: Prompt<R>
) {
  val hole = this[prompt] ?: error("Prompt $prompt not set")
  val subCont = ekFragment?.let { SubCont(ekFragment, hole) }
  handler.startCoroutine(subCont, if (deleteDelimiter) hole.withoutDelimiter() else hole)
}

@Suppress("UNCHECKED_CAST")
@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { k ->
  k.context.startHandler(body as suspend (SubCont<T, R>?) -> R, k, deleteDelimiter, this)
}

// TODO: optimize
@ResetDsl
internal fun <R> Prompt<R>.abortWith(
  deleteDelimiter: Boolean, value: Result<R>
): Nothing = abortS(deleteDelimiter) { value.getOrThrow() }

@Suppress("UNCHECKED_CAST")
internal fun <R> Prompt<R>.abortS(
  deleteDelimiter: Boolean = false, value: suspend () -> R
): Nothing {
  throw NoTrace(this as Prompt<Any?>, { value() }, null, deleteDelimiter)
}

public suspend fun Prompt<*>.isSet(): Boolean = coroutineContext[this] != null

public class Prompt<R> : CoroutineContext.Key<Hole<R>>

internal sealed class UnwindCancellationException(
  val prompt: Prompt<Any?>,
  val function: suspend (SubCont<Any?, Any?>?) -> Any?,
  val ekFragment: Continuation<Any?>?,
  val deleteDelimiter: Boolean
) : CancellationException("Should never get swallowed")

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class NoTrace(
  prompt: Prompt<Any?>,
  function: suspend (SubCont<Any?, Any?>?) -> Any?,
  ekFragment: Continuation<Any?>?,
  deleteDelimiter: Boolean
) : UnwindCancellationException

public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine {
  body.startCoroutine(it)
}

private suspend inline fun <T> suspendCoroutineUnintercepted(
  crossinline block: (Continuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(it)
  COROUTINE_SUSPENDED
}