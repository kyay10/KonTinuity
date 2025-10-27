package io.github.kyay10.kontinuity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
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
  internal fun composedWith(stack: Frames<R>, stackRest: SplitCont<*>, context: CoroutineContext): Frames<T> =
    init.copyIf(shouldCopy, context).prependTo(stack, stackRest)

  @PublishedApi
  internal fun makeUnderCont(
    stack: Frames<R>,
    stackRest: SplitCont<*>,
    context: CoroutineContext
  ): UnderCont<@UnsafeVariance T, @UnsafeVariance R> =
    UnderCont(init, context, shouldCopy).apply { setRest(stack, stackRest) }

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
    suspendCoroutineAndTrampoline { stack, stackRest, context ->
      value.startCoroutineUninterceptedOrReturn(makeUnderCont(stack, stackRest, context))
    }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}


@ResetDsl
public suspend inline fun <R> newReset(noinline body: suspend Prompt<R>.() -> R): R =
  suspendCoroutineAndTrampoline { stack, stackRest, context ->
    val prompt = Prompt<R>()
    body.startCoroutineUninterceptedOrReturn(prompt, PromptCont(prompt, context).apply {
      setRest(stack, stackRest)
      prompt.cont = this
    })
  }

@PublishedApi
internal tailrec fun Frames<*>.handleTrampolining(
  result: Result<Any?>,
  trampoline: Trampoline
): Any? = if (COROUTINE_SUSPENDED === result.getOrNull() || SuspendedException === result.exceptionOrNull()) {
  val step = trampoline.nextStep?.takeIf { it.seq.frames === frames } ?: return COROUTINE_SUSPENDED
  trampoline.nextStep = null
  handleTrampolining(step.stepOrReturn(), trampoline)
} else result.getOrThrow()


public suspend inline fun <T, R> runReader(
  value: T,
  noinline fork: T.() -> T = { this },
  noinline body: suspend Reader<T>.() -> R
): R = suspendCoroutineAndTrampoline { stack, stackRest, context ->
  val reader = Reader(fork)
  body.startCoroutineUninterceptedOrReturn(reader, ReaderT<_, R>(reader, context, value).apply {
    setRest(stack, stackRest)
    reader.cont = this
  })
}

@ResetDsl
public suspend inline fun yieldToTrampoline(): Unit = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.resumeWithIntercepted(Result.success(Unit), stackRest.realContext)
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(
  noinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { rest, restRest, init ->
    body.startCoroutineIntercepted(SubCont(init, true), rest, restRest.realContext)
  }
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
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { rest, restRest, init ->
    body.startCoroutineIntercepted(SubCont(init), rest, restRest.realContext)
  }
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
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { rest, restRest, init ->
    body.startCoroutineIntercepted(SubCont(init, true) to SubCont(init), rest, restRest.realContext)
  }
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
  noinline body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineAndTrampoline { stack, stackRest, context ->
  stack.splitAt(this, stackRest) { rest, restRest, init ->
    body.startCoroutineUninterceptedOrReturn(SubCont(init, true), UnderCont(init, context).apply {
      setRest(rest, restRest)
    })
  }
}

public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing {
  val cont = cont!!
  cont.underflow().resumeWithIntercepted(value, cont.realContext)
  throw SuspendedException
}

public suspend inline fun <R> Prompt<R>.abortWithFast(value: Result<R>): Nothing =
  suspendCoroutineUninterceptedOrReturn {
    val cont = cont!!
    cont.underflow().resumeWithIntercepted(value, cont.realContext)
    COROUTINE_SUSPENDED
  }

public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing {
  val cont = cont!!
  value.startCoroutineIntercepted(cont.underflow(), cont.realContext)
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
  crossinline block: (Frames<T>, SplitCont<*>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  collectStack(it) { stack, stackRest -> block(stack, stackRest) }
  COROUTINE_SUSPENDED
}

@PublishedApi
internal suspend inline fun <T> suspendCoroutineAndTrampoline(
  crossinline block: (Frames<T>, SplitCont<*>, CoroutineContext) -> Any?
): T = suspendCoroutineUninterceptedOrReturn {
  collectStack(it) { stack, stackRest ->
    val realContext = stackRest.realContext
    val trampoline = realContext.trampoline
    stack.handleTrampolining(runCatching { block(stack, stackRest, realContext) }, trampoline)
  }
}