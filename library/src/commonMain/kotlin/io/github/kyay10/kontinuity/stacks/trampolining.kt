package io.github.kyay10.kontinuity.stacks

import io.github.kyay10.highkt.Constructor
import io.github.kyay10.highkt.Id
import io.github.kyay10.highkt.K
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.JvmInline
import kotlinx.coroutines.suspendCancellableCoroutine

public suspend fun <R> runCC(body: suspend Locality<*>.() -> R): R = suspendCancellableCoroutine { c ->
  suspend fun Locality<*>.realBody(): R {
    bridge {}
    return body()
  }
  Locality<*>::realBody.startCoroutine(Trampoline<Any?>(c.context), Continuation(EmptyCoroutineContext, c::resumeWith))
}

@JvmInline internal value class Stack<out resumption>(val frames: Continuation<Nothing>)

@RestrictsSuspension public sealed interface Locality<out local>

internal class Trampoline<local> internal constructor(private val context: CoroutineContext) : Locality<local> {
  suspend fun <R> bridge(block: suspend () -> R): R = suspendCancellableCoroutine { block.startCoroutine(Cont(it)) }

  suspend fun <that> swap(
    stack: Stack<that>,
    block: suspend context(Locality<that>) (Stack<local>) -> Nothing,
  ): Nothing = suspendCoroutineUninterceptedOrReturn {
    try {
      @Suppress("UNCHECKED_CAST")
      val _ =
        (block as Function3<Locality<*>, Stack<local>, Continuation<*>, Any?>)(this@Trampoline, Stack(it), stack.frames)
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

  internal fun resumeIntercepted(stack: Stack<*>, result: Throwable) {
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

context(locality: Locality<*>)
public suspend fun <R> bridge(block: suspend () -> R): R =
  when (locality) {
    is Trampoline -> locality.bridge(block)
  }

// HKTs!
public typealias Local<F, local> = K<F, local>

public typealias Constant<T, U> = Id<T>
public typealias Global<T> = K<Constructor<Constant<*, *>>, T>