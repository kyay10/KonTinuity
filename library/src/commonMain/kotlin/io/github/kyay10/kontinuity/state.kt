package io.github.kyay10.kontinuity

public data class StateValue<T>(public var value: T)

public typealias State<T> = Reader<StateValue<T>>

context(s: State<T>)
public fun <T> set(value: T) {
  ask().value = value
}

context(s: State<T>)
public fun <T> get(): T = ask().value

context(s: State<T>)
public inline fun <T> modify(f: (T) -> T): Unit = set(f(get()))

public suspend fun <T, R> MultishotScope.runState(value: T, body: suspend context(State<T>) MultishotScope.() -> R): R =
  runReader(StateValue(value), { copy() }, body)