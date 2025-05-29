package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
  receiver: R,
  param: P,
  completion: Continuation<T>
): Any? = (this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, completion)

private class SequenceBodyStep<Region, T>(
  private val body: suspend MultishotScope<Region>.() -> T,
  override val seq: SplitSeq<T>
) : Step() {
  override fun MultishotScope<*>.stepOrReturn() = runCatching {
    trampoline.rest = seq
    body.startCoroutineUninterceptedOrReturn(this as MultishotScope<Region>, seq)
  }
}

private class SequenceBodyReceiverStep<Region, T, R>(
  private val body: suspend MultishotScope<Region>.(R) -> T,
  private val receiver: R,
  override val seq: SplitSeq<T>
) : Step() {
  override fun MultishotScope<*>.stepOrReturn() = runCatching {
    trampoline.rest = seq
    body.startCoroutineUninterceptedOrReturn(this as MultishotScope<Region>, receiver, seq)
  }
}

private class SequenceResumeStep<Start>(
  override val seq: SplitSeq<Start>,
  private val result: Result<Start>
) : Step() {
  override fun MultishotScope<*>.stepOrReturn() = result
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeTrampoline(): Trampoline<Any?> {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is Trampoline<*>) it.interceptor else it
  }
  return if (interceptor is Delay) TrampolineWithDelay(interceptor, interceptor, this) else Trampoline(interceptor, this)
}

@InternalCoroutinesApi
private class TrampolineWithDelay<R>(
  interceptor: ContinuationInterceptor?,
  delay: Delay,
  originalContext: CoroutineContext
) :
  Trampoline<R>(interceptor, originalContext), Delay by delay

internal abstract class Step {
  abstract fun MultishotScope<*>.stepOrReturn(): Result<Any?>
  abstract val seq: SplitSeq<*>
}

@RestrictsSuspension
public interface MultishotScope<out Region> {
  public val trampoline: Trampoline<Region>
}

public open class Trampoline<out Region> internal constructor(
  @JvmField internal val interceptor: ContinuationInterceptor?,
  originalContext: CoroutineContext
) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  @JvmField
  internal var nextStep: Step? = null
  @JvmField
  @PublishedApi
  internal var rest: SplitSeq<*>? = null

  @JvmField
  @PublishedApi
  internal val coroutineContext: CoroutineContext = originalContext + this

  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
    TrampolineContinuation(continuation).let {
      interceptor?.interceptContinuation(it) ?: it
    }

  override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
    interceptor?.releaseInterceptedContinuation(continuation)
  }

  @PublishedApi
  internal inner class TrampolineContinuation<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = coroutineContext

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) {
        (nextStep ?: return).also { nextStep = null }.step()
      }
    }
  }
  public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = suspendCoroutineUninterceptedOrReturn {
    block.startCoroutineUninterceptedOrReturn(TrampolineContinuation(it, ))
  }

  @PublishedApi
  internal fun <Region2, T> (suspend MultishotScope<Region2>.() -> T).startCoroutineIntercepted(seq: SplitSeq<T>) {
    nextStep = SequenceBodyStep(this, seq)
  }

  @PublishedApi
  internal fun <Region2, R, T> (suspend MultishotScope<Region2>.(R) -> T).startCoroutineIntercepted(
    receiver: R,
    seq: SplitSeq<T>,
  ) {
    nextStep = SequenceBodyReceiverStep(this, receiver, seq)
  }

  @PublishedApi
  internal fun <Start> SplitSeq<Start>.resumeWithIntercepted(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) {
      nextStep = SequenceResumeStep(this, result)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun Step.step() {
    val result = stepOrReturn()
    if (result.getOrNull() !== COROUTINE_SUSPENDED && result.exceptionOrNull() !== SuspendedException) {
      seq.resumeWithImpl(result as Result<Nothing>)
    }
  }

  @ResetDsl
  public suspend inline fun <T, R> SubCont<Region, T, R>.resumeWith(value: Result<T>): R =
    suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  /*  @ResetDsl
    public suspend inline fun <T, R> SubCont<T, R>.locally(noinline value: suspend MultishotScope.() -> T): R = suspendCoroutineToTrampoline { stack ->
      value.startCoroutineIntercepted(composedWith(stack))
    }*/

  public suspend operator fun <T, R> SubCont<Region, T, R>.invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun <T, R> SubCont<Region, T, R>.resumeWithException(exception: Throwable): R =
    resumeWith(Result.failure(exception))

  public suspend inline fun <T, R> runReader(
    value: T,
    noinline fork: T.() -> T = { this },
    noinline body: suspend context(Reader<T>) MultishotScope<Region>.() -> R
  ): R = suspendCoroutineAndTrampoline { stack ->
    val reader = ReaderCont(stack, value, fork)
    rest = reader
    body.startCoroutineUninterceptedOrReturn(Reader(reader), this, reader)
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

  @PublishedApi
  internal suspend inline fun suspendCoroutineForever(
    crossinline block: () -> Unit
  ): Nothing = suspendCoroutineUninterceptedOrReturn {
    block()
    COROUTINE_SUSPENDED
  }

  @PublishedApi
  internal tailrec fun FramesCont<*, *>.handleTrampolining(
    result: Result<Any?>,
  ): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
    val step = nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
    nextStep = null
    handleTrampolining(with(step) { stepOrReturn() })
  } else {
    this@MultishotScope.rest = rest
    result.getOrThrow()
  }
}

