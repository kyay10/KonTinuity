package io.github.kyay10.kontinuity.stacks

import arrow.core.raise.merge

class PausingStack(block: suspend context(Locality) (pause: suspend context(Locality) () -> Unit) -> Unit) {
  private val mount: StackMount<suspend context(Locality) (Boolean) -> Nothing> = StackMount()

  private var continuation: StackContinuation<suspend context(Locality) () -> Nothing>? =
    mount.new({ exit, _ -> exit(true) }) {
      block pause@{
        merge {
          mount.suspend<suspend context(Locality) (Boolean) -> Nothing, suspend context(Locality) () -> Nothing>({
            raise(Unit)
          }) { exit, continuation ->
            this@PausingStack.continuation = continuation
            exit(false)
          }
        }
      }
    }

  context(_: Locality)
  suspend fun progress(): Boolean = merge {
    mount.resume({ raise(it) }, (continuation ?: return true).also { continuation = null }) { it() }
  }
}
