package io.github.kyay10.kontinuity.stacks

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmInline
import kotlinx.coroutines.suspendCancellableCoroutine

public suspend fun <R> runCC(body: suspend Locality.() -> R): R = suspendCancellableCoroutine { c ->
  suspend fun Locality.realBody(): R {
    bridge {}
    return body()
  }
  Locality::realBody.startCoroutine(Trampoline(c.context), Continuation(EmptyCoroutineContext, c::resumeWith))
}

@JvmInline internal value class Stack(val frames: Continuation<Nothing>)

@RestrictsSuspension public sealed interface Locality

internal class Trampoline internal constructor(private val context: CoroutineContext) : Locality {
  suspend fun <R> bridge(block: suspend () -> R): R = suspendCancellableCoroutine { block.startCoroutine(Cont(it)) }

  suspend fun swap(stack: Stack, block: suspend context(Locality) (Stack) -> Nothing): Nothing =
    suspendCoroutineUninterceptedOrReturn {
      try {
        @Suppress("UNCHECKED_CAST")
        val _ = (block as Function3<Locality, Stack, Continuation<*>, Any?>)(this@Trampoline, Stack(it), stack.frames)
      } catch (e: Throwable) {
        resumeIntercepted(stack, e)
      }
      COROUTINE_SUSPENDED
    }

  internal suspend fun yield() = suspendCoroutineUninterceptedOrReturn {
    yield(it)
    COROUTINE_SUSPENDED
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

  inner class Cont<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = this@Trampoline.context

    override fun resumeWith(result: Result<T>) {
      cont.resumeWith(result)
      while (true) (nextFrames ?: break)
        .also { nextFrames = null }
        .resumeWith(nextResult?.let(Result.Companion::failure) ?: Result.success(Unit))
    }
  }
}

context(locality: Locality)
public suspend fun <R> bridge(block: suspend () -> R): R =
  when (locality) {
    is Trampoline -> locality.bridge(block)
  }

context(locality: Locality)
internal suspend fun Stack.swap(block: suspend context(Locality) (Stack) -> Nothing): Nothing =
  when (locality) {
    is Trampoline -> locality.swap(this@swap, block)
  }
