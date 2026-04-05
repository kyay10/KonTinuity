@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kyay10.kontinuity.internal

import js.array.component1
import js.array.component2
import js.objects.Object
import js.objects.ObjectLike
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private val myContinuation = Continuation<Unit>(EmptyCoroutineContext) {}

private val sampleCont: ObjectLike =
  suspend { suspendCoroutineUninterceptedOrReturn<Continuation<*>> { it.intercepted() } }.asDynamic()(myContinuation)

private val resultContinuationName = Object.entries(sampleCont).single { (_, v) -> v === myContinuation }.component1()
private val interceptedName = Object.entries(sampleCont).single { (_, v) -> v === sampleCont }.component1()

internal actual val <N> Frames<*, N>.completion: Stack<N> get() = Stack(frames.asDynamic()[resultContinuationName])

@Suppress("UNCHECKED_CAST", "UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
internal actual fun <T, N> Frames<T, N>.invokeCopied(completion: Stack<N>, result: Result<T>): N =
  Object.create(Object.getPrototypeOf(frames)).apply {
    Object.assign(this, frames)
    this as ObjectLike
    // mimicking the constructor
    this[resultContinuationName] = completion.frames
    this[interceptedName] = null
  }.invokeSuspend(result) as N