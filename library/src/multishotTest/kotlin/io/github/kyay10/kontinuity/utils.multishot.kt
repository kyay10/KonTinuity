package io.github.kyay10.kontinuity

import arrow.core.Option
import arrow.core.tail
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

context(_: Choose)
suspend fun <T> List<T>.insert(element: T): PersistentList<T> {
  val index = (0..size).bind()
  return toPersistentList().add(index, element)
}

fun <T> List<T>.permutations(): Sequence<List<T>> = if (isEmpty()) sequenceOf(toPersistentList())
else sequence {
  tail().permutations().forEach { perm ->
    (0..perm.size).forEach { i ->
      yield(perm.toPersistentList().add(i, first()))
    }
  }
}

inline fun <A> Option<A>.handleErrorWith(f: () -> Option<A>): Option<A> {
  contract { callsInPlace(f, InvocationKind.AT_MOST_ONCE) }
  return onNone { return f() }
}