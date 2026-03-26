package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.internal.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.jvm.JvmInline

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@JvmInline
public value class SubContFinal<in T, out R> @PublishedApi internal constructor(
  private val init: Segment<T, R>
) {
  @ResetDsl
  public suspend fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack, stackRest ->
    init.prependToFinal(stack, stackRest).resumeWithIntercepted(value, stackRest.trampoline)
  }

  @ResetDsl
  public suspend infix fun locally(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, stackRest ->
    value.startCoroutineIntercepted(init.prependToFinal(stack, stackRest), stackRest.trampoline)
  }

  @ResetDsl
  public suspend infix fun protect(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, stackRest ->
    value.startCoroutineIntercepted(Frames.Under(init, stack, stackRest).wrapped, stackRest.trampoline)
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@JvmInline
public value class Handler<Start> internal constructor(internal val prompt: PromptCont<Start>)

@ResetDsl
public suspend fun <R> handle(body: suspend Handler<R>.() -> R): R = suspendCoroutineHere { stack, stackRest ->
  val prompt = PromptCont(stack, stackRest)
  body.startCoroutineUninterceptedOrReturn(Handler(prompt), prompt)
}

@ResetDsl
public suspend fun yieldToTrampoline(): Unit = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.resumeWithIntercepted(Result.success(Unit), stackRest.trampoline)
}

@ResetDsl
public suspend inline fun <T, R> Handler<R>.useOnce(
  crossinline body: suspend (SubContFinal<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(stackRest) { frames, init ->
    suspend { body(SubContFinal(init)) }.startCoroutineIntercepted(frames, stackRest.trampoline)
  }
}

public fun <R> Handler<R>.discardWith(value: Result<R>): Nothing {
  prompt.underflow().resumeWithIntercepted(value, prompt.trampoline)
  throw SuspendedException
}

public suspend fun <R> Handler<R>.discardWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    prompt.underflow().resumeWithIntercepted(value, prompt.trampoline)
    COROUTINE_SUSPENDED
  }

public fun <R> Handler<R>.discard(value: suspend () -> R): Nothing {
  value.startCoroutineIntercepted(prompt.underflow(), prompt.trampoline)
  throw SuspendedException
}

public suspend fun <R> Handler<R>.discardFast(value: suspend () -> R): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    value.startCoroutineIntercepted(prompt.underflow(), prompt.trampoline)
    COROUTINE_SUSPENDED
  }

internal inline fun <R> runCatching(block: () -> R, onSuspend: () -> Nothing): Result<R> {
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    callsInPlace(onSuspend, InvocationKind.AT_MOST_ONCE)
  }
  return try {
    val outcome = block()
    if (outcome === COROUTINE_SUSPENDED) onSuspend()
    Result.success(outcome)
  } catch (exception: Throwable) {
    if (exception === SuspendedException) onSuspend()
    Result.failure(exception)
  }
}

@OptIn(ExperimentalStdlibApi::class)
@Suppress("SuspendCoroutineLacksCancellationGuarantees")
public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine { c ->
  body.startCoroutine(EmptyCont(c, Trampoline(c.context)))
}

context(p: Handler<P>)
@PublishedApi
internal inline fun <Start, P, R> Stack<Start>.splitAt(
  rest: SplitCont<*>,
  block: (Stack<P>, Segment<Start, P>) -> R
): R = block(p.stack, makeSegment(rest))

@PublishedApi
internal val <Start> Handler<Start>.stack: Stack<Start> get() = prompt.frames

context(p: Handler<P>)
@PublishedApi
internal fun <Start, P> Stack<Start>.makeSegment(rest: SplitCont<*>): Segment<Start, P> =
  Segment(p.prompt, this, rest.errorIfEmptyCont())

@PublishedApi
internal suspend inline fun <T> suspendCoroutineToTrampoline(
  crossinline block: (Stack<T>, SplitCont<*>) -> Unit
): T = collectStack { stack, stackRest ->
  stackRest.onErrorResume { block(stack, stackRest) }
  COROUTINE_SUSPENDED
}

@PublishedApi
internal suspend inline fun <T> collectStack(crossinline block: (Stack<T>, SplitCont<*>) -> Any?): T =
  suspendCoroutineUninterceptedOrReturn {
    block(Stack(it), it.context as SplitCont<*>)
  }

internal suspend inline fun <T> suspendCoroutineHere(crossinline block: (Stack<T>, SplitCont<*>) -> Any?): T =
  collectStack { stack, stackRest ->
    try {
      block(stack, stackRest)
    } catch (_: SuspendedException) {
      COROUTINE_SUSPENDED
    }
  }