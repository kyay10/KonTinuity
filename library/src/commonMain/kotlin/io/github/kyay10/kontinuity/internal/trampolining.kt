package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.runCatching
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

@PublishedApi
internal fun <T> (suspend () -> T).startCoroutineIntercepted(stack: Stack<T>, trampoline: Trampoline): Unit =
  with(trampoline) {
    nextFrames = stack.frames
    nextBody = this@startCoroutineIntercepted
  }

internal fun <T> Stack<T>.resumeWithIntercepted(result: Result<T>, trampoline: Trampoline) {
  if (result.exceptionOrNull() !== SuspendedException) with(trampoline) {
    nextFrames = this@resumeWithIntercepted.frames
    nextBody = null
    nextResult = result
  }
}

@OptIn(InternalCoroutinesApi::class)
@PublishedApi
internal class Trampoline internal constructor(
  context: CoroutineContext,
  interceptor: Interceptor = context[ContinuationInterceptor].let { if (it is Interceptor) it.interceptor else it }
    .let { if (it is Delay) InterceptorWithDelay(it, it) else Interceptor(it) },
) : CoroutineContext by (context + interceptor) {
  init {
    interceptor.trampoline = this
  }

  lateinit var emptyCont: EmptyCont<*>

  var nextFrames: Continuation<*>? = null
  var nextBody: (suspend () -> Any?)? = null
  var nextResult: Result<Any?> = Result.success(null)

  @InternalCoroutinesApi
  private class InterceptorWithDelay(
    interceptor: ContinuationInterceptor?,
    delay: Delay,
  ) : Interceptor(interceptor), Delay by delay

  internal open class Interceptor(val interceptor: ContinuationInterceptor?) :
    AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    lateinit var trampoline: Trampoline

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
      trampoline.Cont(continuation).let { interceptor?.interceptContinuation(it) ?: it }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) = trampoline.emptyCont.onErrorResume {
      interceptor?.releaseInterceptedContinuation(continuation)
    }
  }

  inner class Cont<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) = with(this@Trampoline) {
      cont.resumeWith(result)
      while (true) {
        @Suppress("UNCHECKED_CAST")
        val nextFrames = (nextFrames ?: break) as Continuation<Any?>
        this.nextFrames = null
        val nextBody = this.nextBody
        val result =
          if (nextBody != null) runCatching({ nextBody.startCoroutineUninterceptedOrReturn(nextFrames) }) { continue } else nextResult
        nextFrames.resumeWith(result)
      }
    }
  }
}

@PublishedApi
internal inline fun SplitCont<*>.onErrorResume(block: () -> Unit) {
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
  }
  return try {
    block()
  } catch (exception: Throwable) {
    trampoline.nextFrames = trampoline.emptyCont
    trampoline.nextBody = null
    trampoline.nextResult = Result.failure(exception)
  }
}