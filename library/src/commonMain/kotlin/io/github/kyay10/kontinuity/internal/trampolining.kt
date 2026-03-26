package io.github.kyay10.kontinuity.internal

import io.github.kyay10.kontinuity.runCatching
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

@PublishedApi
internal fun <T> (suspend () -> T).startCoroutineIntercepted(seq: Stack<T>, trampoline: Trampoline): Unit =
  with(trampoline) {
    nextFrames = seq.frames
    nextBody = this@startCoroutineIntercepted
  }

@PublishedApi
internal fun <Start> Stack<Start>.resumeWithIntercepted(result: Result<Start>, trampoline: Trampoline) {
  if (result.exceptionOrNull() !== SuspendedException) with(trampoline) {
    nextFrames = this@resumeWithIntercepted.frames
    nextBody = null
    nextResult = result
  }
}

@OptIn(InternalCoroutinesApi::class)
internal class Trampoline(
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

  @PublishedApi
  internal open class Interceptor(val interceptor: ContinuationInterceptor?) :
    AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    lateinit var trampoline: Trampoline

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
      trampoline.Cont(continuation).let { interceptor?.interceptContinuation(it) ?: it }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) = onErrorResume {
      interceptor?.releaseInterceptedContinuation(continuation)
    }
  }

  internal fun resumeWithException(exception: Throwable) = emptyCont.resumeWithException(exception)

  inner class Cont<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) = with(this@Trampoline) {
      cont.resumeWith(result)
      while (true) onErrorResume {
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
internal val CoroutineContext.trampoline: Trampoline
  get() =
    (this[ContinuationInterceptor] as? Trampoline.Interceptor ?: error("No trampoline in context: $this")).trampoline

@PublishedApi
internal fun CoroutineContext.resumeCCWithException(exception: Throwable) {
  trampoline.resumeWithException(exception)
}

@PublishedApi
internal inline fun CoroutineContext.onErrorResume(block: () -> Unit) {
  contract {
    callsInPlace(block, InvocationKind.AT_MOST_ONCE)
  }
  return try {
    block()
  } catch (exception: Throwable) {
    resumeCCWithException(exception)
  }
}