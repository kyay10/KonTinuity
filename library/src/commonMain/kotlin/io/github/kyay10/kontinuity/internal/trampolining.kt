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

@OptIn(InternalCoroutinesApi::class)
@PublishedApi
internal class Trampoline private constructor(context: CoroutineContext) : CoroutineContext by context {
  companion object {
    operator fun invoke(context: CoroutineContext): Trampoline {
      val interceptor = context[ContinuationInterceptor].let { if (it is Interceptor) it.interceptor else it }
        .let { if (it is Delay) InterceptorWithDelay(it, it) else Interceptor(it) }
      return Trampoline(context + interceptor).also { interceptor.trampoline = it }
    }
  }

  lateinit var emptyCont: EmptyCont<*>

  var nextFrames: Continuation<*>? = null
  var nextResult: Result<Any?> = Result.success(null)

  @Suppress("UNCHECKED_CAST")
  @PublishedApi
  internal fun <T> (suspend () -> T).startCoroutineIntercepted(stack: Stack<T>): Unit =
    stack.resumeWithIntercepted(runCatching({ startCoroutineUninterceptedOrReturn(stack.frames) as T }) { return })

  internal fun <T> Stack<T>.resumeWithIntercepted(result: Result<T>) {
    nextFrames = this@resumeWithIntercepted.frames
    nextResult = result
  }

  @PublishedApi
  internal inline fun onErrorResume(block: Trampoline.() -> Unit) {
    contract {
      callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return try {
      block()
    } catch (exception: Throwable) {
      nextFrames = emptyCont
      nextResult = Result.failure(exception)
    }
  }

  @InternalCoroutinesApi
  private class InterceptorWithDelay(
    interceptor: ContinuationInterceptor?,
    delay: Delay,
  ) : Interceptor(interceptor), Delay by delay

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

    override fun resumeWith(result: Result<T>) = with(this@Trampoline) {
      cont.resumeWith(result)
      while (true) {
        @Suppress("UNCHECKED_CAST")
        val nextFrames = (nextFrames ?: break) as Continuation<Any?>
        this.nextFrames = null
        nextFrames.resumeWith(nextResult)
      }
    }
  }
}