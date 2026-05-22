package io.github.kyay10.kontinuity.stacks

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi

@JvmInline internal value class Stack(val frames: Continuation<Nothing>)

@OptIn(InternalCoroutinesApi::class)
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

  private var nextFrames: Continuation<Unit>? = null
  private var nextResult: Throwable? = null

  internal fun resumeIntercepted(stack: Stack, result: Throwable) {
    @Suppress("UNCHECKED_CAST")
    nextFrames = stack.frames as Continuation<Unit>
    nextResult = result
  }

  internal fun yield(continuation: Continuation<Unit>) {
    nextFrames = continuation
    nextResult = null
  }

  @InternalCoroutinesApi
  private class InterceptorWithDelay(interceptor: ContinuationInterceptor?, delay: Delay) :
    Interceptor(interceptor), Delay by delay

  private open class Interceptor(val interceptor: ContinuationInterceptor?) :
    AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    lateinit var trampoline: Trampoline

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
      trampoline.Cont(continuation).let { interceptor?.interceptContinuation(it) ?: it }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
      interceptor?.releaseInterceptedContinuation(continuation)
    }
  }

  private inner class Cont<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) (nextFrames ?: break)
        .also { nextFrames = null }
        .resumeWith(nextResult?.let(Result.Companion::failure) ?: Result.success(Unit))
    }
  }
}
