package io.github.kyay10.kontinuity

import kotlin.jvm.JvmInline
import kotlin.jvm.JvmName


@JvmInline
public value class SubCont<in T, out R> @PublishedApi internal constructor(
  private val init: Segment<T, R>
) {
  @ResetDsl
  public suspend fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack, stackRest ->
    init.prependTo(stack, stackRest).copy(init.startRest).resumeWithIntercepted(value, init.trampoline)
  }

  @ResetDsl
  public suspend infix fun locally(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, stackRest ->
    value.startCoroutineIntercepted(init.prependTo(stack, stackRest).copy(init.startRest), init.trampoline)
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { frames, init ->
    suspend { body(SubCont(init)) }.startCoroutineIntercepted(frames, trampoline)
  }
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContContext")
public suspend inline fun <T, R> shift(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = p.shift(body)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftWithFinal(
  crossinline body: suspend (SubCont<T, R>, SubContFinal<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(this, stackRest) { frames, init ->
    suspend { body(SubCont(init), SubContFinal(init)) }.startCoroutineIntercepted(frames, trampoline)
  }
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend inline fun <T, R> shiftWithFinal(
  crossinline body: suspend (SubCont<T, R>, SubContFinal<T, R>) -> R
): T = p.shiftWithFinal(body)

// Acts like shift { it(body()) }
// guarantees that the continuation will be resumed at least once
@ResetDsl
public suspend inline fun <T, P> Prompt<P>.inHandlingContext(
  crossinline body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  val rest = rest.ifSegment { error("$this is not on the stack") }
  stack.splitAt(this, stackRest) { frames, init ->
    suspend { body(SubCont(init)) }.startCoroutineIntercepted(
      Frames.Under(init, frames, rest).wrapped, trampoline
    )
  }
}