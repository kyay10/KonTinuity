public typealias StackState<T> = State<List<T>>

public suspend fun <T> StackState<T>.set(value: T) {
  ask().value += value
}

public suspend fun <T> StackState<T>.get() = ask().value.last()

public suspend inline fun <T> StackState<T>.modify(f: (T) -> T) {
  val state = ask()
  state.value += f(state.value.last())
}

public suspend fun <T, R> runStackState(value: T, body: suspend StackState<T>.() -> R): R = with(StackState<T>()) {
  pushStackState(value) { body() }
}

public suspend fun <T, R> StackState<T>.pushStackState(value: T, body: suspend () -> R): R =
  pushState(listOf(value), body)

public suspend fun <T, R> StackState<T>.pushStackStateWithCurrent(value: T, body: suspend () -> R): R {
  val current = askOrNull()?.value ?: emptyList()
  return pushState(current + value, body)
}