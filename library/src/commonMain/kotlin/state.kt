public class NestableState<T, R> : Reader<T> {
  internal val prompt = Prompt<R>()
}

public typealias State<T> = NestableState<T, *>

public suspend fun <T> State<T>.set(value: T) = setImpl(value)

// Introduces an existential type R
private suspend fun <T, R> NestableState<T, R>.setImpl(value: T) = prompt.takeSubCont(deleteDelimiter = false) { sk ->
  sk.pushSubContWith(Result.success(Unit), prompt, extraContext = context(value))
}

public suspend inline fun <T> State<T>.modify(f: (T) -> T) = set(f(get()))

public suspend fun <T, R> runState(value: T, body: suspend NestableState<T, R>.() -> R): R {
  val state = NestableState<T, R>()
  return state.pushState(value) { state.body() }
}

public suspend fun <T, R> NestableState<T, R>.pushState(value: T, body: suspend () -> R): R = prompt.pushPrompt(context(value)) { body() }
