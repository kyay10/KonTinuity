package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(r: Unit): Nothing = prompt.abort(failValue)
}

public typealias Choose = Prompt<Unit>

public suspend fun <R> Choose.pushChoice(body: suspend () -> R, handler: suspend (R) -> Unit) {
  pushPrompt {
    handler(body())
  }
}

public suspend fun <R> runChoice(
  body: suspend context(SingletonRaise<Unit>, Choose) () -> R, handler: suspend (R) -> Unit
): Unit = with(Choose()) {
  pushChoice({
    body(SingletonRaise(PromptFail(this, Unit)), this)
  }, handler)
}

public suspend fun <R> Choose.pushList(body: suspend () -> R): List<R> =
  runReader(mutableListOf(), MutableList<R>::toMutableList) {
    pushChoice(body) {
      ask().add(it)
    }
    ask()
  }

public suspend fun <R> runList(body: suspend context(SingletonRaise<Unit>, Choose) () -> R): List<R> =
  runReader(mutableListOf(), MutableList<R>::toMutableList) {
    runChoice(body) {
      ask().add(it)
    }
    ask()
  }

context(_: Choose)
public suspend fun <T> List<T>.bind(): T = shift { continuation ->
  (0..lastIndex).forEachIteratorless { item ->
    continuation(this[item])
  }
}

context(_: Choose)
public suspend fun <T> choose(left: T, right: T): T = shift { continuation ->
  continuation(left)
  continuation(right)
}

context(_: Choose)
public suspend fun IntRange.bind(): Int = shift { continuation ->
  (start..endInclusive).forEachIteratorless { i ->
    continuation(i)
  }
}

public suspend fun <T> replicate(amount: Int, producer: suspend (Int) -> T): List<T> = runList {
  producer((0..<amount).bind())
}

public fun <R> runFlowCC(
  body: suspend context(SingletonRaise<Unit>, Choose) () -> R
): Flow<R> = channelFlow {
  runCC {
    runChoice(body, this::send)
  }
}

context(_: Choose, scope: CoroutineScope)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T> Flow<T>.bind(): T = shift { continuation ->
  produceIn(scope).consumeEach { item -> continuation(item) }
}

private inline fun IntRange.forEachIteratorless(block: (Int) -> Unit) {
  var index = start
  while (index <= endInclusive) {
    block(index)
    index++
  }
}