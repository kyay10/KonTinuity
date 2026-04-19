package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.internal.*
import kotlin.jvm.JvmInline

@JvmInline
public value class SubCont<in T, out R> internal constructor(private val init: Segment<T, R>) {
  public val final: SubContFinal<T, R> get() = SubContFinal(init)

  @ResetDsl
  public suspend fun resumeWith(value: Result<T>): R = suspendCoroutineToTrampoline { stack, rest ->
    init.prependTo(stack, rest).resumeWithIntercepted(value, rest.trampoline)
  }

  @ResetDsl
  public suspend infix fun locally(value: suspend () -> T): R = suspendCoroutineToTrampoline { stack, rest ->
    value.startCoroutineIntercepted(init.prependTo(stack, rest), rest.trampoline)
  }

  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@ResetDsl
public suspend inline fun <T, R> Handler<R>.use(crossinline body: suspend (SubCont<T, R>) -> R): T =
  split { stack, init, trampoline -> suspend { body(init) }.startCoroutineIntercepted(stack, trampoline) }

// Acts like shift { it(body()) }
// guarantees that the continuation will be resumed at least once
@ResetDsl
public suspend inline fun <T, P> Handler<P>.useTailResumptive(crossinline body: suspend (SubCont<T, P>) -> T): T {
  val rest = rest
  return split { stack, init, trampoline ->
    suspend { body(init) }.startCoroutineIntercepted(makeUnder(init.final, stack, rest), trampoline)
  }
}

@PublishedApi
internal val Handler<*>.rest: SplitCont<*> get() = prompt.rest as? SplitCont<*> ?: error("$this is not on the stack")

@PublishedApi
internal fun <T, R> makeUnder(init: SubContFinal<T, R>, stack: Stack<R>, rest: SplitCont<*>): Stack<T> =
  Stack(Under(init.init, stack, rest))

@PublishedApi
internal fun <T, R> makeSubCont(init: SubContFinal<T, R>): SubCont<T, R> = SubCont(init.init)

context(p: Handler<R>)
@PublishedApi
internal suspend inline fun <T, R> split(crossinline block: (Stack<R>, SubCont<T, R>, Trampoline) -> Unit): T =
  splitOnce { stack, init, trampoline -> block(stack, makeSubCont(init), trampoline) }