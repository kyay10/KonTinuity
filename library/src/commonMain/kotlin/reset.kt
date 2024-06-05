import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<in T, out R> internal constructor(
  private val ekFragment: Continuation<T>,
  private val prompt: Prompt<R>,
) {
  private fun composedWith(
    k: Continuation<R>, isDelimiting: Boolean, extraContext: CoroutineContext, rewindHandler: RewindHandler?
  ) = ekFragment.clone(prompt, Hole(k, prompt.takeIf { isDelimiting }, extraContext, rewindHandler))

  @ResetDsl
  public suspend fun pushSubContWith(
    value: Result<T>,
    isDelimiting: Boolean = false,
    extraContext: CoroutineContext = EmptyCoroutineContext,
    rewindHandler: RewindHandler? = null
  ): R = suspendCoroutineUnintercepted { k ->
    composedWith(k, isDelimiting, extraContext, rewindHandler).resumeWith(value)
  }

  @ResetDsl
  public suspend fun pushSubCont(
    isDelimiting: Boolean = false,
    extraContext: CoroutineContext = EmptyCoroutineContext,
    rewindHandler: RewindHandler? = null,
    value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    value.startCoroutine(composedWith(k, isDelimiting, extraContext, rewindHandler))
  }
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(
  extraContext: CoroutineContext = EmptyCoroutineContext, rewindHandler: RewindHandler? = null, body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, this, extraContext, rewindHandler))
}

@ResetDsl
public suspend fun <R> pushContext(
  context: CoroutineContext, rewindHandler: RewindHandler? = null, body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, null, context, rewindHandler))
}

public fun interface RewindHandler {
  public fun onRewind(oldProducedContext: CoroutineContext, newParentContext: CoroutineContext): CoroutineContext
}

@ResetDsl
public suspend fun <R> withRewindHandler(
  rewindHandler: RewindHandler, extraContext: CoroutineContext = EmptyCoroutineContext, body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, null, extraContext, rewindHandler))
}

@ResetDsl
public suspend fun <R> nonReentrant(
  body: suspend () -> R
): R = withRewindHandler(NonReentrant, body = body)

private object NonReentrant : RewindHandler {
  override fun onRewind(oldProducedContext: CoroutineContext, newParentContext: CoroutineContext): CoroutineContext =
    throw IllegalStateException("Non-reentrant context")
}

internal data class Hole<T>(
  override val completion: Continuation<T>,
  val prompt: Prompt<T>?,
  val extraContext: CoroutineContext,
  private val rewindHandler: RewindHandler?,
) : CopyableContinuation<T>, CoroutineContext.Element {
  override val key: Prompt<T> get() = prompt ?: error("should never happen")
  override val context: CoroutineContext =
    (if (prompt != null) completion.context + this else completion.context) + extraContext

  override fun resumeWith(result: Result<T>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(context)
    else completion.intercepted().resumeWith(result)
  }

  @Suppress("UNCHECKED_CAST")
  override fun copy(completion: Continuation<*>): Hole<T> {
    val extraContext = rewindHandler?.onRewind(extraContext, completion.context) ?: extraContext
    return copy(completion = completion as Continuation<T>, extraContext = extraContext)
  }

  internal fun withoutDelimiter(): Hole<T> = copy(prompt = null)
}

public fun CoroutineContext.promptParentContext(prompt: Prompt<*>): CoroutineContext? =
  this[prompt]?.completion?.context

public fun CoroutineContext.promptContext(prompt: Prompt<*>): CoroutineContext? = this[prompt]?.context

private fun <T> CoroutineContext.holeFor(prompt: Prompt<T>, deleteDelimiter: Boolean): Continuation<T> {
  val hole = this[prompt] ?: error("Prompt $prompt not set")
  return if (deleteDelimiter) hole.withoutDelimiter() else hole
}

@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(SubCont(k, this), k.context.holeFor(this, deleteDelimiter))
}

@Suppress("UNCHECKED_CAST")
internal fun <R> Prompt<R>.abortWith(deleteDelimiter: Boolean, value: Result<R>): Nothing =
  throw AbortWithValueException(this as Prompt<Any?>, value, deleteDelimiter)

private class AbortWithValueException(
  private val prompt: Prompt<Any?>, private val value: Result<Any?>, private val deleteDelimiter: Boolean
) : SeekingCoroutineContextException() {
  override fun use(context: CoroutineContext) = context.holeFor(prompt, deleteDelimiter).resumeWith(value)
}

@Suppress("UNCHECKED_CAST")
internal fun <R> Prompt<R>.abortS(deleteDelimiter: Boolean = false, value: suspend () -> R): Nothing =
  throw AbortWithProducerException(this as Prompt<Any?>, value, deleteDelimiter)

private class AbortWithProducerException(
  private val prompt: Prompt<Any?>, private val value: suspend () -> Any?, private val deleteDelimiter: Boolean
) : SeekingCoroutineContextException() {
  override fun use(context: CoroutineContext) = value.startCoroutine(context.holeFor(prompt, deleteDelimiter))
}

public suspend fun Prompt<*>.isSet(): Boolean = coroutineContext[this] != null

public class Prompt<R> : CoroutineContext.Key<Hole<R>>

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect abstract class SeekingCoroutineContextException() : CancellationException {
  abstract fun use(context: CoroutineContext)
}

public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine {
  body.startCoroutine(it)
}

private suspend inline fun <T> suspendCoroutineUnintercepted(
  crossinline block: (Continuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(it)
  COROUTINE_SUSPENDED
}