import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(failValue)
}

public class ListPrompt<R> : Reader<MutableList<R>> {
  internal val prompt = Prompt<Unit>()
}

public suspend fun <R> ListPrompt<R>.pushList(builder: MutableList<R>, body: suspend () -> Unit) {
  prompt.pushPrompt(context(builder), body)
}

public suspend fun <R> listReset(
  body: suspend context(SingletonRaise<Unit>) ListPrompt<R>.() -> R
): List<R> = buildList {
  runCC {
    val prompt = ListPrompt<R>()
    prompt.pushList(this) {
      val result = body(SingletonRaise(PromptFail(prompt.prompt, Unit)), prompt)
      prompt.ask().add(result)
    }
  }
}

context(ListPrompt<R>)
public suspend fun <T, R> List<T>.bind(): T = prompt.shift { continuation ->
  for (item in this) continuation(item)
}

public class FlowPrompt<R> : Reader<SendChannel<R>> {
  internal val prompt = Prompt<Unit>()
}

public fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>) FlowPrompt<R>.() -> R
): Flow<R> = channelFlow {
  runCC {
    val prompt = FlowPrompt<R>()
    prompt.pushFlow(this) {
      val result = body(SingletonRaise(PromptFail(prompt.prompt, Unit)), prompt)
      prompt.ask().send(result)
    }
  }
}

public suspend fun <R> FlowPrompt<R>.pushFlow(channel: SendChannel<R>, body: suspend () -> Unit) {
  prompt.pushPrompt(context(channel), body)
}

context(FlowPrompt<R>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T, R> Flow<T>.bind(): T = prompt.shift { continuation ->
  for (item in this@bind.produceIn(CoroutineScope(currentCoroutineContext()))) {
    continuation(item)
  }
}