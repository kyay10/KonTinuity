package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
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
private class PromptFail<Region, R>(
  private val prompt: Prompt<Region, *, R>,
  private val multishotScope: MultishotScope<Region>,
  private val failValue: R
) : Raise<Unit> {
  override fun raise(r: Unit): Nothing = with(prompt) {
    multishotScope.abortWith(Result.success(failValue))
  }
}

public interface ChoiceFunction<Region, R> {
  context(_: SingletonRaise<Unit>, _: Prompt<Region2, Region, Unit>)
  public suspend operator fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
}

public suspend fun <Region, R> MultishotScope<Region>.runChoice(
  body: ChoiceFunction<Region, R>,
  handler: suspend MultishotScope<Region>.(R) -> Unit
): Unit = newReset(
  object : PromptFunction<Region, Unit> {
    context(p: Prompt<Region2, Region, Unit>)
    override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke() {
      handler(with(body) {
        with(SingletonRaise<Unit>(PromptFail(p, this@invoke, Unit))) {
          this@invoke.invoke()
        }
      })
    }
  })

public suspend fun <Region, R> MultishotScope<Region>.runList(body: ChoiceFunction<Region, R>): List<R> =
  runReader(mutableListOf(), MutableList<R>::toMutableList) {
    runChoice(body) {
      ask().add(it)
    }
    ask()
  }

context(_: Prompt<Region, OuterRegion, Unit>)
public suspend fun <Region: OuterRegion, OuterRegion, T> MultishotScope<Region>.bind(list: List<T>): T = shift { continuation ->
  (0..list.lastIndex).forEachIteratorless { item ->
    continuation(list[item])
  }
}

context(_: Prompt<Region, OuterRegion, Unit>)
public suspend fun <Region: OuterRegion, OuterRegion, T> MultishotScope<Region>.choose(left: T, right: T): T = shift { continuation ->
  continuation(left)
  continuation(right)
}

context(_: Prompt<Region, OuterRegion, Unit>)
public suspend fun <Region: OuterRegion, OuterRegion> MultishotScope<Region>.bind(ints: IntRange): Int = shift { continuation ->
  (ints.start..ints.endInclusive).forEachIteratorless { i ->
    continuation(i)
  }
}

public suspend fun <Region, T> MultishotScope<Region>.replicate(
  amount: Int,
  producer: suspend MultishotScope<Region>.(Int) -> T
): List<T> =
  runList(object : ChoiceFunction<Region, T> {
    context(_: SingletonRaise<Unit>, _: Prompt<Region2, Region, Unit>)
    override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): T = producer(bind(0..<amount))
  })

public interface FlowChoiceFunction<Region, R> {
  context(_: SingletonRaise<Unit>, _: Prompt<Region2, Region, Unit>, _: CoroutineScope)
  public suspend operator fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
}

public fun <R> runFlowCC(
  body: FlowChoiceFunction<*, R>
): Flow<R> = channelFlow {
  runCC {
    runChoice(object : ChoiceFunction<Any?, R> {
      context(_: SingletonRaise<Unit>, _: Prompt<Region2, Any?, Unit>)
      override suspend fun <Region2 : Any?> MultishotScope<Region2>.invoke(): R = with(body) { invoke() }
    }) {
      bridge {
        send(it)
      }
    }
  }
}

context(_: Prompt<Region, OuterRegion, Unit>, scope: CoroutineScope)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <Region: OuterRegion, OuterRegion, T> MultishotScope<Region>.bind(flow: Flow<T>): T = shift { continuation ->
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