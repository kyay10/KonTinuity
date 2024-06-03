import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private class StateValue<T>(val value: T, override val key: StateImpl<T, *>) : CoroutineContext.Element

public sealed interface State<T>

private val <T> State<T>.impl
  get() = when (this) {
    is StateImpl<T, *> -> this
  }

private class StateImpl<T, R> : State<T>, CoroutineContext.Key<StateValue<T>> {
  val prompt = Prompt<R>()
}

public suspend fun <T> State<T>.get(): T {
  val stateValue = coroutineContext[impl] ?: error("State $this not set")
  return stateValue.value
}

public suspend fun <T> State<T>.set(value: T) = impl.set(value)

private suspend fun <T, R> StateImpl<T, R>.set(value: T) = prompt.takeSubCont(deleteDelimiter = false) { sk ->
  sk.pushSubContWith(Result.success(Unit), prompt, extraContext = StateValue(value, impl))
}

public suspend inline fun <T> State<T>.modify(f: (T) -> T) = set(f(get()))

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R {
  val state = StateImpl<T, R>()
  return state.prompt.pushPrompt(extraContext = StateValue<T>(value, state)) { state.body() }
}
