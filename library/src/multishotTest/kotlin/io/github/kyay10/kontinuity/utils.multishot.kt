package io.github.kyay10.kontinuity

import arrow.core.tail
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

context(_: Choose)
suspend fun <T> List<T>.insert(element: T): PersistentList<T> {
  val index = (0..size).bind()
  return toPersistentList().add(index, element)
}

fun <T> List<T>.permutations(): Sequence<List<T>> = if (isEmpty()) sequenceOf(toPersistentList())
else sequence {
  this@permutations.tail().permutations().forEach { perm ->
    (0..perm.size).forEach { i ->
      val newPerm = perm.toPersistentList()
      yield(newPerm.add(i, this@permutations.first()))
    }
  }
}