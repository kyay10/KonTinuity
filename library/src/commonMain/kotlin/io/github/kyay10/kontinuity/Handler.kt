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
  public suspend fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack, rest ->
    init.prependToFinal(stack, rest).resumeWithIntercepted(value, rest.trampoline)
  }

  @ResetDsl
  public suspend infix fun locally(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, rest ->
    value.startCoroutineIntercepted(init.prependToFinal(stack, rest), rest.trampoline)
  }

  @ResetDsl
  public suspend infix fun protect(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, rest ->
    value.startCoroutineIntercepted(Frames.Under(init, stack, rest).wrapped, rest.trampoline)
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@JvmInline
public value class Handler<Start> internal constructor(internal val prompt: Prompt<Start>)

@ResetDsl
public suspend fun <R> handle(body: suspend Handler<R>.() -> R): R = suspendCoroutineHere { stack, rest ->
  val prompt = Prompt(stack, rest)
  body.startCoroutineUninterceptedOrReturn(Handler(prompt), prompt)
}

@ResetDsl
public suspend fun yieldToTrampoline(): Unit = suspendCoroutineToTrampoline { stack, rest ->
  stack.resumeWithIntercepted(Result.success(Unit), rest.trampoline)
}

@ResetDsl
public suspend inline fun <T, R> Handler<R>.useOnce(
  crossinline body: suspend (SubContFinal<T, R>) -> R
): T = split { stack, init, trampoline ->
  suspend { body(SubContFinal(init)) }.startCoroutineIntercepted(stack, trampoline)
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

public abstract class Finalize<S> {
  protected abstract fun onSuspend(): S
  protected abstract fun onResume(state: S, isFinal: Boolean)
  internal fun suspend(): S = onSuspend()
  internal fun resume(state: S, isFinal: Boolean) = onResume(state, isFinal)
}

internal class ClauseFinalizer<Start, S>(stack: Stack<Start>, rest: Marker<*, *>, val clauses: Finalize<S>) :
  Finalizer<Start, S>(stack, rest) {
  override fun onSuspend(): S = clauses.suspend()
  override fun onResume(state: S, rest: Marker<*, *>, isFinal: Boolean) = clauses.resume(state, isFinal)
}

public suspend fun <R> Finalize<*>.finalize(body: suspend () -> R): R =
  suspendCoroutineHere { stack, rest ->
    body.startCoroutineUninterceptedOrReturn(
      if (rest is Marker<*, *>) ClauseFinalizer(stack, rest, this) else stack.frames
    )
  }

context(p: Handler<P>)
@PublishedApi
internal suspend inline fun <Start, P> split(crossinline block: (Stack<P>, Segment<Start, P>, Trampoline) -> Unit): Start =
  suspendCoroutineToTrampoline { stack, rest -> block(p.stack, stack.makeSegment(rest), rest.trampoline) }

@PublishedApi
internal val <Start> Handler<Start>.stack: Stack<Start> get() = prompt.stack

context(p: Handler<P>)
@PublishedApi
internal fun <Start, P> Stack<Start>.makeSegment(rest: SplitCont<*>): Segment<Start, P> =
  Segment(p.prompt, this, rest as? Marker<*, *> ?: error("$p is not present in the stack"))

@PublishedApi
internal suspend inline fun <T> suspendCoroutineToTrampoline(
  crossinline block: (Stack<T>, SplitCont<*>) -> Unit
): T = collectStack { stack, rest ->
  rest.onErrorResume { block(stack, rest) }
  COROUTINE_SUSPENDED
}

@PublishedApi
internal suspend inline fun <T> collectStack(crossinline block: (Stack<T>, SplitCont<*>) -> Any?): T =
  suspendCoroutineUninterceptedOrReturn {
    block(Stack(it), it.context as SplitCont<*>)
  }

private suspend inline fun <T> suspendCoroutineHere(crossinline block: (Stack<T>, SplitCont<*>) -> Any?): T =
  collectStack { stack, rest ->
    try {
      block(stack, rest)
    } catch (_: SuspendedException) {
      COROUTINE_SUSPENDED
    }
  }