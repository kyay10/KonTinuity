package io.github.kyay10.kontinuity

public data class StateValue<T>(public var value: T)

public typealias State<T> = Reader<StateValue<T>>

public suspend fun <T> State<T>.set(value: T) {
  ask().value = value
}

public suspend fun <T> State<T>.get() = ask().value

public suspend inline fun <T> State<T>.modify(f: (T) -> T) = set(f(get()))

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R = runReader(StateValue(value), { copy() }, body)