@ResetDsl
public suspend inline fun <Region, R> MultishotScope<Region>.newReset(body: PromptFunction<Region, R>): R =
  suspendCoroutineAndTrampoline { stack ->
    val prompt = PromptCont(stack)
    rest = prompt
    val body: suspend context(Prompt<Region, Region, R>) MultishotScope<Region>.() -> R = {
      with(body) {
        invoke()
      }
    }
    body.startCoroutineUninterceptedOrReturn(Prompt(prompt), this, prompt)
  }

context(p: Prompt<InnerRegion, OuterRegion, R>)
@ResetDsl
public suspend inline fun <InnerRegion: OuterRegion, OuterRegion, T, R> MultishotScope<InnerRegion>.shift(
  noinline body: suspend MultishotScope<OuterRegion>.(SubCont<OuterRegion, T, R>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p.underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), rest)
}

context(p: Prompt<InnerRegion, OuterRegion, R>)
@ResetDsl
public suspend inline fun <InnerRegion: OuterRegion, OuterRegion, T, R> MultishotScope<InnerRegion>.shiftOnce(
  noinline body: suspend MultishotScope<OuterRegion>.(SubCont<OuterRegion, T, R>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p.underlying)
  body.startCoroutineIntercepted(SubCont(init), rest)
}

context(p: Prompt<InnerRegion, OuterRegion , R>)
@ResetDsl
public suspend inline fun <InnerRegion: OuterRegion, OuterRegion, T, R> MultishotScope<InnerRegion>.shiftWithFinal(
  noinline body: suspend MultishotScope<OuterRegion>.(Pair<SubCont<OuterRegion, T, R>, SubCont<OuterRegion, T, R>>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p.underlying)
  body.startCoroutineIntercepted(SubCont<OuterRegion, _, _>(init, OnInit.REUSABLE) to SubCont(init), rest)
}

context(p: Prompt<InnerRegion, OuterRegion, R>)
@ResetDsl
public suspend inline fun <InnerRegion: OuterRegion, OuterRegion, T, R> MultishotScope<InnerRegion>.shiftRepushing(
  noinline body: suspend MultishotScope<OuterRegion>.(SubCont<OuterRegion, T, R>) -> R
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p.underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REPUSH), rest)
}

// Acts like shift0/control { it(body()) }
context(p: Prompt<InnerRegion, OuterRegion, P>)
@ResetDsl
public suspend inline fun <InnerRegion: OuterRegion, OuterRegion, T, P> MultishotScope<InnerRegion>.inHandlingContext(
  noinline body: suspend MultishotScope<OuterRegion>.(SubCont<OuterRegion, T, P>) -> T
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p.underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), UnderCont(init, rest))
}

context(p: Prompt<InnerRegion, OuterRegion, P>)
@ResetDsl
public suspend inline fun <InnerRegion: OuterRegion, OuterRegion, T, P> MultishotScope<InnerRegion>.inHandlingContextTwice(
  noinline body: suspend MultishotScope<OuterRegion>.(SubCont<OuterRegion, T, P>) -> T
): T = suspendCoroutineToTrampoline { stack ->
  val (init, rest) = stack.splitAt(p.underlying)
  body.startCoroutineIntercepted(SubCont(init, OnInit.COPY), UnderCont(init, rest))
}

// This isn't effect safe, but that's intentional because we want to bridge with `Raise`
context(p: Prompt<Region, *, R>)
public fun <Region, R> MultishotScope<Region>.abortWith(value: Result<R>): Nothing {
  p.underlying.rest.resumeWithIntercepted(value)
  throw SuspendedException
}

context(p: Prompt<Region, *, R>)
public suspend inline fun <Region, R> MultishotScope<Region>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineForever {
    p.underlying.rest.resumeWithIntercepted(value)
  }

// This isn't effect safe, but that's intentional because we want to bridge with `Raise`
context(p: Prompt<InnerRegion, OuterRegion, R>)
public fun <InnerRegion: OuterRegion, OuterRegion, R> MultishotScope<InnerRegion>.abortS(value: suspend MultishotScope<OuterRegion>.() -> R): Nothing {
  value.startCoroutineIntercepted(p.underlying.rest)
  throw SuspendedException
}