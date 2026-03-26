package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.internal.Frames
import io.github.kyay10.kontinuity.internal.Segment
import io.github.kyay10.kontinuity.internal.SplitCont
import io.github.kyay10.kontinuity.internal.Stack
import io.github.kyay10.kontinuity.internal.copy
import io.github.kyay10.kontinuity.internal.ifSegment
import io.github.kyay10.kontinuity.internal.prependTo
import io.github.kyay10.kontinuity.internal.resumeWithIntercepted
import io.github.kyay10.kontinuity.internal.startCoroutineIntercepted
import kotlin.jvm.JvmInline

@JvmInline
public value class SubCont<in T, out R> @PublishedApi internal constructor(
  private val init: Segment<T, R>
) {
  @ResetDsl
  public suspend fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack, stackRest ->
    init.prependTo(stack, stackRest).copy(init.startRest).resumeWithIntercepted(value, stackRest.trampoline)
  }

  @ResetDsl
  public suspend infix fun locally(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, stackRest ->
    value.startCoroutineIntercepted(init.prependTo(stack, stackRest).copy(init.startRest), stackRest.trampoline)
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@ResetDsl
public suspend inline fun <T, R> Handler<R>.use(
  crossinline body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(stackRest) { frames, init ->
    suspend { body(SubCont(init)) }.startCoroutineIntercepted(frames, stackRest.trampoline)
  }
}

@ResetDsl
public suspend inline fun <T, R> Handler<R>.useWithFinal(
  crossinline body: suspend (SubCont<T, R>, SubContFinal<T, R>) -> R
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  stack.splitAt(stackRest) { frames, init ->
    suspend { body(SubCont(init), SubContFinal(init)) }.startCoroutineIntercepted(frames, stackRest.trampoline)
  }
}

// Acts like shift { it(body()) }
// guarantees that the continuation will be resumed at least once
@ResetDsl
public suspend inline fun <T, P> Handler<P>.useTailResumptive(
  crossinline body: suspend (SubCont<T, P>) -> T
): T = suspendCoroutineToTrampoline { stack, stackRest ->
  val rest = rest
  stack.splitAt(stackRest) { frames, init ->
    suspend { body(SubCont(init)) }.startCoroutineIntercepted(makeUnder(init, frames, rest), stackRest.trampoline)
  }
}

@PublishedApi
internal val Handler<*>.rest: SplitCont<*> get() = prompt.rest.ifSegment { error("$this is not on the stack") }

@PublishedApi
internal fun <T, P> makeUnder(init: Segment<T, P>, frames: Stack<P>, rest: SplitCont<*>): Stack<T> =
  Frames.Under(init, frames, rest).wrapped