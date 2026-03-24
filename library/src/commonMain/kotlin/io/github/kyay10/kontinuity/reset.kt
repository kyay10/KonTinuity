package io.github.kyay10.kontinuity

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.cancellation.CancellationException
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
    init.prependToFinal(stack, stackRest).resumeWithIntercepted(value, init.trampoline)
  }

  @ResetDsl
  public suspend infix fun locally(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, stackRest ->
    value.startCoroutineIntercepted(init.prependToFinal(stack, stackRest), init.trampoline)
  }

  @ResetDsl
  public suspend infix fun protect(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, stackRest ->
    value.startCoroutineIntercepted(Frames.Under(init, stack, stackRest).wrapped, init.trampoline)
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@ResetDsl
public suspend fun <R> newReset(body: suspend Prompt<R>.() -> R): R = suspendCoroutineHere { stack, stackRest ->
  val prompt = PromptCont(stack, stackRest)
  body.startCoroutineUninterceptedOrReturn(Prompt(prompt), prompt)
}

@ResetDsl
public suspend fun <T, R> runReader(value: T, fork: T.() -> T = { this }, body: suspend Reader<T>.() -> R): R =
  suspendCoroutineHere { stack, stackRest ->
    val reader = ReaderCont(fork, value, stack, stackRest)
    body.startCoroutineUninterceptedOrReturn(Reader(reader), reader)
  }

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend fun <S : Stateful<S>, R> runReader(value: S, body: suspend Reader<S>.() -> R): R =
  runReader(value, Stateful<S>::fork, body)

@ResetDsl
public suspend fun yieldToTrampoline(): Unit = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.resumeWithIntercepted(Result.success(Unit), stackRest.trampoline)
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftOnce(
  crossinline body: suspend (SubContFinal<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(stackRest) { frames, init ->
    suspend { body(SubContFinal(init)) }.startCoroutineIntercepted(frames, init.trampoline)
  }
}

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
  prompt.underflow().resumeWithIntercepted(value, prompt.trampoline)
  throw SuspendedException
}

public suspend fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    prompt.underflow().resumeWithIntercepted(value, prompt.trampoline)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing {
  value.startCoroutineIntercepted(prompt.underflow(), prompt.trampoline)
  throw SuspendedException
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException

@Suppress("ObjectInheritsException")
@PublishedApi
internal data object SuspendedException : NoTrace()

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

@Suppress("SuspendCoroutineLacksCancellationGuarantees")
public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine { c ->
  body.startCoroutine(EmptyCont(c, Trampoline(c.context)))
}

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

private suspend inline fun <T> suspendCoroutineHere(crossinline block: (Stack<T>, SplitCont<*>) -> Any?): T =
  collectStack { stack, stackRest ->
    try {
      block(stack, stackRest)
    } catch (_: SuspendedException) {
      COROUTINE_SUSPENDED
    }
  }