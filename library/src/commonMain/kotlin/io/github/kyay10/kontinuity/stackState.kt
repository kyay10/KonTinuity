package io.github.kyay10.kontinuity

public typealias StackState<T> = State<List<T>>

public suspend operator fun <T> StackState<T>.plusAssign(value: T) = modify { it + value }
public suspend fun <T> StackState<T>.getLast() = ask().value.last()

public suspend inline fun <T> StackState<T>.modifyLast(f: (T) -> T) = plusAssign(f(getLast()))

public suspend fun <T, R> runStackState(value: T, body: suspend StackState<T>.() -> R): R =
  runState(listOf(value), body)

public suspend fun <T, R> StackState<T>.pushStackState(value: T, body: suspend () -> R): R =
  pushState(listOf(value), body)

public suspend fun <T, R> StackState<T>.pushStackStateWithCurrent(value: T, body: suspend () -> R): R {
  val current = askOrNull()?.value ?: emptyList()
  return pushState(current + value, body)
}