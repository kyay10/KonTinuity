package io.github.kyay10.kontinuity.stacks

import io.github.kyay10.kontinuity.Handler
import io.github.kyay10.kontinuity.State
import io.github.kyay10.kontinuity.SubContFinal
import io.github.kyay10.kontinuity.handle
import io.github.kyay10.kontinuity.runState
import io.github.kyay10.kontinuity.useOnce
import io.github.kyay10.kontinuity.yieldToTrampoline
import kotlin.properties.Delegates

sealed class StackSuspension(internal val mount: StackMount<*>) {
  enum class State {
    Pending,
    Available,
    Expired,
  }

  internal var state = State.Pending

  internal class Initial(mount: StackMount<*>) : StackSuspension(mount)

  internal class Cont(val cont: SubContFinal<Nothing, Nothing>, mount: StackMount<*>) : StackSuspension(mount)
}

class StackContinuation<out R>(val suspension: StackSuspension, val resumer: R)

fun <T> ignoreInput(continuation: StackContinuation<suspend () -> Nothing>): StackContinuation<suspend (T) -> Nothing> =
  StackContinuation(continuation.suspension) { _ -> continuation.resumer() }

// TODO support multiple stacks that all refer to StackMount
class StackMount<E> {
  internal lateinit var state: State<E>
  internal var handler: Handler<Nothing> by Delegates.notNull()

  fun <R> new(resumer: R): StackContinuation<R> = StackContinuation(StackSuspension.Initial(this), resumer)

  internal var isMounted = false
}

suspend fun <R> restack(block: suspend StackRestacker.() -> R): R = block(StackRestacker())

class StackRestacker internal constructor() {
  suspend fun <E> mount(
    environment: E,
    mount: StackMount<E>,
    suspension: StackSuspension,
    block: suspend StackRestacker.() -> Nothing,
  ): Nothing {
    require(!mount.isMounted)
    require(suspension.state != StackSuspension.State.Expired)
    mount.isMounted = true
    suspension.state = StackSuspension.State.Expired
    when (suspension) {
      is StackSuspension.Initial ->
        runState(environment) {
          handle {
            // As a performance optimization, `handle` doesn't unwind the stack,
            // but we can force it.
            // this is needed for DeepRecursiveFunction to work
            yieldToTrampoline()
            mount.state = this@runState
            mount.handler = this
            block()
          }
        }
      is StackSuspension.Cont -> {
        mount.state.value = environment
        suspension.cont.locally { block() }
      }
    }
  }

  suspend fun <E> dismount(
    mount: StackMount<E>,
    block: suspend StackRestacker.(environment: E, StackSuspension) -> Nothing,
  ): Nothing {
    require(mount.isMounted)
    mount.isMounted = false
    mount.handler.useOnce { cont -> block(mount.state.value, StackSuspension.Cont(cont, mount)) }
  }

  // This could be made a primitive for Handler as an optimization
  suspend fun switchTo(
    suspension: StackSuspension,
    block: suspend StackRestacker.(StackSuspension) -> Nothing,
  ): Nothing = suspension.mount.switchTo(suspension, block)

  private suspend fun <E> StackMount<E>.switchTo(
    suspension: StackSuspension,
    block: suspend StackRestacker.(StackSuspension) -> Nothing,
  ): Nothing = dismount(this) { e, suspension2 -> mount(e, this@switchTo, suspension) { block(suspension2) } }

  suspend fun finish(block: suspend () -> Nothing): Nothing {
    block()
  }
}

fun <E, O> StackMount<E>.new(
  after: suspend (E, O) -> Nothing,
  block: suspend () -> O,
): StackContinuation<suspend () -> Nothing> = new {
  val output = block()
  restack { dismount(this@new) { environment, _ -> finish { after(environment, output) } } }
}

suspend fun <E, R> StackMount<E>.resume(
  environment: E,
  continuation: StackContinuation<R>,
  block: suspend (R) -> Nothing,
): Nothing {
  restack { mount(environment, this@resume, continuation.suspension) { finish { block(continuation.resumer) } } }
}

suspend fun <E, R> StackMount<E>.suspend(
  resumer: R,
  block: suspend (E, StackContinuation<R>) -> Nothing,
): Nothing {
  restack {
    dismount(this@suspend) { environment, suspension ->
      finish { block(environment, StackContinuation(suspension, resumer)) }
    }
  }
}
