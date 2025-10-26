package io.github.kyay10.kontinuity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine
import kotlin.jvm.JvmName

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public class SubCont<in T, out R> @PublishedApi internal constructor(
  private val init: Segment<T, R>,
  private val shouldCopy: Boolean = false
) {
  @PublishedApi
  internal fun composedWith(stack: FramesCont<R, *>): FramesCont<T, *> =
    init.copyIf(shouldCopy, stack.rest.realContext) prependTo stack

  @PublishedApi
  internal fun makeUnderCont(stack: FramesCont<R, *>): UnderCont<@UnsafeVariance T, @UnsafeVariance R> =
    UnderCont(init, stack.rest.realContext, shouldCopy).apply { rest = stack }

  @ResetDsl
  public suspend inline fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  @ResetDsl
  public suspend inline infix fun locally(noinline value: suspend () -> T): R = suspendCoroutineToTrampoline { stack ->
    value.startCoroutineIntercepted(composedWith(stack))
  }

  @ResetDsl
  public suspend inline infix fun protect(noinline value: suspend () -> T): R = suspendCoroutineAndTrampoline { stack ->
    value.startCoroutineUninterceptedOrReturn(makeUnderCont(stack))
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}


@ResetDsl
public suspend inline fun <R> newReset(noinline body: suspend Prompt<R>.() -> R): R =
  suspendCoroutineAndTrampoline { stack ->
    val prompt = Prompt<R>()
    body.startCoroutineUninterceptedOrReturn(prompt, PromptCont(prompt, stack.rest.realContext).apply {
      rest = stack
      prompt.cont = this
    })
  }

@PublishedApi
internal tailrec fun FramesCont<*, *>.handleTrampolining(
  result: Result<Any?>,
): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
  val trampoline = rest.realContext.trampoline
  val step = trampoline.nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn())
} else result.getOrThrow()


public suspend inline fun <T, R> runReader(
  value: T,
  noinline fork: T.() -> T = { this },
  noinline body: suspend Reader<T>.() -> R
): R = suspendCoroutineAndTrampoline { stack ->
  val reader = Reader(fork)
  body.startCoroutineUninterceptedOrReturn(reader, ReaderT<_, R>(reader, stack.rest.realContext, value).apply {
    rest = stack
    reader.cont = this
  })
}

@ResetDsl
public suspend inline fun yieldToTrampoline(): Unit = suspendCoroutineToTrampoline { stack ->
  stack.resumeWithIntercepted(Result.success(Unit))
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(
  noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (rest, init) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init, true), rest)
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
  val (rest, init) = stack.splitAt(this)
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
  val (rest, init) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init, true) to SubCont(init), rest)
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
  crossinline body: suspend (SubCont<T, P>) -> T
): T = shiftWithFinal { (reusable, once) ->
  once.protect {
    body(reusable)
  }
}

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
  cont!!.underflow().resumeWithIntercepted(value)
  throw SuspendedException
}

public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    cont!!.underflow().resumeWithIntercepted(value)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing {
  value.startCoroutineIntercepted(cont!!.underflow())
  throw SuspendedException
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException

@PublishedApi
internal data object SuspendedException : NoTrace()

internal inline fun <R> runCatching(block: () -> R, onSuspend: () -> Nothing): Result<R> =
  try {
    val outcome = block()
    if (outcome === COROUTINE_SUSPENDED) onSuspend()
    Result.success(outcome)
  } catch (exception: Throwable) {
    if (exception === SuspendedException) onSuspend()
    Result.failure(exception)
  }

public suspend fun <R> runCC(body: suspend () -> R): R = withContext(currentCoroutineContext().makeTrampoline()) {
  suspendCancellableCoroutine {
    body.startCoroutine(EmptyCont(it))
  }
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineToTrampoline(
  crossinline block: (FramesCont<T, *>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(collectStack(it))
  COROUTINE_SUSPENDED
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineAndTrampoline(
  crossinline block: (FramesCont<T, *>) -> Any?
): T = suspendCoroutineUninterceptedOrReturn {
  val stack = collectStack(it)
  stack.handleTrampolining(runCatching { block(stack) })
}