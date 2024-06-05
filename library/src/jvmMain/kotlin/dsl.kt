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
public suspend fun <R> Reader<MutableList<R>>.pushList(
  builder: MutableList<R> = mutableListOf(), body: suspend () -> Unit
): MutableList<R> = pushReader(builder) {
  pushPrompt {
    body()
  }
  ask()
}

public suspend fun <R> listReset(
  body: suspend context(SingletonRaise<Unit>, Prompt<Unit>, Reader<MutableList<R>>) () -> R
): List<R> = runCC {
  with(Prompt<Unit>(), ForkingReader<MutableList<R>>(MutableList<R>::toMutableList)) {
    pushList {
      val result = body(
        SingletonRaise(PromptFail(given<Prompt<Unit>>(), Unit)), given<Prompt<Unit>>(), given<Reader<MutableList<R>>>()
      )
      ask().add(result)
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

// TODO Should we use ForkingReader here?
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
  pushPrompt(context(channel), body = body)

context(Prompt<Unit>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T> Flow<T>.bind(): T = shift { continuation ->
  this@bind.produceIn(CoroutineScope(currentCoroutineContext())).consumeEach { item ->
    continuation(item)
  }
}