package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R, *, *>, private val failValue: R) : Raise<Unit> {
  override fun raise(r: Unit): Nothing = prompt.abortWith(Result.success(failValue))
}

public typealias Choose<IR, OR> = Prompt<Unit, IR, OR>

context(_: MultishotScope<Region>)
public suspend fun <R, Region> runChoice(
  body: suspend context(SingletonRaise<Unit>, Choose<NewRegion, Region>, NewScope<Region>) () -> R,
  handler: suspend context(MultishotScope<Region>) (R) -> Unit
): Unit = newReset {
  context(SingletonRaise<Unit>(PromptFail(this, Unit))) {
    handler(body())
  }
}

context(_: MultishotScope<Region>)
public suspend fun <R, Region> runList(body: suspend context(SingletonRaise<Unit>, Choose<NewRegion, Region>, NewScope<Region>) () -> R): List<R> =
  runReader(mutableListOf(), MutableList<R>::toMutableList) {
    runChoice(body) {
      ask().add(it)
    }
    ask()
  }

context(_: Choose<IR, OR>, _: MultishotScope<IR>)
public suspend fun <T, IR, OR> List<T>.bind(): T = shift { continuation ->
  (0..lastIndex).forEachIteratorless { item ->
    continuation(this[item])
  }
}

context(_: Choose<IR, OR>, _: MultishotScope<IR>)
public suspend fun <T, IR, OR> choose(left: T, right: T): T = shift { continuation ->
  continuation(left)
  continuation(right)
}

context(_: Choose<IR, OR>, _: MultishotScope<IR>)
public suspend fun <IR, OR> IntRange.bind(): Int = shift { continuation ->
  (start..endInclusive).forEachIteratorless { i ->
    continuation(i)
  }
}

context(_: MultishotScope<Region>)
public suspend fun <T, Region> replicate(amount: Int, producer: suspend context(MultishotScope<Region>) (Int) -> T): List<T> = runList {
  producer((0..<amount).bind())
}

public fun <R> runFlowCC(
  body: suspend context(SingletonRaise<Unit>, Choose<NewRegion, Any?>, CoroutineScope, NewScope<Any?>) () -> R
): Flow<R> = channelFlow {
  runCC {
    runChoice({ body() }) { bridge { send(it) } }
  }
}

context(_: Choose<IR, OR>, scope: CoroutineScope, _: MultishotScope<IR>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T, IR, OR> Flow<T>.bind(): T = shift { continuation ->
  val channel = produceIn(scope)
  channel.consume {
    val iterator = channel.iterator()
    while (bridge { iterator.hasNext() }) {
      continuation(iterator.next())
    }
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