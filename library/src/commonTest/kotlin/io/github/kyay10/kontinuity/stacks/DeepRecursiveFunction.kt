package io.github.kyay10.kontinuity.stacks

interface DeepRecursiveFunction<T, R, in local> {
  context(_: Locality<local2>)
  suspend fun <local2 : local> DeepRecursiveScope<T, R, local2>.block(t: T): R
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

object LocalUnit : Local<Unit, Any?>

context(_: Locality<local>)
suspend fun <R, local> onFreshStack(block: suspend context(Locality<local>) () -> R): R =
  merge(
    object : MergeFun<R, local> {
      context(_: Locality<local2>, _: Raise<R, local2>)
      override suspend fun <local2 : local> invoke(): R {
        val mount = StackMount<local2, Unit, local2, local2>()
        val continuation = mount.new(LocalUnit)
        // : StackContinuation<Unit>_{mount.mounted}
        restack(
          object : RestackerFun0<Nothing, local2> {
            context(_: Locality<local>)
            override suspend fun <local : local2> StackRestacker<local2, local>.invoke() =
              mount(
                LocalUnit,
                mount,
                continuation.suspension,
                object : RestackerFun0<Nothing, local2> {
                  context(_: Locality<local>)
                  override suspend fun <local : local2> StackRestacker<local2, local>.invoke() = finish {
                    raise(block())
                  }
                },
              )
          }
        )
      }
    }
  )
