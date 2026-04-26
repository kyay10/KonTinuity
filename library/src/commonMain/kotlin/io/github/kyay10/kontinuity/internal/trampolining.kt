package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.runCatching
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi

@OptIn(InternalCoroutinesApi::class)
@PublishedApi
internal class Trampoline private constructor(context: CoroutineContext) : CoroutineContext by context {
  companion object {
    operator fun invoke(context: CoroutineContext): Trampoline {
      val interceptor =
        context[ContinuationInterceptor]
          .let { if (it is Interceptor) it.interceptor else it }
          .let { if (it is Delay) InterceptorWithDelay(it, it) else Interceptor(it) }
      return Trampoline(context + interceptor).also { interceptor.trampoline = it }
    }
  }

  lateinit var emptyCont: EmptyCont<*>

  var nextFrames: Continuation<Any?>? = null
  var nextResult: Result<Any?> = Result.success(null)

  @Suppress("UNCHECKED_CAST")
  @PublishedApi
  internal fun <T> (suspend () -> T).startCoroutineIntercepted(stack: Stack<T>): Unit =
    stack.resumeWithIntercepted(
      runCatching({ startCoroutineUninterceptedOrReturn(stack.frames) as T }) {
        return
      }
    )

  internal fun <T> Stack<T>.resumeWithIntercepted(result: Result<T>) {
    @Suppress("UNCHECKED_CAST")
    nextFrames = this@resumeWithIntercepted.frames as Continuation<Any?>
    nextResult = result
  }

  @PublishedApi
  internal inline fun onErrorResume(block: Trampoline.() -> Unit) {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    return try {
      block()
    } catch (exception: Throwable) {
      @Suppress("UNCHECKED_CAST")
      nextFrames = emptyCont as Continuation<Any?>
      nextResult = Result.failure(exception)
    }
  }

  @InternalCoroutinesApi
  private class InterceptorWithDelay(interceptor: ContinuationInterceptor?, delay: Delay) :
    Interceptor(interceptor), Delay by delay

  private open class Interceptor(val interceptor: ContinuationInterceptor?) :
    AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    lateinit var trampoline: Trampoline

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
      trampoline.Cont(continuation).let { interceptor?.interceptContinuation(it) ?: it }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) = trampoline.onErrorResume {
      interceptor?.releaseInterceptedContinuation(continuation)
    }
  }

  private inner class Cont<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) (nextFrames ?: break).also { nextFrames = null }.resumeWith(nextResult)
    }
  }
}
