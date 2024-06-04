import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(failValue)
}

context(Prompt<Unit>)
public suspend fun <R> Reader<MutableList<in R>>.pushList(builder: MutableList<R>, body: suspend () -> Unit) =
  pushPrompt(context(builder), body)

public suspend fun <R> listReset(
  body: suspend context(SingletonRaise<Unit>, Prompt<Unit>, Reader<MutableList<in R>>) () -> R
): List<R> = buildList {
  runCC {
    with(Prompt<Unit>(), Reader<MutableList<in R>>()) {
      pushList(this@buildList) {
        val result = body(
          SingletonRaise(PromptFail(given<Prompt<Unit>>(), Unit)),
          given<Prompt<Unit>>(),
          given<Reader<MutableList<in R>>>()
        )
        ask().add(result)
      }
    }
  }
}

private fun <R> R.given(): R = this

@Suppress("SUBTYPING_BETWEEN_CONTEXT_RECEIVERS")
private inline fun <A, B, R> with(a: A, b: B, block: context(A, B) () -> R): R = block(a, b)

context(Prompt<Unit>)
public suspend fun <T> List<T>.bind(): T = shift { continuation ->
  for (item in this) continuation(item)
}

public fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>, Prompt<Unit>, Reader<SendChannel<R>>) () -> R
): Flow<R> = channelFlow {
  runCC {
    with(Prompt<Unit>(), Reader<SendChannel<R>>()) {
      pushFlow(this@channelFlow) {
        val result = body(
          SingletonRaise(PromptFail(given<Prompt<Unit>>(), Unit)),
          given<Prompt<Unit>>(),
          given<Reader<SendChannel<R>>>()
        )
        ask().send(result)
      }
    }
  }
}

context(Prompt<Unit>)
public suspend fun <R> Reader<SendChannel<R>>.pushFlow(channel: SendChannel<R>, body: suspend () -> Unit) =
  pushPrompt(context(channel), body)

context(Prompt<Unit>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T> Flow<T>.bind(): T = shift { continuation ->
  this@bind.produceIn(CoroutineScope(currentCoroutineContext())).consumeEach { item ->
    continuation(item)
  }
}