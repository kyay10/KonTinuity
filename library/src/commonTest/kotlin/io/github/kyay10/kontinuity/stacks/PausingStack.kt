package io.github.kyay10.kontinuity.stacks

import arrow.core.raise.merge

class PausingStack
private constructor(
  block: suspend (pause: suspend () -> Unit) -> Unit,
  private val mount: StackMount<suspend (Boolean) -> Nothing>,
) {

  private var continuation: StackContinuation<suspend (Boolean) -> Nothing, suspend () -> Nothing>? =
    mount.new({ exit, _ -> exit(true) }) {
      block pause@{
        merge {
          mount.suspend(suspend { raise(Unit) }) { exit, continuation ->
            this@PausingStack.continuation = continuation
            exit(false)
          }
        }
      }
    }

  suspend fun progress(): Boolean = merge {
    mount.resume({ raise(it) }, (continuation ?: return true).also { continuation = null }) { it() }
  }

  companion object {
    suspend operator fun invoke(block: suspend (pause: suspend () -> Unit) -> Unit): PausingStack =
      PausingStack(block, StackMount())
  }
}
