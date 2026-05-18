package io.github.kyay10.kontinuity.stacks

import arrow.core.raise.merge

class DeepRecursiveFunction<T, R>(internal val block: suspend DeepRecursiveScope<T, R>.(T) -> R)

sealed class DeepRecursiveScope<T, R> {
  abstract suspend fun callRecursive(value: T): R

  abstract suspend fun <U, S> DeepRecursiveFunction<U, S>.callRecursive(value: U): S
}

suspend operator fun <T, R> DeepRecursiveFunction<T, R>.invoke(value: T): R = DeepRecursiveScopeImpl(this).block(value)

private class DeepRecursiveScopeImpl<T, R>(private val function: DeepRecursiveFunction<T, R>) :
  DeepRecursiveScope<T, R>() {
  override suspend fun callRecursive(value: T): R = onFreshStack { function.block(this, value) }

  override suspend fun <U, S> DeepRecursiveFunction<U, S>.callRecursive(value: U): S =
    DeepRecursiveScopeImpl(this).callRecursive(value)
}

suspend fun <R> onFreshStack(block: suspend () -> R): R = merge {
  val mount = StackMount<Unit>()
  // : StackMount<local, Unit>
  val continuation = mount.new(Unit)
  // : StackContinuation<Unit>_{mount.mounted}
  restack { mount(Unit, mount, continuation.suspension) { finish { raise(block()) } } }
}
