package io.github.kyay10.kontinuity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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
  private val init: SingleUseSegment<T, R>,
) {
  @PublishedApi
  internal fun composedWith(stack: SplitSeq<R>): SplitSeq<T> = init prependTo stack

  @ResetDsl
  public suspend inline fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  @ResetDsl
  public suspend inline fun locally(noinline value: suspend () -> T): R = suspendCoroutineToTrampoline { stack ->
    value.startCoroutineIntercepted(composedWith(stack))
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}


@ResetDsl
public suspend inline fun <R> newReset(noinline body: suspend Prompt<R>.() -> R): R =
  suspendCoroutineAndTrampoline { stack ->
    val prompt = Prompt(stack)
    body.startCoroutineUninterceptedOrReturn(prompt, prompt)
  }

@PublishedApi
internal tailrec fun FramesCont<*, *, *>.handleTrampolining(
  result: Result<Any?>,
): Any? = if (COROUTINE_SUSPENDED == result.getOrNull() || SuspendedException == result.exceptionOrNull()) {
  val trampoline = realContext.trampoline
  val step = trampoline.nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn())
} else result.getOrThrow()


public suspend inline fun <T, R> runReader(
  value: T,
  noinline fork: T.() -> T = { this },
  noinline body: suspend Reader<T>.() -> R
): R = suspendCoroutineAndTrampoline { stack ->
  val reader = ReaderT(stack, value, fork)
  body.startCoroutineUninterceptedOrReturn(reader, reader)
}

@ResetDsl
public suspend inline fun <R> nonReentrant(
  noinline body: suspend () -> R
): R = runCC(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(
  noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init.makeReusable()), rest)
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
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(this)
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
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init.makeReusable()) to SubCont(init), rest)
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R> shiftWithFinal(
  noinline body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = p.shiftWithFinal(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftRepushing(
  noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(this)
  init.makeReusable()
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("shiftRepushingContext")
public suspend inline fun <T, R> shiftRepushing(
  noinline body: suspend (SubCont<T, R>) -> R
): T = p.shiftRepushing(body)

// Acts like shift0/control { it(body()) }
@ResetDsl
public suspend fun <T, P> Prompt<P>.inHandlingContext(
  body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineAndTrampoline { stack ->
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineUninterceptedOrReturn(SubCont(init.makeReusable()), UnderCont(init, rest))
}

@ResetDsl
public suspend fun <T, P> Prompt<P>.inHandlingContextTwice(
  body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineAndTrampoline { stack ->
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineUninterceptedOrReturn(SubCont(init.makeCopy()), UnderCont(init, rest))
}

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
  rest.resumeWithIntercepted(value)
  throw SuspendedException
}

public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    rest.resumeWithIntercepted(value)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing {
  value.startCoroutineIntercepted(rest)
  throw SuspendedException
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException
internal data object SuspendedException : NoTrace()

public suspend fun <R> runCC(body: suspend () -> R): R = withContext(coroutineContext.makeTrampoline()) {
  suspendCoroutine {
    body.startCoroutine(EmptyCont(it))
  }
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineToTrampoline(
  crossinline block: (SplitSeq<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(collectStack(it))
  COROUTINE_SUSPENDED
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineAndTrampoline(
  crossinline block: (SplitSeq<T>) -> Any?
): T = suspendCoroutineUninterceptedOrReturn {
  val stack = collectStack(it)
  stack.handleTrampolining(runCatching { block(stack) })
}