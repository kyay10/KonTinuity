package io.github.kyay10.kontinuity

import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

public suspend inline fun <T> Reader<T>.ask(): T = suspendCoroutineUninterceptedOrReturn {
  findNearestSplitSeq(it).find(this)
}

public suspend inline fun <T> Reader<T>.askOrNull(): T? = suspendCoroutineUninterceptedOrReturn {
  findNearestSplitSeq(it).findOrNull(this)
}

public suspend inline fun <T, R> runReader(value: T, noinline fork: T.() -> T = { this }, noinline body: suspend Reader<T>.() -> R): R =
  with(Reader<T>()) {
    pushReader(value, fork) { body() }
  }