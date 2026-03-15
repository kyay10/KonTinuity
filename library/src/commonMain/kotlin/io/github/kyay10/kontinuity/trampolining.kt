package io.github.kyay10.kontinuity

import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmField

@PublishedApi
internal fun <T> (suspend () -> T).startCoroutineIntercepted(seq: Frames<T>, context: CoroutineContext): Unit =
  with(context.trampoline) {
    nextFrames = seq.frames
    nextBody = this@startCoroutineIntercepted
  }

@PublishedApi
internal fun <Start> Frames<Start>.resumeWithIntercepted(result: Result<Start>, context: CoroutineContext) {
  if (result.exceptionOrNull() !== SuspendedException) with(context.trampoline) {
    nextFrames = this@resumeWithIntercepted.frames
    nextBody = null
    nextResult = result
  }
}

@OptIn(InternalCoroutinesApi::class)
internal fun CoroutineContext.makeTrampoline(): Trampoline {
  val interceptor = this[ContinuationInterceptor].let {
    if (it is Trampoline) it.interceptor else it
  }
  return if (interceptor is Delay) TrampolineWithDelay(interceptor, interceptor) else Trampoline(interceptor)
}

@InternalCoroutinesApi
private class TrampolineWithDelay(interceptor: ContinuationInterceptor?, delay: Delay) :
  Trampoline(interceptor), Delay by delay

internal open class Trampoline(val interceptor: ContinuationInterceptor?) :
  AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

  lateinit var emptyCont: EmptyCont<*>

  @JvmField
  var nextFrames: Continuation<*>? = null

  @JvmField
  var nextBody: (suspend () -> Any?)? = null

  @JvmField
  var nextResult: Result<Any?> = Result.success(null)

  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
    TrampolineContinuation(continuation).let {
      interceptor?.interceptContinuation(it) ?: it
    }

  override fun releaseInterceptedContinuation(continuation: Continuation<*>) = onErrorResume {
    interceptor?.releaseInterceptedContinuation(continuation)
  }

  internal fun resumeWithException(exception: Throwable) = emptyCont.resumeWithException(exception)

  private inner class TrampolineContinuation<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) onErrorResume {
        @Suppress("UNCHECKED_CAST")
        val nextFrames = (nextFrames ?: break) as Continuation<Any?>
        this@Trampoline.nextFrames = null
        val nextBody = this@Trampoline.nextBody
        val result =
          if (nextBody != null) runCatching({ nextBody.startCoroutineUninterceptedOrReturn(nextFrames) }) { continue } else nextResult
        Frames(nextFrames).resumeWith(result)
      }
    }
  }
}

@PublishedApi
internal val CoroutineContext.trampoline: Trampoline
  get() =
    this[ContinuationInterceptor] as? Trampoline ?: error("No trampoline in context: $this")

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