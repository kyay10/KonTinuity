import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.jvm.JvmInline

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@JvmInline
public value class StateSubCont<in T, out R, S : Stateful<S>> internal constructor(
  private val subchain: StateSubchain<T, R, S>,
) {
  private val prompt get() = subchain.hole.prompt
  public val state: S get() = subchain.hole.state

  private fun composedWith(
    k: Continuation<R>
  ) = subchain.replace(Hole(k, null, UnitState))

  private fun composedWith(
    k: Continuation<R>, state: S
  ) = subchain.replace(Hole(k, prompt, state))

  @ResetDsl
  public suspend fun pushSubContWith(
    value: Result<T>,
    isFinal: Boolean = false,
  ): R = suspendCoroutineUnintercepted { k ->
    composedWith(k).also { if (isFinal) subchain.clear() }.resumeWith(value)
  }

  @ResetDsl
  public suspend fun pushDelimSubContWith(
    value: Result<T>,
    state: S,
    isFinal: Boolean = false,
  ): R = suspendCoroutineUnintercepted { k ->
    composedWith(k, state).also { if (isFinal) subchain.clear() }.resumeWith(value)
  }

  @ResetDsl
  public suspend fun pushSubCont(
    isFinal: Boolean = false, value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    value.startCoroutine(composedWith(k).also { if (isFinal) subchain.clear() })
  }

  @ResetDsl
  public suspend fun pushDelimSubCont(
    state: S, isFinal: Boolean = false, value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    value.startCoroutine(composedWith(k, state).also { if (isFinal) subchain.clear() })
  }
}

public typealias SubCont<T, R> = StateSubCont<T, R, UnitState>

public suspend fun <T, R> SubCont<T, R>.pushDelimSubContWith(value: Result<T>, isFinal: Boolean = false): R =
  pushDelimSubContWith(value, UnitState, isFinal)

public suspend fun <T, R> SubCont<T, R>.pushDelimSubCont(isFinal: Boolean = false, value: suspend () -> T): R =
  pushDelimSubCont(UnitState, isFinal, value)

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(
  body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, this, UnitState))
}

@ResetDsl
public suspend fun <R, S : Stateful<S>> StatePrompt<R, S>.pushPrompt(
  state: S, body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, this, state))
}

public fun interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

@ResetDsl
public suspend fun <R> nonReentrant(
  body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutine(Hole(k, null, NonReentrant))
}

private data object NonReentrant : Stateful<NonReentrant> {
  override fun fork(): NonReentrant = throw IllegalStateException("Non-reentrant context")
}

internal data class Hole<T, S : Stateful<S>>(
  override val completion: Continuation<T>,
  val prompt: StatePrompt<T, S>?,
  val state: S,
) : CopyableContinuation<T>, CoroutineContext.Element {
  override val key get() = prompt ?: error("should never happen")
  override val context: CoroutineContext = if (prompt != null) {
    completion.context + this
  } else completion.context

  override fun resumeWith(result: Result<T>) {
    val exception = result.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(context)
    else completion.intercepted().resumeWith(result)
  }

  @Suppress("UNCHECKED_CAST")
  override fun copy(completion: Continuation<*>): Hole<T, S> =
    copy(completion = completion as Continuation<T>, state = state.fork())
}

public fun CoroutineContext.promptParentContext(prompt: Prompt<*>): CoroutineContext? =
  this[prompt]?.completion?.context

public fun CoroutineContext.promptContext(prompt: Prompt<*>): CoroutineContext? = this[prompt]?.context

private fun <T, S : Stateful<S>> CoroutineContext.holeFor(prompt: StatePrompt<T, S>) =
  this[prompt] ?: error("Prompt $prompt not set")

@ResetDsl
public suspend fun <T, R, S : Stateful<S>> StatePrompt<R, S>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (StateSubCont<T, R, S>) -> R
): T = suspendCoroutineUnintercepted { k ->
  val subchain = k.collectSubchain(this)
  val hole = subchain.hole
  body.startCoroutine(StateSubCont(subchain), if (deleteDelimiter) hole.completion else hole)
}

// Acts like shift0/shift { it(body()) }
// This is NOT multishot
@ResetDsl
public suspend fun <T> Prompt<*>.inHandlingContext(
  includeBodyContext: Boolean = false, body: suspend () -> T
): T = suspendCoroutineUnintercepted { k ->
  val hole = k.context.holeFor(this)
  val context = if (includeBodyContext) hole.context else hole.completion.context
  // TODO make it a CopyableContinuation
  body.startCoroutine(Continuation(context) {
    val exception = it.exceptionOrNull()
    if (exception is SeekingCoroutineContextException) exception.use(context)
    else k.resumeWith(it)
  })
}

@Suppress("UNCHECKED_CAST")
internal fun <R> StatePrompt<R, *>.abortWith(deleteDelimiter: Boolean, value: Result<R>): Nothing =
  throw AbortWithValueException(this as Prompt<Any?>, value, deleteDelimiter)

private class AbortWithValueException(
  private val prompt: Prompt<Any?>, private val value: Result<Any?>, private val deleteDelimiter: Boolean
) : SeekingCoroutineContextException() {
  override fun use(context: CoroutineContext) {
    val hole = context.holeFor(prompt)
    (if (deleteDelimiter) hole.completion else hole).resumeWith(value)
  }
}

@Suppress("UNCHECKED_CAST")
internal fun <R> Prompt<R>.abortS(deleteDelimiter: Boolean = false, value: suspend () -> R): Nothing =
  throw AbortWithProducerException(this as Prompt<Any?>, value, deleteDelimiter)

private class AbortWithProducerException(
  private val prompt: Prompt<Any?>, private val value: suspend () -> Any?, private val deleteDelimiter: Boolean
) : SeekingCoroutineContextException() {
  override fun use(context: CoroutineContext) {
    val hole = context.holeFor(prompt)
    value.startCoroutine(if (deleteDelimiter) hole.completion else hole)
  }
}

@Suppress("UNCHECKED_CAST")
internal fun <R, S : Stateful<S>> StatePrompt<R, S>.abortS(
  deleteDelimiter: Boolean = false, value: suspend (S) -> R
): Nothing =
  throw AbortWithStateProducerException(this as StatePrompt<Any?, *>, value as suspend (Any?) -> Any?, deleteDelimiter)

private class AbortWithStateProducerException(
  private val prompt: StatePrompt<Any?, *>,
  private val value: suspend (Any?) -> Any?,
  private val deleteDelimiter: Boolean
) : SeekingCoroutineContextException() {
  override fun use(context: CoroutineContext) {
    val hole = context.holeFor(prompt)
    value.startCoroutine(hole.state, if (deleteDelimiter) hole.completion else hole)
  }
}

public suspend fun StatePrompt<*, *>.isSet(): Boolean = coroutineContext[this] != null

public suspend fun <S : Stateful<S>> StatePrompt<*, S>.getState(): S = coroutineContext.holeFor(this).state
public suspend fun <S : Stateful<S>> StatePrompt<*, S>.getStateOrNull(): S? = coroutineContext[this]?.state

public data object UnitState : Stateful<UnitState> {
  override fun fork(): UnitState = this
}

public typealias Prompt<R> = StatePrompt<R, UnitState>

public class StatePrompt<R, S : Stateful<S>> : CoroutineContext.Key<Hole<R, S>>

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