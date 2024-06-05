import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(failValue)
}

public typealias Choice = Prompt<Unit>

public suspend fun <R> Choice.pushChoice(body: suspend () -> R, handler: suspend (R) -> Unit) {
  pushPrompt {
    handler(body())
  }
}

public suspend fun <R> runChoice(
  body: suspend context(SingletonRaise<Unit>, Choice) () -> R, handler: suspend (R) -> Unit
) {
  val prompt = Prompt<Unit>()
  prompt.pushChoice({
    body(SingletonRaise(PromptFail(prompt, Unit)), prompt)
  }, handler)
}

public suspend fun <R> Choice.pushList(body: suspend () -> R): List<R> =
  runForkingReader(mutableListOf(), MutableList<R>::toMutableList) {
    pushChoice(body) {
      ask().add(it)
    }
    ask()
  }

public suspend fun <R> runList(body: suspend context(SingletonRaise<Unit>, Choice) () -> R): List<R> =
  runForkingReader(mutableListOf(), MutableList<R>::toMutableList) {
    runChoice(body) {
      ask().add(it)
    }
    ask()
  }

public suspend fun <R> listReset(body: suspend context(SingletonRaise<Unit>, Choice) () -> R): List<R> =
  runCC { runList(body) }

context(Choice)
public suspend fun <T> List<T>.bind(): T = shift { continuation ->
  for (item in 0..lastIndex) continuation(this[item])
}

context(Choice)
public suspend fun IntRange.bind(): Int = shift { continuation ->
  for (i in start..endInclusive) continuation(i)
}

public suspend fun <T> replicate(amount: Int, producer: suspend (Int) -> T): List<T> = runList {
  producer((0..<amount).bind())
}

public fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>, Choice) () -> R
): Flow<R> = channelFlow {
  runCC {
    runChoice(body, this::send)
  }
}

context(Choice)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T> Flow<T>.bind(): T = shift { continuation ->
  // TODO implement coroutineScope { ... }
  nonReentrant {
    produceIn(CoroutineScope(currentCoroutineContext())).consumeEach { item ->
      continuation(item)
    }
  }
}