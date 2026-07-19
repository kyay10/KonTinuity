package io.github.kyay10.kontinuity.stacks

import io.github.kyay10.regional.Regional

@Regional
fun interface DeepRecursiveFunction<T, R, in local> {
  context(_: Locality<local2>)
  suspend fun <local2 : local> DeepRecursiveScope<T, R, local2>.block(t: T): R = _impl(t)
}

context(_: Locality<local2>, scope: DeepRecursiveScope<T, R, local2>)
private suspend fun <T, R, local, local2 : local> DeepRecursiveFunction<T, R, local>.block(t: T): R = scope.block(t)

sealed class DeepRecursiveScope<T, R, local> {
  context(_: Locality<local>)
  abstract suspend fun callRecursive(value: T): R

  context(_: Locality<local2>)
  abstract suspend fun <U, S, local2 : local> DeepRecursiveFunction<U, S, local2>.callRecursive(value: U): S
}

context(_: Locality<local>)
suspend operator fun <T, R, local> DeepRecursiveFunction<T, R, local>.invoke(value: T): R =
  with(DeepRecursiveScopeImpl(this)) { block(value) }

private class DeepRecursiveScopeImpl<T, R, local>(private val function: DeepRecursiveFunction<T, R, local>) :
  DeepRecursiveScope<T, R, local>() {
  context(_: Locality<local>)
  override suspend fun callRecursive(value: T): R = onFreshStack { function.block(value) }

  context(_: Locality<local2>)
  override suspend fun <U, S, local2 : local> DeepRecursiveFunction<U, S, local2>.callRecursive(value: U): S =
    DeepRecursiveScopeImpl(this).callRecursive(value)
}

context(_: Locality<local>)
suspend fun <R, local> onFreshStack(block: suspend context(Locality<local>) () -> R): R = merge {
  val mount = StackMount<_, Global<Unit>>()
  val continuation = mount.new<Global<Unit>>(Unit)
  // : StackContinuation<Unit>_{mount.mounted}
  restack { mount(Unit, mount, continuation.suspension) { finish { raise(block()) } } }
}

context(_: Locality<local>)
fun <local, E> StackMount(): StackMount<local, E, local, local> = StackMount<_, _, _, _>()
