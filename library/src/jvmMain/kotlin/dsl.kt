import arrow.core.raise.Raise
import arrow.core.raise.SingletonRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*

context(A) internal fun <A> given(): A = this@A

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val pStack: PStack, private val failValue: R) : Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(pStack, deleteDelimiter = false, Result.success(failValue))
}

public suspend fun <R> resetWithFail(
  failValue: R, body: suspend context(SingletonRaise<Unit>) Prompt<R>.() -> R
): R = topReset {
  body(SingletonRaise(PromptFail(this, pStack(), failValue)), this)
}

public suspend fun <R> listReset(
  body: suspend context(SingletonRaise<Unit>) Prompt<List<R>>.() -> R
): List<R> = resetWithFail(emptyList()) {
  listOf(body(given<SingletonRaise<Unit>>(), this))
}

context(Prompt<List<R>>)
public suspend fun <T, R> List<T>.bind(): T = shift { continuation ->
  flatMap { continuation(it) }
}

public interface EffectfulFlow<out T> {
  public suspend fun collect(collector: EffectfulFlowCollector<T>)
}

public fun interface EffectfulFlowCollector<in T> {
  public suspend fun emit(value: T)
}

private object EmptyEffectfulFlow : EffectfulFlow<Nothing> {
  override suspend fun collect(collector: EffectfulFlowCollector<Nothing>) = Unit
}

@PublishedApi
internal inline fun <T> unsafeEffectfulFlow(crossinline block: suspend EffectfulFlowCollector<T>.() -> Unit): EffectfulFlow<T> {
  return object : EffectfulFlow<T> {
    override suspend fun collect(collector: EffectfulFlowCollector<T>) {
      collector.block()
    }
  }
}

public fun <T> effectfulFlowOf(vararg values: T): EffectfulFlow<T> = unsafeEffectfulFlow {
  for (value in values) emit(value)
}

public fun <T> effectfulFlowOf(value: T): EffectfulFlow<T> = unsafeEffectfulFlow {
  emit(value)
}

public suspend fun <T> FlowCollector<T>.emitAll(effectfulFlow: EffectfulFlow<T>) {
  effectfulFlow.collect { emit(it) }
}

public suspend fun <T> EffectfulFlowCollector<T>.emitAll(flow: EffectfulFlow<T>) {
  flow.collect(this)
}

public fun <R> flowReset(
  body: suspend context(SingletonRaise<Unit>) Prompt<EffectfulFlow<R>>.() -> R
): Flow<R> =
  channelFlow {
    runCC {
      newReset {
        effectfulFlowOf(body(SingletonRaise<Unit>(PromptFail<EffectfulFlow<R>>(this, pStack(), EmptyEffectfulFlow)), this))
      }.collect { send(it) }
    }
  }

context(Prompt<EffectfulFlow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun <T, R> Flow<T>.bind(): T = shift { continuation ->
  unsafeEffectfulFlow {
    for (item in this@bind.produceIn(CoroutineScope(currentCoroutineContext()))) {
      emitAll(continuation(item))
    }
  }
}