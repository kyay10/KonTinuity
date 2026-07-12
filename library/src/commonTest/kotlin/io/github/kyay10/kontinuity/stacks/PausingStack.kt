package io.github.kyay10.kontinuity.stacks

import io.github.kyay10.regional.Regional

@Regional
fun interface PausingBlock<in local> {
  context(_: Locality<local2>)
  suspend operator fun <local2 : local> invoke(pause: suspend context(Locality<local2>) () -> Unit): Unit = _impl(pause)
}

// Plugin only works with named functions, not constructors
class PausingStack<local> private constructor(private val block: PausingBlock<local>) {
  companion object {
    operator fun <local> invoke(block: PausingBlock<local>) = PausingStack(block)
  }

  private inner class WithMounted<mounted : local> {
    private val mount: StackMount<local, LocalFun1<Boolean, Nothing, *>, mounted, local> = StackMount()
    private var continuation: StackContinuation<LocalFun0<Nothing, *>, out mounted, mounted>? =
      mount.new({ environment, _ -> environment.fix()(true) }) {
        block pause@{
          merge {
            mount.suspend(LocalFun0 { raise(Unit) }) { environment, c ->
              continuation = c
              environment.fix()(false)
            }
          }
        }
      }

    context(_: Locality<local2>)
    suspend fun <local2 : local> progress(): Boolean = merge {
      mount.resume(
        LocalFun1 { raise(it) },
        (continuation ?: return@merge true).also { continuation = null },
      ) {
        it.fix()()
      }
    }
  }

  private val withMounted = WithMounted<local>()

  context(_: Locality<local2>)
  suspend fun <local2 : local> progress(): Boolean = withMounted.progress()
}
