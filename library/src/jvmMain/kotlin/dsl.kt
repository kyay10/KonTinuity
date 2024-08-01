import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(failValue)
}

public typealias Choose = Prompt<Unit>

public suspend fun <R> Choose.pushChoice(body: suspend () -> R, handler: suspend (R) -> Unit) {
  pushPrompt {
    handler(body())
  }
}

public suspend fun <R> runChoice(
  body: suspend context(SingletonRaise<Unit>, Choose) () -> R, handler: suspend (R) -> Unit
) {
  val prompt = Prompt<Unit>()
  prompt.pushChoice({
    body(SingletonRaise(PromptFail(prompt, Unit)), prompt)
  }, handler)
}

private data class StatefulMutableList<E>(val mutableList: MutableList<E>): Stateful<StatefulMutableList<E>>, MutableList<E> by mutableList {
  override fun fork(): StatefulMutableList<E> = copy(mutableList.toMutableList())
}

public suspend fun <R> Choose.pushList(body: suspend () -> R): List<R> =
  runStatefulReader(StatefulMutableList<R>(mutableListOf())) {
    pushChoice(body) {
      ask().add(it)
    }
    ask().mutableList
  }

public suspend fun <R> runList(body: suspend context(SingletonRaise<Unit>, Choose) () -> R): List<R> =
  runStatefulReader(StatefulMutableList<R>(mutableListOf())) {
    runChoice(body) {
      ask().add(it)
    }
    ask().mutableList
  }

public suspend fun <R> listReset(body: suspend context(SingletonRaise<Unit>, Choose) () -> R): List<R> =
  runCC { runList(body) }

context(Choose)
public suspend fun <T> List<T>.bind(): T = shift { continuation ->
  for (item in 0..lastIndex) continuation(this[item])
}

public suspend fun <T> Choose.choose(left: T, right: T): T = shift { continuation ->
  continuation(left)
  continuation(right)
}

context(Choose)
public suspend fun IntRange.bind(): Int = shift { continuation ->
  for (i in start..endInclusive) continuation(i)
}

public suspend fun <T> replicate(amount: Int, producer: suspend (Int) -> T): List<T> = runList {
  producer((0..<amount).bind())
}

public fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>, Choose) () -> R
): Flow<R> = channelFlow {
  runCC {
    runChoice(body, this::send)
  }
}

context(Choose)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T> Flow<T>.bind(): T = shift { continuation ->
  // TODO using coroutineScope in such a way is generally unsafe unless a nonReentrant block is used
  // inside of it. That's because we can't "see through" the coroutineScope and because it's stateful
  coroutineScope {
    nonReentrant {
      produceIn(this).consumeEach { item ->
        continuation(item)
      }
    }
  }
}