package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
  receiver: R,
  param: P,
  completion: Continuation<T>
): Any? = (this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, completion)

private class SequenceBodyStep<T>(private val body: suspend MultishotScope.() -> T, override val seq: SplitSeq<T>) : Step() {
  override fun stepOrReturn() = runCatching {
    body.startCoroutineUninterceptedOrReturn(seq, seq)
  }
}

private class SequenceBodyReceiverStep<T, R>(
  private val body: suspend MultishotScope.(R) -> T,
  private val receiver: R,
  override val seq: SplitSeq<T>
) : Step() {
  override fun stepOrReturn() = runCatching {
    body.startCoroutineUninterceptedOrReturn(seq, receiver, seq)
  }
}

private class SequenceResumeStep<Start>(
  override val seq: SplitSeq<Start>,
  private val result: Result<Start>
) : Step() {
  override fun stepOrReturn() = result
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeTrampoline(): Trampoline {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is Trampoline) it.interceptor else it
  }
  return if (interceptor is Delay) TrampolineWithDelay(interceptor, interceptor, this) else Trampoline(interceptor, this)
}
@InternalCoroutinesApi
private class TrampolineWithDelay(interceptor: ContinuationInterceptor?, delay: Delay, originalContext: CoroutineContext) :
  Trampoline(interceptor, originalContext), Delay by delay

internal abstract class Step {
  abstract fun stepOrReturn(): Result<Any?>
  abstract val seq: SplitSeq<*>
}

@Suppress("UNCHECKED_CAST")
private fun Step.step() {
  val result = stepOrReturn()
  if (result.getOrNull() !== COROUTINE_SUSPENDED && result.exceptionOrNull() !== SuspendedException) {
    seq.resumeWithImpl(result as Result<Nothing>)
  }
}

@RestrictsSuspension
public open class Trampoline(@JvmField internal val interceptor: ContinuationInterceptor?, originalContext: CoroutineContext) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  @JvmField
  internal var nextStep: Step? = null

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
}
public sealed class MultishotScope(@JvmField @PublishedApi internal val trampoline: Trampoline) {
  public suspend inline fun <R> bridge(noinline block: suspend () -> R): R = suspendCoroutineUninterceptedOrReturn {
    block.startCoroutineUninterceptedOrReturn(trampoline.TrampolineContinuation(it))
  }

  @PublishedApi
  internal fun <T> (suspend MultishotScope.() -> T).startCoroutineIntercepted(seq: SplitSeq<T>) {
    trampoline.nextStep = SequenceBodyStep(this, seq)
  }

  @PublishedApi
  internal fun <R, T> (suspend MultishotScope.(R) -> T).startCoroutineIntercepted(
    receiver: R,
    seq: SplitSeq<T>,
  ) {
    trampoline.nextStep = SequenceBodyReceiverStep(this, receiver, seq)
  }

  @PublishedApi
  internal fun <Start> SplitSeq<Start>.resumeWithIntercepted(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) {
      trampoline.nextStep = SequenceResumeStep(this, result)
    }
  }
  @ResetDsl
  public suspend inline fun <T, R> SubCont<T, R>.resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack ->
    composedWith(stack).resumeWithIntercepted(value)
  }

  @ResetDsl
  public suspend inline fun <T, R> SubCont<T, R>.locally(noinline value: suspend MultishotScope.() -> T): R = suspendCoroutineToTrampoline { stack ->
    value.startCoroutineIntercepted(composedWith(stack))
  }

  public suspend operator fun <T, R> SubCont<T, R>.invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun <T, R> SubCont<T, R>.resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))

  @ResetDsl
  public suspend inline fun <R> newReset(noinline body: suspend context(Prompt<R>) MultishotScope.() -> R): R =
    suspendCoroutineAndTrampoline { stack ->
      val prompt = PromptCont(stack)
      body.startCoroutineUninterceptedOrReturn(Prompt(prompt), prompt, prompt)
    }

  public suspend inline fun <T, R> runReader(
    value: T,
    noinline fork: T.() -> T = { this },
    noinline body: suspend context(Reader<T>) MultishotScope.() -> R
  ): R = suspendCoroutineAndTrampoline { stack ->
    val reader = ReaderCont(stack, value, fork)
    body.startCoroutineUninterceptedOrReturn(Reader(reader), reader, reader)
  }

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shift(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(underlying)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("takeSubContContext")
  public suspend inline fun <T, R> shift(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = p.shift(body)

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shiftOnce(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(underlying)
    body.startCoroutineIntercepted(SubCont(init), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("takeSubContOnceContext")
  public suspend inline fun <T, R> shiftOnce(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = p.shiftOnce(body)

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shiftWithFinal(
    noinline body: suspend MultishotScope.(Pair<SubCont<T, R>, SubCont<T, R>>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(underlying)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE) to SubCont(init), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("takeSubContWithFinalContext")
  public suspend inline fun <T, R> shiftWithFinal(
    noinline body: suspend MultishotScope.(Pair<SubCont<T, R>, SubCont<T, R>>) -> R
  ): T = p.shiftWithFinal(body)

  @ResetDsl
  public suspend inline fun <T, R> Prompt<R>.shiftRepushing(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(underlying)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REPUSH), rest)
  }

  context(p: Prompt<R>)
  @ResetDsl
  @JvmName("shiftRepushingContext")
  public suspend inline fun <T, R> shiftRepushing(
    noinline body: suspend MultishotScope.(SubCont<T, R>) -> R
  ): T = p.shiftRepushing(body)

  // Acts like shift0/control { it(body()) }
  @ResetDsl
  public suspend inline fun <T, P> Prompt<P>.inHandlingContext(
    noinline body: suspend MultishotScope.(SubCont<T, P>) -> T
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(underlying)
    body.startCoroutineIntercepted(SubCont(init, OnInit.REUSABLE), UnderCont(init, rest))
  }

  @ResetDsl
  public suspend inline fun <T, P> Prompt<P>.inHandlingContextTwice(
    noinline body: suspend MultishotScope.(SubCont<T, P>) -> T
  ): T = suspendCoroutineToTrampoline { stack ->
    val (init, rest) = stack.splitAt(underlying)
    body.startCoroutineIntercepted(SubCont(init, OnInit.COPY), UnderCont(init, rest))
  }

  public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
    underlying.rest.resumeWithIntercepted(value)
    throw SuspendedException
  }

  public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
    suspendCoroutineUninterceptedOrReturn {
      underlying.rest.resumeWithIntercepted(value)
      COROUTINE_SUSPENDED
    }

  public fun <R> Prompt<R>.abortS(value: suspend MultishotScope.() -> R): Nothing {
    value.startCoroutineIntercepted(underlying.rest)
    throw SuspendedException
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
}

@PublishedApi
internal tailrec fun FramesCont<*, *>.handleTrampolining(
  result: Result<Any?>,
): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
  val step = trampoline.nextStep?.takeIf { it.seq === this && !this.copied } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn())
} else result.getOrThrow()