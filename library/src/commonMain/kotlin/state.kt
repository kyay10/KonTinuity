import arrow.atomic.Atomic
import arrow.atomic.update

public typealias State<T> = ForkingReader<Atomic<T>>

public fun <T> State(): State<T> = ForkingReader { Atomic(get()) }

public suspend fun <T> State<T>.set(value: T) = ask().set(value)
public suspend fun <T> State<T>.get() = ask().get()

public suspend inline fun <T> State<T>.modify(f: (T) -> T) = ask().update(f)

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R {
  val state = State<T>()
  return state.pushState(value) { state.body() }
}

public suspend fun <T, R> State<T>.pushState(value: T, body: suspend () -> R): R =
  pushReader(Atomic(value), body)