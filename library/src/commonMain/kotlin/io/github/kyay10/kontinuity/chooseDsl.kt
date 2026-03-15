package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

public interface Choose : Raise<Unit> {
  public suspend fun IntRange.bind(): Int
  public suspend fun <T> Flow<T>.bind(): T
}

context(c: Choose)
public suspend fun IntRange.bind(): Int = with(c) { bind() }

context(c: Choose)
public suspend fun <T> Flow<T>.bind(): T = with(c) { bind() }

public suspend fun <R> runChoice(
  body: suspend context(Choose) () -> R, handler: suspend (R) -> Unit
): Unit = newReset {
  handler(body(object : Choose {
    override suspend fun IntRange.bind() = shift { resume -> forEachIteratorless { resume(it) } }

    override suspend fun <T> Flow<T>.bind() = shift { resume -> collect { resume(it) } }

    override fun raise(r: Unit) = abortWith(Result.success(Unit))
  }))
}

public suspend fun <R> runList(body: suspend context(Choose) () -> R): List<R> = buildListLocally {
  runChoice(body) { add(it) }
}

context(c: Choose)
public suspend fun <T> List<T>.bind(): T = this[indices.bind()]

context(_: Choose)
public suspend fun <T> choose(left: T, right: T): T = listOf(left, right).bind()

public suspend fun <T> replicate(amount: Int, producer: suspend (Int) -> T): List<T> = runList {
  producer((0..<amount).bind())
}

public fun <R> runFlowCC(
  body: suspend context(Choose) () -> R
): Flow<R> = channelFlow {
  runCC {
    runChoice(body, ::send)
  }
}

public inline fun <T, R> List<T>.foldIteratorless(initial: R, operation: (acc: R, T) -> R): R {
  var accumulator = initial
  forEachIteratorless { element ->
    accumulator = operation(accumulator, element)
  }
  return accumulator
}

public inline fun <T, R> List<T>.foldRightIteratorless(initial: R, operation: (T, acc: R) -> R): R {
  var accumulator = initial
  indices.reversed().forEachIteratorless { element ->
    accumulator = operation(get(element), accumulator)
  }
  return accumulator
}

public inline fun IntProgression.forEachIteratorless(block: (Int) -> Unit) {
  var value = first
  if (isEmpty()) return
  while (true) {
    block(value)
    if (value == last) break
    value += step
  }
}

public inline fun <T> List<T>.forEachIteratorless(block: (T) -> Unit) {
  var index = 0
  while (index <= size - 1) {
    block(get(index))
    index++
  }
}