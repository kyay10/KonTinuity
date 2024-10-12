public data class StateValue<T>(public var value: T)

public typealias State<T> = Reader<StateValue<T>>

public suspend fun <T> State<T>.set(value: T) {
  ask().value = value
}

public suspend fun <T> State<T>.get() = ask().value

public suspend inline fun <T> State<T>.modify(f: (T) -> T) = set(f(get()))

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R = with(State<T>()) {
  pushState(value) { body() }
}

public suspend fun <T, R> State<T>.pushState(value: T, body: suspend () -> R): R =
  pushReader(StateValue(value), { copy() }, body)