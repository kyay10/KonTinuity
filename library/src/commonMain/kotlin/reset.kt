import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<T, R> internal constructor(
  private val ekFragment: Continuation<T>,
  private val prompt: Prompt<R>,
  private val upTo: Hole<*>,
) {
  @ResetDsl
  public suspend fun pushSubContWith(
    isDelimiting: Boolean = false, extraContext: CoroutineContext = EmptyCoroutineContext, value: Result<T>
  ): R = suspendCoroutineUnintercepted { k ->
    val replacement = if (isDelimiting) Hole(k, prompt, extraContext) else Hole(k, null, extraContext)
    ekFragment.clone(upTo, replacement).resumeWith(value)
  }

  @ResetDsl
  public suspend fun pushSubCont(
    isDelimiting: Boolean = false, extraContext: CoroutineContext = EmptyCoroutineContext, value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    val replacement = if (isDelimiting) Hole(k, prompt, extraContext) else Hole(k, null, extraContext)
    value.startCoroutine(ekFragment.clone(upTo, replacement))
  }

  @ResetDsl
  public suspend fun pushDelimSubContWith(extraContext: CoroutineContext = EmptyCoroutineContext, value: Result<T>): R =
    pushSubContWith(isDelimiting = true, extraContext, value)

  @ResetDsl
  public suspend fun pushDelimSubCont(
    extraContext: CoroutineContext = EmptyCoroutineContext, value: suspend () -> T
  ): R = pushSubCont(isDelimiting = true, extraContext, value)
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(
  extraContext: CoroutineContext = EmptyCoroutineContext, body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, this, extraContext))
}

internal class Hole<R>(
  val ultimateCont: Continuation<R>, val prompt: Prompt<R>?, val extraContext: CoroutineContext = EmptyCoroutineContext
) : CloneableContinuation<R>, CoroutineContext.Element {
  override val key: CoroutineContext.Key<*> get() = prompt ?: error("should never happen")
  override val context: CoroutineContext =
    if (prompt != null) ultimateCont.context + this + extraContext else ultimateCont.context + extraContext

  override fun resumeWith(result: Result<R>) {
    val exception = result.exceptionOrNull()
    if (exception is UnwindCancellationException) {
      context.startHandler(exception.function, exception.ekFragment, exception.deleteDelimiter, exception.prompt)
    } else ultimateCont.intercepted().resumeWith(result)
  }

  override fun clone(upTo: Hole<*>, replacement: Hole<*>): CloneableContinuation<R> =
    Hole(ultimateCont.clone(upTo, replacement), prompt, extraContext)
}

private fun <T, R> CoroutineContext.startHandler(
  handler: suspend (SubCont<T, R>?) -> R, ekFragment: Continuation<T>?, deleteDelimiter: Boolean, prompt: Prompt<R>
) {
  val hole = this[prompt] ?: error("Prompt $prompt not set")
  val subCont = ekFragment?.let { SubCont(ekFragment, prompt, hole) }
  handler.startCoroutine(subCont, if (deleteDelimiter) hole.ultimateCont.intercepted() else hole)
}

@Suppress("UNCHECKED_CAST")
@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { k ->
  k.context.startHandler(body as suspend (SubCont<T, R>?) -> R, k, deleteDelimiter, this)
}

@ResetDsl
@PublishedApi
internal fun <R> Prompt<R>.abort(
  deleteDelimiter: Boolean = false, value: Result<R>
): Nothing = abortS(deleteDelimiter) { value.getOrThrow() }

@Suppress("UNCHECKED_CAST")
@ResetDsl
@PublishedApi
internal fun <R> Prompt<R>.abortS(
  deleteDelimiter: Boolean = false, value: suspend () -> R
): Nothing {
  throw NoTrace(this as Prompt<Any?>, { value() }, null, deleteDelimiter)
}

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

@PublishedApi
internal suspend inline fun <T> suspendCoroutineUnintercepted(
  crossinline block: (Continuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(it)
  COROUTINE_SUSPENDED
}