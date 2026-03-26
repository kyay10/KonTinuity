@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kyay10.kontinuity.internal

import js.objects.Object
import js.objects.ObjectLike
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private object MyInterceptor : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = Wrapper(continuation)
  class Wrapper<T>(val cont: Continuation<T>) : Continuation<T> by cont
}

private object MyContinuation : Continuation<Unit> {
  override val context: CoroutineContext
    get() = MyInterceptor

  override fun resumeWith(result: Result<Unit>) {}
}

private val sampleCont = run {
  lateinit var cont: Continuation<*>
  suspend { suspendCoroutineUninterceptedOrReturn<Unit> { cont = it } }.asDynamic()(MyContinuation)
  val _ = cont.intercepted()
  cont.unsafeCast<ObjectLike>()
}

private val resultContinuationName = Object.keys(sampleCont).single { sampleCont[it] === MyContinuation }
private val contextName = Object.keys(sampleCont).single { sampleCont[it] === MyInterceptor }
private val interceptedName = Object.keys(sampleCont).single { sampleCont[it] is MyInterceptor.Wrapper<*> }

internal actual val <N> Frames<*, N>.completion: Stack<N>?
  get() = Stack(frames.asDynamic()[resultContinuationName])

@Suppress("UNCHECKED_CAST", "UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
internal actual fun <S, N> Frames<S, N>.invokeCopied(
  completion: Stack<N>,
  context: SplitCont<*>,
  result: Result<S>
): N = Object.create(Object.getPrototypeOf(frames)).apply {
  Object.assign(this, frames)
  this as ObjectLike
  // mimicking the constructor
  this[resultContinuationName] = completion.frames
  this[contextName] = context
  this[interceptedName] = null
}.invokeSuspend(result) as N