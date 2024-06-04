import arrow.atomic.Atomic
import arrow.atomic.update
import arrow.atomic.value

public typealias StackState<T> = Reader<Atomic<List<T>>>

public fun <T> StackState(): StackState<T> = Reader()

public suspend fun <T> StackState<T>.set(value: T) = ask().update { it + value }
public suspend fun <T> StackState<T>.get() = ask().value.last()

public suspend inline fun <T> StackState<T>.modify(f: (T) -> T) = ask().update { it + f(it.last()) }

public suspend fun <T, R> runStackState(value: T, body: suspend StackState<T>.() -> R): R {
  val state = StackState<T>()
  return state.pushStackState(value) { state.body() }
}

public suspend fun <T, R> StackState<T>.pushStackState(value: T, body: suspend () -> R): R =
  pushReader(Atomic(listOf(value)), body)

public suspend fun <T, R> StackState<T>.pushStackStateWithCurrent(value: T, body: suspend () -> R): R {
  val current = askOrNull()?.value ?: emptyList()
  return pushReader(Atomic(current + value), body)
}