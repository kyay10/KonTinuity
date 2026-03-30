package io.github.kyay10.kontinuity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

public interface Choose : Exc {
  public suspend fun IntRange.bind(): Int
  public suspend fun <T> Flow<T>.bind(): T
}

context(c: Choose)
public suspend fun IntRange.bind(): Int = with(c) { this@bind.bind() }

context(c: Choose)
public suspend fun <T> Flow<T>.bind(): T = with(c) { this@bind.bind() }

public suspend fun choice(body: suspend context(Choose) () -> Unit): Unit = handle {
  body(object : Choose, Exc by exc {
    override suspend fun IntRange.bind() = use { resume -> forEachIteratorless { resume(it) } }

    override suspend fun <T> Flow<T>.bind() = use { resume -> collect { resume(it) } }
  })
}

public suspend fun <R> runList(body: suspend context(Choose) () -> R): List<R> = buildListLocally {
  choice { add(body()) }
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
): Flow<R> = channelFlow { runCC { choice { send(body()) } } }

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