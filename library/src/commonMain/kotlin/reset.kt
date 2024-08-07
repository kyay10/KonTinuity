import arrow.core.identity
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public class SubCont<in T, out R> internal constructor(
  private val subchain: Subchain<T, R>,
  private val prompt: Prompt<R>, // can be extracted from subchain.last().key but needs cast
) {
  private fun composedWith(
    k: Continuation<R>, isDelimiting: Boolean
  ) = subchain.replace(if (isDelimiting) PromptHole(k, prompt) else InterceptedHole(k))

  @ResetDsl
  public suspend fun pushSubContWith(
    value: Result<T>,
    isDelimiting: Boolean = false,
    isFinal: Boolean = false,
  ): R = suspendCoroutineUnintercepted { k ->
    composedWith(k, isDelimiting).also { if (isFinal) subchain.clear() }.resumeWith(value)
  }

  @ResetDsl
  public suspend fun pushSubCont(
    isDelimiting: Boolean = false,
    isFinal: Boolean = false,
    value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    value.startCoroutine(composedWith(k, isDelimiting).also { if (isFinal) subchain.clear() })
  }
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(
  body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(PromptHole(k, this))
}

public suspend fun <T, R> Reader<T>.pushReader(value: T, fork: T.() -> T = ::identity, body: suspend () -> R): R =
  suspendCoroutineUnintercepted { k ->
    body.startCoroutine(ReaderHole(k, this, value, fork))
  }


@ResetDsl
public suspend fun <R> nonReentrant(
  body: suspend () -> R
): R = Reader<Unit>().pushReader(Unit, { throw IllegalStateException("Non-reentrant context") }, body)

internal data class PromptHole<T>(
  override val completion: Continuation<T>,
  override val key: Prompt<T>,
) : CopyableContinuation<T>, CoroutineContext.Element {
  override val context: CoroutineContext = completion.context + this

  override fun resumeWith(result: Result<T>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(context)
    else completion.intercepted().resumeWith(result)
  }

  @Suppress("UNCHECKED_CAST")
  override fun copy(completion: Continuation<*>): PromptHole<T> = copy(completion = completion as Continuation<T>)
}

internal class InterceptedHole<T>(
  override val completion: Continuation<T>,
) : CopyableContinuation<T> {
  override val context: CoroutineContext = completion.context

  override fun resumeWith(result: Result<T>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(context)
    else completion.intercepted().resumeWith(result)
  }

  @Suppress("UNCHECKED_CAST")
  override fun copy(completion: Continuation<*>): InterceptedHole<T> = InterceptedHole(completion as Continuation<T>)
}

internal data class ReaderHole<T, S>(
  override val completion: Continuation<T>,
  override val key: Reader<S>,
  val state: S,
  val fork: S.() -> S,
) : CopyableContinuation<T>, CoroutineContext.Element {
  override val context: CoroutineContext = completion.context + this

  override fun resumeWith(result: Result<T>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(context)
    else completion.resumeWith(result)
  }

  @Suppress("UNCHECKED_CAST")
  override fun copy(completion: Continuation<*>): ReaderHole<T, S> =
    copy(completion = completion as Continuation<T>, state = state.fork())
}

public suspend fun <S> Reader<S>.deleteBinding(): S = suspendCoroutineUnintercepted { k ->
  val hole = k.context[this] ?: error("Reader $this not set")
  hole.deleteBinding(k)
}

private fun <T, S> ReaderHole<T, S>.deleteBinding(k: Continuation<S>) =
  k.collectSubchain(this).replace(InterceptedHole(completion)).resumeWith(Result.success(state))

private fun <T> CoroutineContext.holeFor(prompt: Prompt<T>, deleteDelimiter: Boolean): Continuation<T> {
  val hole = this[prompt] ?: error("Prompt $prompt not set")
  return if (deleteDelimiter) hole.completion else hole
}

@Suppress("UNCHECKED_CAST")
@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { k ->
  val hole = k.context[this] ?: error("Prompt $this not set")
  val subchain = k.collectSubchain(hole)
  body.startCoroutine(SubCont(subchain, hole.key), if (deleteDelimiter) hole.completion else hole)
}

// Acts like shift0/shift { it(body()) }
// This is NOT multishot
@ResetDsl
public suspend fun <T> Prompt<*>.inHandlingContext(
  includeBodyContext: Boolean = false, body: suspend () -> T
): T = suspendCoroutineUnintercepted { k ->
  val hole = k.context.holeFor(this, !includeBodyContext)
  // TODO make it a CopyableContinuation
  body.startCoroutine(Continuation(hole.context) {
    val exception = it.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(hole.context)
    else k.resumeWith(it)
  })
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

public class Prompt<R> : CoroutineContext.Key<PromptHole<R>>
public class Reader<S> : CoroutineContext.Key<ReaderHole<*, S>>

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