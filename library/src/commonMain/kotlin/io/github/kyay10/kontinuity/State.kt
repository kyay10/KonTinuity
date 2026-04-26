package io.github.kyay10.kontinuity

public interface State<S> {
  public var value: S
}

public class ForkState<S> internal constructor(private var state: S, private val fork: S.() -> S) :
  State<S>, Finalize<S>() {
  private class ForkOnFirstRead(val state: Any?)

  override fun onSuspend(): S = state

  @Suppress("UNCHECKED_CAST")
  override fun onResume(state: S, isFinal: Boolean) {
    this.state = if (isFinal || state is ForkOnFirstRead) state else ForkOnFirstRead(state) as S
  }

  @Suppress("UNCHECKED_CAST")
  private inline fun valueOrElse(onFork: S.() -> S): S = state.let {
    if (it is ForkOnFirstRead) onFork(it.state as S) else it
  }

  override var value: S
    get() = valueOrElse { fork().also { value = it } }
    set(value) {
      state = value
    }

  public val unsafeValue: S
    get() = valueOrElse { this }
}

private class SimpleState<S>(override var value: S) : State<S>, Finalize<S>() {
  override fun onSuspend(): S = value

  @Suppress("UNCHECKED_CAST")
  override fun onResume(state: S, isFinal: Boolean) {
    value = state
  }
}

public suspend fun <T, R> runState(value: T, fork: T.() -> T, body: suspend ForkState<T>.() -> R): R =
  with(ForkState(value, fork)) { finalize { body() } }

public suspend fun <T, R> runState(value: T, body: suspend State<T>.() -> R): R =
  with(SimpleState(value)) { finalize { body() } }

public typealias Reader<S> = State<out S>

public typealias ForkReader<S> = ForkState<out S>

@ResetDsl
public suspend fun <T, R> runReader(value: T, fork: T.() -> T, body: suspend ForkReader<T>.() -> R): R =
  runState(value, fork, body)

@ResetDsl public suspend fun <T, R> runReader(value: T, body: suspend Reader<T>.() -> R): R = runState(value, body)

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend fun <S : Stateful<S>, R> runReader(value: S, body: suspend Reader<S>.() -> R): R =
  runReader(value, Stateful<S>::fork, body)
