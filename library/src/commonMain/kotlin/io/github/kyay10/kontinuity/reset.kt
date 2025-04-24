package io.github.kyay10.kontinuity

import arrow.core.identity
import arrow.core.nonFatalOrThrow
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.jvm.JvmName

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public class SubCont<in T, out R> @PublishedApi internal constructor(
  private val init: Segment<T, R>,
  private val prompt: Prompt<R>
) {
  @PublishedApi
  internal fun composedWith(
    stack: SplitSeq<R>, isDelimiting: Boolean
  ): SplitSeq<T> = init prependTo stack.letIf(isDelimiting) { it.pushPrompt(prompt) }

  @ResetDsl
  public suspend inline fun pushSubContWith(
    value: Result<T>,
    isDelimiting: Boolean = false,
  ): R = suspendCoroutineUnintercepted { stack ->
    composedWith(stack, isDelimiting).resumeWithIntercepted(value)
  }

  @ResetDsl
  public suspend inline fun pushSubCont(
    isDelimiting: Boolean = false,
    noinline value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { stack ->
    value.startCoroutineIntercepted(composedWith(stack, isDelimiting))
  }
}

@ResetDsl
public suspend inline fun <R> Prompt<R>.pushPrompt(
  noinline body: suspend () -> R
): R = suspendCoroutineUninterceptedOrReturn { k ->
  body.startCoroutineHere(collectStack(k).pushPrompt(this))
}

@PublishedApi
internal fun <R> (suspend () -> R).startCoroutineHere(stack: SplitSeq<R>): Any? =
  handleTrampolining(stack, runCatching { startCoroutineUninterceptedOrReturn(WrapperCont(stack)) })

private fun handleTrampolining(
  stack: SplitSeq<*>,
  firstResult: Result<Any?>
): Any? = with(stack.frameCont()) {
  val result = handleTrampoliningImpl(stack, firstResult)
  //TODO justify that stack.frameCont() === this
  // also justify the validity of all of this using the Reification Invariant
  if (result != Result.success(COROUTINE_SUSPENDED)) reattachFrames()
  result.getOrThrow()
}

/**
 * Requires that this === stack.frameCont()
 */
private tailrec fun FrameCont<*>.handleTrampoliningImpl(
  stack: SplitSeq<*>,
  result: Result<Any?>,
): Result<Any?> = if (result == Result.success(COROUTINE_SUSPENDED)) {
  val trampoline = context.trampoline
  val step = trampoline.nextStep?.takeIf { it.seq.frameCont() === this } ?: return Result.success(COROUTINE_SUSPENDED)
  trampoline.nextStep = null
  handleTrampoliningImpl(step.seq, step.stepOrReturn())
} else result.onFailure {
  if (it is SeekingStackException) {
    it.use(stack)
    return handleTrampoliningImpl(stack, Result.success(COROUTINE_SUSPENDED))
  }
}


context(p: Prompt<R>)
@ResetDsl
@JvmName("pushPromptContext")
public suspend inline fun <R> pushPrompt(
  noinline body: suspend () -> R
): R = p.pushPrompt(body)

public suspend inline fun <T, R> Reader<T>.pushReader(
  value: T,
  noinline fork: T.() -> T = ::identity,
  noinline body: suspend () -> R
): R =
  suspendCoroutineUninterceptedOrReturn { k ->
    body.startCoroutineHere(collectStack(k).pushReader(this, value, fork))
  }

context(r: Reader<T>)
@JvmName("pushReaderContext")
public suspend inline fun <T, R> pushReader(
  value: T,
  noinline fork: T.() -> T = ::identity,
  noinline body: suspend () -> R
): R =
  r.pushReader(value, fork, body)

@ResetDsl
public suspend inline fun <R> nonReentrant(
  noinline body: suspend () -> R
): R = runCC(body)

public suspend inline fun <S> Reader<S>.deleteBinding(): Unit = suspendCoroutineUninterceptedOrReturn { k ->
  val stack = collectStack(k)
  stack.deleteReader(this, null).also { stack.frameCont().reattachFrames() }
}

context(r: Reader<S>)
@JvmName("deleteBindingContext")
public suspend inline fun <S> deleteBinding(): Unit = r.deleteBinding()

@PublishedApi
internal fun <T> SplitSeq<*>.holeFor(prompt: Prompt<T>, deleteDelimiter: Boolean): SplitSeq<T> {
  val splitSeq = find(prompt)
  return splitSeq.letUnless(deleteDelimiter) { it.pushPrompt(prompt) }
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { stack ->
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init, this), rest.letUnless(deleteDelimiter) { it.pushPrompt(this) })
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContContext")
public suspend inline fun <T, R> takeSubCont(
  deleteDelimiter: Boolean = true, noinline body: suspend (SubCont<T, R>) -> R
): T = p.takeSubCont(deleteDelimiter, body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.takeSubContOnce(
  deleteDelimiter: Boolean = true, noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { stack ->
  val (init, rest) = stack.splitAtOnce(this)
  body.startCoroutineIntercepted(SubCont(init, this), rest.letUnless(deleteDelimiter) { it.pushPrompt(this) })
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContOnceContext")
public suspend inline fun <T, R> takeSubContOnce(
  deleteDelimiter: Boolean = true, noinline body: suspend (SubCont<T, R>) -> R
): T = p.takeSubContOnce(deleteDelimiter, body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.takeSubContWithFinal(
  deleteDelimiter: Boolean = true, noinline body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = suspendCoroutineUnintercepted { stack ->
  val (reusableInit, _) = stack.splitAt(this)
  val (init, rest) = stack.splitAtOnce(this)
  body.startCoroutineIntercepted(
    SubCont(reusableInit, this) to SubCont(init, this),
    rest.letUnless(deleteDelimiter) { it.pushPrompt(this) }
  )
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R> takeSubContWithFinal(
  deleteDelimiter: Boolean = true, noinline body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = p.takeSubContWithFinal(deleteDelimiter, body)

// Acts like shift0/control { it(body()) }
// TODO if `body` is multishot, we need to somehow
//  evolve k to be multishot too. This is a more general issue with
//  the `once` functionality. Maybe look to Scheme for ideas?
@ResetDsl
public suspend inline fun <T, P> Prompt<P>.inHandlingContext(
  deleteDelimiter: Boolean = true, noinline body: suspend () -> T
): T = takeSubContOnce(deleteDelimiter) { k ->
  val res = runCatching { body() }
  // TODO test abortWith here
  res.exceptionOrNull()?.nonFatalOrThrow()
  k.pushSubContWith(res, isDelimiting = deleteDelimiter)
}

context(p: Prompt<P>)
@ResetDsl
@JvmName("inHandlingContextContext")
public suspend inline fun <T, P> inHandlingContext(
  deleteDelimiter: Boolean = true, noinline body: suspend () -> T
): T = p.inHandlingContext(deleteDelimiter, body)

public fun <R> Prompt<R>.abortWith(deleteDelimiter: Boolean, value: Result<R>): Nothing =
  throw SeekingStackException { stack ->
    stack.holeFor(this, deleteDelimiter).resumeWithIntercepted(value)
  }

public suspend inline fun <R> Prompt<R>.abortWithFast(deleteDelimiter: Boolean, value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn { k ->
    findNearestSplitSeq(k).holeFor(this, deleteDelimiter).resumeWithIntercepted(value)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(deleteDelimiter: Boolean = false, value: suspend () -> R): Nothing =
  throw SeekingStackException { stack ->
    value.startCoroutineIntercepted(stack.holeFor(this, deleteDelimiter))
  }

public class Prompt<R>
public class Reader<S>

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException
internal class SeekingStackException(val use: (SplitSeq<*>) -> Unit) : NoTrace()

public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine {
  body.startCoroutine(WrapperCont(EmptyCont(Continuation(it.context.withTrampoline(), it::resumeWith))))
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineUnintercepted(
  crossinline block: (SplitSeq<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(collectStack(it))
  COROUTINE_SUSPENDED
}