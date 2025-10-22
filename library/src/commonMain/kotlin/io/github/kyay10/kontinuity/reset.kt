package io.github.kyay10.kontinuity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

@PublishedApi
internal enum class OnInit {
  NONE,
  REUSABLE,
  COPY,
  REPUSH,
}

public class SubCont<in T, out R> @PublishedApi internal constructor(
  private var init: SingleUseSegment<T, R>,
  private var onInitialize: OnInit = OnInit.NONE,
) {
  @PublishedApi
  internal fun composedWith(stack: FramesCont<in R, *>): SplitSeq<T> {
    when (onInitialize) {
      OnInit.REUSABLE -> init = init.makeReusable()
      OnInit.COPY -> init = init.makeCopy()
      OnInit.REPUSH -> init.makeReusable()
      OnInit.NONE -> Unit
    }
    onInitialize = OnInit.NONE
    return init prependTo stack
  }

  @ResetDsl
  context(scope: MultishotScope)
  public suspend inline fun resumeWith(value: Result<T>): R = scope.suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  @ResetDsl
  context(scope: MultishotScope)
  public suspend inline fun locally(noinline value: suspend context(MultishotScope) () -> T): R =
    scope.suspendCoroutineToTrampoline { stack ->
      value.startCoroutineIntercepted(composedWith(stack))
    }

  context(scope: MultishotScope)
  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))

  context(scope: MultishotScope)
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}


@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <R> newReset(noinline body: suspend context(MultishotScope) Prompt<R>.() -> R): R =
  scope.suspendCoroutineAndTrampoline { stack ->
    val prompt = PromptCont(stack)
    body.startCoroutineUninterceptedOrReturn(prompt, Prompt(prompt), prompt)
  }

@PublishedApi
internal tailrec fun FramesCont<*, *>.handleTrampolining(
  result: Result<Any?>,
): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
  val trampoline = trampoline
  val step = trampoline.nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn())
} else result.getOrThrow()

context(scope: MultishotScope)
public suspend inline fun <T, R> runReader(
  value: T,
  noinline fork: T.() -> T = { this },
  noinline body: suspend context(MultishotScope) Reader<T>.() -> R
): R = scope.suspendCoroutineAndTrampoline { stack ->
  val reader = ReaderCont(stack, value, fork)
  body.startCoroutineUninterceptedOrReturn(reader, Reader(reader), reader)
}

@ResetDsl
context(scope: MultishotScope)
public suspend inline fun yieldToTrampoline(): Unit = scope.suspendCoroutineToTrampoline { stack ->
  stack.resumeWithIntercepted(Result.success(Unit))
}

@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <T, R> Prompt<R>.shift(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), rest)
}

@ResetDsl
@JvmName("takeSubContContext")
context(p: Prompt<R>, _: MultishotScope)
public suspend inline fun <T, R> shift(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> R
): T = p.shift(body)

@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <T, R> Prompt<R>.shiftOnce(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<R>, _: MultishotScope)
@ResetDsl
@JvmName("takeSubContOnceContext")
public suspend inline fun <T, R> shiftOnce(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> R
): T = p.shiftOnce(body)

@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <T, R> Prompt<R>.shiftWithFinal(
  noinline body: suspend context(MultishotScope) (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE) to SubCont(init), rest)
}

context(p: Prompt<R>, _: MultishotScope)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R> shiftWithFinal(
  noinline body: suspend context(MultishotScope) (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = p.shiftWithFinal(body)

@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <T, R> Prompt<R>.shiftRepushing(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REPUSH), rest)
}

context(p: Prompt<R>, _: MultishotScope)
@ResetDsl
@JvmName("shiftRepushingContext")
public suspend inline fun <T, R> shiftRepushing(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> R
): T = p.shiftRepushing(body)

// Acts like shift0/control { it(body()) }
@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <T, R> Prompt<R>.inHandlingContext(
  noinline body: suspend context(MultishotScope) (SubCont<T, R>) -> T
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), UnderCont(init, rest))
}

@ResetDsl
context(scope: MultishotScope)
public suspend inline fun <T, P> Prompt<P>.inHandlingContextTwice(
  noinline body: suspend context(MultishotScope) (SubCont<T, P>) -> T
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.COPY), UnderCont(init, rest))
}

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
  underlying.rest.resumeWithIntercepted(value)
  throw SuspendedException
}

context(scope: MultishotScope)
public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing = scope.suspendCoroutineForever {
  underlying.rest.resumeWithIntercepted(value)
}

public fun <R> Prompt<R>.abortS(value: suspend context(MultishotScope) () -> R): Nothing {
  value.startCoroutineIntercepted(underlying.rest)
  throw SuspendedException
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException

@Suppress("ObjectInheritsException")
@PublishedApi
internal data object SuspendedException : NoTrace()

public suspend fun <R> runCC(body: suspend context(MultishotScope) () -> R): R {
  val trampoline = currentCoroutineContext().makeTrampoline()
  return withContext(trampoline) {
    suspendCancellableCoroutine {
      val emptyCont = EmptyCont(it, trampoline)
      body.startCoroutine(emptyCont, emptyCont)
    }
  }
}

@RestrictsSuspension
public sealed class MultishotScope(@JvmField @PublishedApi internal val trampoline: Trampoline) {
  public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = suspendCoroutineUninterceptedOrReturn {
    block.startCoroutineUninterceptedOrReturn(trampoline.TrampolineContinuation(it))
  }

  @PublishedApi
  internal suspend inline fun suspendCoroutineForever(
    crossinline block: () -> Unit
  ): Nothing = suspendCoroutineUninterceptedOrReturn {
    block()
    COROUTINE_SUSPENDED
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
}
context(scope: MultishotScope)
public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = scope.bridge(block)