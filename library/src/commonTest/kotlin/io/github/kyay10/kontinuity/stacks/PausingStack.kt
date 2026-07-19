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
    private val mount: StackMount<local, LocalFun1Of<Boolean, Nothing>, mounted, local> = StackMount()
    private var continuation: StackContinuation<LocalFun0Of<Nothing>, out mounted, mounted>? =
      mount.new({ environment, _ -> environment(true) }) {
        block pause@{
          merge {
            mount.suspend(LocalFun0 { raise(Unit) }) { environment, c ->
              continuation = c
              environment(false)
            }
          }
        }
      }

    context(_: Locality<local2>)
    suspend fun <local2 : local> progress(): Boolean = merge {
      val f = LocalFun1 { it: Boolean -> raise(it) }
      mount.resume(
        f,
        (continuation ?: return@merge true).also { continuation = null },
      ) { it() }
    }
  }

  private val withMounted = WithMounted<local>()

  context(_: Locality<local2>)
  suspend fun <local2 : local> progress(): Boolean = withMounted.progress()
}
