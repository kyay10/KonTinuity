@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kyay10.kontinuity.internal

import js.objects.Object
import js.objects.ObjectLike
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private val myContinuation = Continuation<Unit>(EmptyCoroutineContext) {}

private val sampleCont: ObjectLike =
  suspend { suspendCoroutineUninterceptedOrReturn<Continuation<*>> { it.intercepted() } }.asDynamic()(myContinuation)

private val resultContinuationName = Object.keys(sampleCont).single { sampleCont[it] === myContinuation }
private val contextName = Object.keys(sampleCont).single { sampleCont[it] === EmptyCoroutineContext }
private val interceptedName = Object.keys(sampleCont).single { sampleCont[it] === sampleCont }

internal actual val <N> Frames<*, N>.completion: Stack<N>? get() = Stack(frames.asDynamic()[resultContinuationName])

@Suppress("UNCHECKED_CAST", "UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
internal actual fun <T, N> Frames<T, N>.invokeCopied(
  completion: Stack<N>,
  context: SplitCont<*>,
  result: Result<T>,
): N = Object.create(Object.getPrototypeOf(frames)).apply {
  Object.assign(this, frames)
  this as ObjectLike
  // mimicking the constructor
  this[resultContinuationName] = completion.frames
  this[contextName] = context
  this[interceptedName] = null
}.invokeSuspend(result) as N