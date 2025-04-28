package io.github.kyay10.kontinuity

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
) {
  @PublishedApi
  internal fun composedWith(stack: SplitSeq<R>): SplitSeq<T> = init prependTo stack

  @ResetDsl
  public suspend inline fun resumeWith(value: Result<T>): R = suspendCoroutineUnintercepted { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }
  @ResetDsl
  public suspend inline fun locally(noinline value: suspend () -> T): R = suspendCoroutineUnintercepted { stack ->
    value.startCoroutineIntercepted(composedWith(stack))
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}


@ResetDsl
public suspend inline fun <R> newReset(noinline body: suspend Prompt<R>.() -> R): R =
  suspendCoroutineUninterceptedOrReturn { k ->
    val prompt = Prompt<R>()
    body.startCoroutineHere(prompt, collectStack(k).pushPrompt(prompt))
  }

@PublishedApi
internal fun <R> (suspend () -> R).startCoroutineHere(stack: SplitSeq<R>): Any? =
  stack.frameCont().handleTrampolining(stack, runCatching { startCoroutineUninterceptedOrReturn(WrapperCont(stack)) })

@PublishedApi
internal fun <T, R> (suspend (T) -> R).startCoroutineHere(param: T, stack: SplitSeq<R>): Any? =
  stack.frameCont()
    .handleTrampolining(stack, runCatching { startCoroutineUninterceptedOrReturn(param, WrapperCont(stack)) })

private fun FrameCont<*>.handleTrampolining(
  stack: SplitSeq<*>,
  firstResult: Result<Any?>
): Any? {
  val result = handleTrampoliningImpl(stack, firstResult)
  //TODO justify that stack.frameCont() === this
  // also justify the validity of all of this using the Reification Invariant
  if (result != Result.success(COROUTINE_SUSPENDED)) reattachFrames()
  return result.getOrThrow()
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


public suspend inline fun <T, R> runReader(
  value: T,
  noinline fork: T.() -> T = { this },
  noinline body: suspend Reader<T>.() -> R
): R = suspendCoroutineUninterceptedOrReturn { k ->
  val reader = Reader<T>()
  body.startCoroutineHere(reader, collectStack(k).pushReader(reader, value, fork))
}

@ResetDsl
public suspend inline fun <R> nonReentrant(
  noinline body: suspend () -> R
): R = runCC(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(
  noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { stack ->
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContContext")
public suspend inline fun <T, R> shift(
  noinline body: suspend (SubCont<T, R>) -> R
): T = p.shift(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftOnce(
  noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { stack ->
  val (init, rest) = stack.splitAtOnce(this)
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContOnceContext")
public suspend inline fun <T, R> shiftOnce(
  noinline body: suspend (SubCont<T, R>) -> R
): T = p.shiftOnce(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftWithFinal(
  noinline body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = suspendCoroutineUnintercepted { stack ->
  val (init, rest) = stack.splitAtOnce(this)
  val reusableInit = when (init) {
    is SingleUseSegment -> init.makeReusable()
    else -> init
  }
  body.startCoroutineIntercepted(SubCont(reusableInit) to SubCont(init), rest)
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R> shiftWithFinal(
  noinline body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = p.shiftWithFinal(body)

// Acts like shift0/control { it(body()) }
@ResetDsl
public suspend inline fun <T, P> Prompt<P>.inHandlingContext(
  noinline body: suspend () -> T
): T = suspendCoroutineUninterceptedOrReturn { k ->
  val stack = collectStack(k)
  val (init, rest) = stack.splitAtOnce(this)
  body.startCoroutineHere(UnderCont(init, rest))
}

// Acts like shift0/control { it(body()) }
@ResetDsl
public suspend fun <T, P> Prompt<P>.inHandlingContextWithSubCont(
  body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineUninterceptedOrReturn { k ->
  val stack = collectStack(k)
  val (init, rest) = stack.splitAtOnce(this)
  val reusableInit = when (init) {
    is SingleUseSegment -> init.makeReusable()
    else -> init
  }
  body.startCoroutineHere(SubCont(reusableInit), UnderCont(init, rest))
}

context(p: Prompt<P>)
@ResetDsl
@JvmName("inHandlingContextContext")
public suspend inline fun <T, P> inHandlingContext(
  noinline body: suspend () -> T
): T = p.inHandlingContext(body)

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing =
  throw SeekingStackException { stack ->
    stack.find(this).resumeWithIntercepted(value)
  }

public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn { k ->
    findNearestSplitSeq(k).find(this).resumeWithIntercepted(value)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing =
  throw SeekingStackException { stack ->
    value.startCoroutineIntercepted(stack.find(this))
  }

public class Prompt<R> @PublishedApi internal constructor() {
  @PublishedApi
  internal var cont: PromptCont<R>? = null
}

public class Reader<S> {
  @PublishedApi
  internal var cont: ReaderCont<S, *>? = null
}

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