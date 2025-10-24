package io.github.kyay10.kontinuity

import io.github.kyay10.regional.Regional
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

public class SubCont<in T, out R, in Region> @PublishedApi internal constructor(
  private var init: SingleUseSegment<T, R, Region>,
  private var onInitialize: OnInit = OnInit.NONE,
) {
  @PublishedApi
  internal fun composedWith(stack: FramesCont<in R, *, Region>): SplitSeq<T, *> {
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
  context(scope: MultishotScope<Region>)
  public suspend inline fun resumeWith(value: Result<T>): R = scope.suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  context(scope: MultishotScope<Region>)
  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))

  context(scope: MultishotScope<Region>)
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

public typealias NewRegion = @Regional Nothing
public typealias NewScope<Outer> = MultishotScope<@Regional Outer>

@ResetDsl
context(scope: MultishotScope<Region>)
public suspend inline fun <R, Region> newReset(noinline body: suspend context(NewScope<Region>) Prompt<R, NewRegion, Region>.() -> R): R =
  scope.suspendCoroutineAndTrampoline { stack ->
    val prompt = PromptCont(stack)
    body.startCoroutineUninterceptedOrReturn(prompt, Prompt(prompt), prompt)
  }

@PublishedApi
internal tailrec fun FramesCont<*, *, *>.handleTrampolining(
  result: Result<Any?>,
): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
  val trampoline = trampoline
  val step = trampoline.nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn())
} else result.getOrThrow()

context(scope: MultishotScope<Region>)
public suspend inline fun <T, R, Region> runReader(
  value: T,
  noinline fork: T.() -> T = { this },
  noinline body: suspend context(MultishotScope<Region>) Reader<T>.() -> R
): R = scope.suspendCoroutineAndTrampoline { stack ->
  val reader = ReaderCont(stack, value, fork)
  body.startCoroutineUninterceptedOrReturn(reader, Reader(reader), reader)
}

@ResetDsl
context(scope: MultishotScope<*>)
public suspend inline fun yieldToTrampoline(): Unit = scope.suspendCoroutineToTrampoline { stack ->
  stack.resumeWithIntercepted(Result.success(Unit))
}

@ResetDsl
context(scope: MultishotScope<IR>)
public suspend inline fun <T, R, IR, OR> Prompt<R, IR, OR>.shift(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), rest)
}

@ResetDsl
@JvmName("takeSubContContext")
context(p: Prompt<R, IR, OR>, _: MultishotScope<IR>)
public suspend inline fun <T, R, IR, OR> shift(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> R
): T = p.shift(body)

@ResetDsl
context(scope: MultishotScope<IR>)
public suspend inline fun <T, R, IR, OR> Prompt<R, IR, OR>.shiftOnce(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<R, IR, OR>, _: MultishotScope<IR>)
@ResetDsl
@JvmName("takeSubContOnceContext")
public suspend inline fun <T, R, IR, OR> shiftOnce(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> R
): T = p.shiftOnce(body)

@ResetDsl
context(scope: MultishotScope<IR>)
public suspend inline fun <T, R, IR, OR> Prompt<R, IR, OR>.shiftWithFinal(
  noinline body: suspend context(MultishotScope<OR>) (Pair<SubCont<T, R, OR>, SubCont<T, R, OR>>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE) to SubCont(init), rest)
}

context(p: Prompt<R, IR, OR>, _: MultishotScope<IR>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R, IR, OR> shiftWithFinal(
  noinline body: suspend context(MultishotScope<OR>) (Pair<SubCont<T, R, OR>, SubCont<T, R, OR>>) -> R
): T = p.shiftWithFinal(body)

@ResetDsl
context(scope: MultishotScope<IR>)
public suspend inline fun <T, R, IR, OR> Prompt<R, IR, OR>.shiftRepushing(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> R
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REPUSH), rest)
}

context(p: Prompt<R, IR, OR>, _: MultishotScope<IR>)
@ResetDsl
@JvmName("shiftRepushingContext")
public suspend inline fun <T, R, IR, OR> shiftRepushing(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> R
): T = p.shiftRepushing(body)

// Acts like shift0/control { it(body()) }
@ResetDsl
context(scope: MultishotScope<IR>)
public suspend inline fun <T, R, IR, OR> Prompt<R, IR, OR>.inHandlingContext(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, R, OR>) -> T
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), UnderCont(init, rest))
}

@ResetDsl
context(scope: MultishotScope<IR>)
public suspend inline fun <T, P, IR, OR> Prompt<P, IR, OR>.inHandlingContextTwice(
  noinline body: suspend context(MultishotScope<OR>) (SubCont<T, P, OR>) -> T
): T = scope.suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.COPY), UnderCont(init, rest))
}

public fun <R> Prompt<R, *, *>.abortWith(value: Result<R>): Nothing {
  underlying.rest.resumeWithIntercepted(value)
  throw SuspendedException
}

context(scope: MultishotScope<Region>)
public suspend inline fun <R, Region> Prompt<R, Region, *>.abortWithFast(value: Result<R>): Nothing =
  scope.suspendCoroutineForever {
    underlying.rest.resumeWithIntercepted(value)
  }

public fun <R, OR> Prompt<R, *, OR>.abortS(value: suspend context(MultishotScope<OR>) () -> R): Nothing {
  value.startCoroutineIntercepted(underlying.rest)
  throw SuspendedException
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect open class NoTrace() : CancellationException

@Suppress("ObjectInheritsException")
@PublishedApi
internal data object SuspendedException : NoTrace()

public suspend fun <R> runCC(body: suspend context(MultishotScope<Any?>) () -> R): R {
  val trampoline = currentCoroutineContext().makeTrampoline()
  return withContext(trampoline) {
    suspendCancellableCoroutine {
      val emptyCont = EmptyCont(it, trampoline)
      body.startCoroutine(emptyCont, emptyCont)
    }
  }
}

@RestrictsSuspension
public sealed class MultishotScope<out Region>(@JvmField @PublishedApi internal val trampoline: Trampoline) {
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
    crossinline block: (FramesCont<T, *, Region>) -> Unit
  ): T = suspendCoroutineUninterceptedOrReturn {
    block(collectStack(it))
    COROUTINE_SUSPENDED
  }

  @PublishedApi
  internal suspend inline fun <T> suspendCoroutineAndTrampoline(
    crossinline block: (FramesCont<T, *, Region>) -> Any?
  ): T = suspendCoroutineUninterceptedOrReturn {
    val stack = collectStack(it)
    stack.handleTrampolining(runCatching { block(stack) })
  }
}

context(scope: MultishotScope<*>)
public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = scope.bridge(block)