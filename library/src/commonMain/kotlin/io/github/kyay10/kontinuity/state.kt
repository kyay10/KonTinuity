package io.github.kyay10.kontinuity

public data class StateValue<T>(public var value: T)

public typealias State<T> = Reader<StateValue<T>>

public fun <T> State<T>.set(value: T) {
  ask().value = value
}

public fun <T> State<T>.get(): T = ask().value

public inline fun <T> State<T>.modify(f: (T) -> T): Unit = set(f(get()))

context(_: MultishotScope)
public suspend fun <T, R> runState(value: T, body: suspend context(MultishotScope) State<T>.() -> R): R =
  runReader(StateValue(value), { copy() }, body)