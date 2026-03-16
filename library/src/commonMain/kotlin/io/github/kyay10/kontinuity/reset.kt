package io.github.kyay10.kontinuity

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
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
  private val init: Segmentable.Segment<T, R>,
  private val shouldCopy: Boolean = false
) {
  @PublishedApi
  internal fun composedWith(stack: Frames<R>, stackRest: SplitCont<*>, context: CoroutineContext): Frames<T> =
    init.prependTo(shouldCopy, stack, stackRest)

  @PublishedApi
  internal fun makeUnderCont(
    stack: Frames<R>,
    stackRest: SplitCont<*>,
  ): Frames.Under<@UnsafeVariance T, @UnsafeVariance R> =
    Frames.Under(init, stack, stackRest)

  @ResetDsl
  public suspend inline fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack, stackRest ->
    val context = stackRest.realContext
    composedWith(stack, stackRest, context).resumeWithIntercepted(value, context)
  }

  @ResetDsl
  public suspend inline infix fun locally(noinline value: suspend () -> T): R =
    suspendCoroutineToTrampoline { stack, stackRest ->
      val context = stackRest.realContext
      value.startCoroutineIntercepted(composedWith(stack, stackRest, context), context)
    }

  @ResetDsl
  public suspend inline infix fun protect(noinline value: suspend () -> T): R =
    suspendCoroutineToTrampoline { stack, stackRest ->
      val context = stackRest.realContext
      value.startCoroutineIntercepted(Frames(makeUnderCont(stack, stackRest)), context)
    }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}


@ResetDsl
public suspend fun <R> newReset(body: suspend Prompt<R>.() -> R): R =
  suspendCoroutineHere { stack, stackRest ->
    val prompt = Prompt(stack, stackRest)
    body.startCoroutineUninterceptedOrReturn(prompt, prompt)
  }

@ResetDsl
public suspend fun <T, R> runReader(value: T, fork: T.() -> T = { this }, body: suspend Reader<T>.() -> R): R =
  suspendCoroutineHere { stack, stackRest ->
    val reader = ReaderT(fork, value, stack, stackRest)
    body.startCoroutineUninterceptedOrReturn(reader, reader)
  }

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend inline fun <S : Stateful<S>, R> runReader(value: S, noinline body: suspend Reader<S>.() -> R): R =
  runReader(value, Stateful<S>::fork, body)

@ResetDsl
public suspend inline fun yieldToTrampoline(): Unit = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.resumeWithIntercepted(Result.success(Unit), stackRest.realContext)
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { frames, rest, init ->
    suspend { body(SubCont(init, true)) }.startCoroutineIntercepted(frames, rest.realContext)
  }
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContContext")
public suspend inline fun <T, R> shift(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = p.shift(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftOnce(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { frames, rest, init ->
    suspend { body(SubCont(init)) }.startCoroutineIntercepted(frames, rest.realContext)
  }
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContOnceContext")
public suspend inline fun <T, R> shiftOnce(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = p.shiftOnce(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftWithFinal(
  crossinline body: suspend (SubCont<T, R>, SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { frames, rest, init ->
    suspend { body(SubCont(init, true), SubCont(init)) }.startCoroutineIntercepted(frames, rest.realContext)
  }
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R> shiftWithFinal(
  crossinline body: suspend (SubCont<T, R>, SubCont<T, R>) -> R
): T = p.shiftWithFinal(body)

// Acts like shift { it(body()) }
// guarantees that the continuation will be resumed at least once
@ResetDsl
public suspend inline fun <T, P> Prompt<P>.inHandlingContext(
  crossinline body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { frames, rest, init ->
    suspend { body(SubCont(init, true)) }.startCoroutineIntercepted(
      Frames(Frames.Under(init, frames, rest)),
      rest.realContext
    )
  }
}

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
  underflow().resumeWithIntercepted(value, realContext)
  throw SuspendedException
}

public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    underflow().resumeWithIntercepted(value, realContext)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing {
  value.startCoroutineIntercepted(underflow(), realContext)
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
  val trampoline = c.context.makeTrampoline()
  body.startCoroutine(EmptyCont(c, c.context + trampoline).also { trampoline.emptyCont = it })
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineToTrampoline(
  crossinline block: (Frames<T>, SplitCont<*>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  it.context.onErrorResume {
    collectStack(it) { stack, stackRest -> block(stack, stackRest) }
  }
  COROUTINE_SUSPENDED
}

private suspend inline fun <T> suspendCoroutineHere(
  crossinline block: (Frames<T>, SplitCont<*>) -> Any?
): T = suspendCoroutineUninterceptedOrReturn {
  collectStack(it) { stack, stackRest ->
    try {
      block(stack, stackRest)
    } catch (_: SuspendedException) {
      COROUTINE_SUSPENDED
    }
  }
}