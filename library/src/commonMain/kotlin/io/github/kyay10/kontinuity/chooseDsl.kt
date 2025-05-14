package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import io.github.kyay10.kontinuity.effekt.given
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** MonadFail-style errors */
private class PromptFail<R>(
  private val prompt: Prompt<R>,
  private val multishotScope: MultishotScope,
  private val failValue: R
) : Raise<Unit> {
  override fun raise(r: Unit): Nothing = with(multishotScope) {
    prompt.abortWith(Result.success(failValue))
  }
}

public typealias Choose = Prompt<Unit>

public suspend fun <R> MultishotScope.runChoice(
  body: suspend context(SingletonRaise<Unit>, Choose) MultishotScope.() -> R,
  handler: suspend MultishotScope.(R) -> Unit
): Unit = newReset {
  handler(body(SingletonRaise(PromptFail(given<Prompt<Unit>>(), this, Unit)), given<Prompt<Unit>>(), this))
}

public suspend fun <R> MultishotScope.runList(body: suspend context(SingletonRaise<Unit>, Choose) MultishotScope.() -> R): List<R> =
  runReader(mutableListOf(), MutableList<R>::toMutableList) {
    runChoice(body) {
      ask().add(it)
    }
    ask()
  }

context(_: Choose)
public suspend fun <T> MultishotScope.bind(list: List<T>): T = shift { continuation ->
  (0..list.lastIndex).forEachIteratorless { item ->
    continuation(list[item])
  }
}

context(_: Choose)
public suspend fun <T> MultishotScope.choose(left: T, right: T): T = shift { continuation ->
  continuation(left)
  continuation(right)
}

context(_: Choose)
public suspend fun MultishotScope.bind(ints: IntRange): Int = shift { continuation ->
  (ints.start..ints.endInclusive).forEachIteratorless { i ->
    continuation(i)
  }
}

public suspend fun <T> MultishotScope.replicate(amount: Int, producer: suspend MultishotScope.(Int) -> T): List<T> =
  runList {
    producer(bind(0..<amount))
}

public fun <R> runFlowCC(
  body: suspend context(SingletonRaise<Unit>, Choose, CoroutineScope) MultishotScope.() -> R
): Flow<R> = channelFlow {
  runCC {
    runChoice({ body() }) {
      bridge {
        send(it)
      }
    }
  }
}

context(_: Choose, scope: CoroutineScope)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T> MultishotScope.bind(flow: Flow<T>): T = shift { continuation ->
  val channel = flow.produceIn(scope)
  channel.consume {
    val iterator = channel.iterator()
    while (bridge { iterator.hasNext() }) {
      continuation(iterator.next())
    }

  }
}

public inline fun <E, R> ReceiveChannel<E>.consume(block: ReceiveChannel<E>.() -> R): R {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  var cause: Throwable? = null
  try {
    return block()
  } catch (e: Throwable) {
    cause = e
    throw e
  } finally {
    cancelConsumed(cause)
  }
}

@PublishedApi
internal fun ReceiveChannel<*>.cancelConsumed(cause: Throwable?) {
  cancel(cause?.let {
    it as? CancellationException ?: CancellationException("Channel was consumed, consumer had failed", it)
  })
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