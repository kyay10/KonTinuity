import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(failValue)
}

private data class MarkedListBuilder<R>(
  val builder: MutableList<R>, override val key: ListPrompt<R>
) : CoroutineContext.Element

public class ListPrompt<R> : CoroutineContext.Key<MarkedListBuilder<R>> {
  internal val prompt = Prompt<Unit>()
}

public suspend fun <R> ListPrompt<R>.pushList(builder: MutableList<R>, body: suspend () -> Unit) {
  prompt.pushPrompt(extraContext = MarkedListBuilder(builder, this), body)
}

public suspend fun <R> listReset(
  body: suspend context(SingletonRaise<Unit>) ListPrompt<R>.() -> R
): List<R> = runCC {
  val prompt = ListPrompt<R>()
  val builder = mutableListOf<R>()
  prompt.pushList(builder) {
    val result = body(SingletonRaise(PromptFail(prompt.prompt, Unit)), prompt)
    (coroutineContext[prompt] ?: error("List builder not set for $prompt")).builder.add(result)
  }
  builder
}

context(ListPrompt<R>)
public suspend fun <T, R> List<T>.bind(): T = prompt.shift { continuation ->
  for (item in this) continuation(item)
}

private data class MarkedFlowSendChannel<R>(
  val channel: SendChannel<R>, override val key: FlowPrompt<R>
) : CoroutineContext.Element

public class FlowPrompt<R> : CoroutineContext.Key<MarkedFlowSendChannel<R>> {
  internal val prompt = Prompt<Unit>()
}

public fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>) FlowPrompt<R>.() -> R
): Flow<R> = channelFlow {
  runCC {
    val prompt = FlowPrompt<R>()
    prompt.pushFlow(this) {
      val result = body(SingletonRaise(PromptFail(prompt.prompt, Unit)), prompt)
      (currentCoroutineContext()[prompt] ?: error("Flow channel not set for $prompt")).channel.send(result)
    }
  }
}

public suspend fun <R> FlowPrompt<R>.pushFlow(channel: SendChannel<R>, body: suspend () -> Unit) {
  prompt.pushPrompt(extraContext = MarkedFlowSendChannel(channel, this), body)
}

context(FlowPrompt<R>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T, R> Flow<T>.bind(): T = prompt.shift { continuation ->
  for (item in this@bind.produceIn(CoroutineScope(currentCoroutineContext()))) {
    continuation(item)
  }
}