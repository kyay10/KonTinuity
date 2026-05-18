package io.github.kyay10.kontinuity.stacks

import arrow.core.raise.merge
import io.github.kyay10.kontinuity.Handler
import io.github.kyay10.kontinuity.SubContFinal
import io.github.kyay10.kontinuity.handle
import io.github.kyay10.kontinuity.useOnce

typealias Request<E> = suspend (E) -> Nothing

class StackSuspension<in E> internal constructor(internal val cont: SubContFinal<Nothing, Request<E>>) {
  enum class State {
    Pending,
    Available,
    Expired,
  }

  internal var state = State.Pending
}

class StackContinuation<in E, out R>(val suspension: StackSuspension<E>, val resumer: R)

fun <E, T> ignoreInput(
  continuation: StackContinuation<E, suspend () -> Nothing>
): StackContinuation<E, suspend (T) -> Nothing> =
  StackContinuation(continuation.suspension) { _ -> continuation.resumer() }

class StackMount<E>
private constructor(
  internal val handler: Handler<suspend (E) -> Nothing>,
  internal val initialSuspension: StackSuspension<E>,
) {
  fun <R> new(resumer: R): StackContinuation<E, R> = StackContinuation(initialSuspension, resumer)

  internal var isMounted = false

  companion object {
    // this hacky workaround is needed because I explicitly don't allow new Handlers
    // to be created willy-nilly. Instead, they arise naturally from `handle`.
    // There's likely some lateinit alternative I could explore, but alas, this'll do.
    suspend operator fun <E> invoke(): StackMount<E> = merge {
      val req = handle<Request<E>> { useOnce { raise(StackMount(this, StackSuspension(it))) } }
      // This can never happen since `useOnce` immediately `raise`s out
      // I wonder if I can convince the type system of that somehow?
      error("can't handle $req")
    }
  }
}

suspend fun <R> restack(block: suspend StackRestacker.() -> R): R = block(StackRestacker())

class StackRestacker internal constructor() {
  suspend fun <E> mount(
    environment: E,
    mount: StackMount<E>,
    suspension: StackSuspension<E>,
    block: suspend StackRestacker.() -> Nothing,
  ): Nothing {
    require(!mount.isMounted)
    require(suspension.state != StackSuspension.State.Expired)
    mount.isMounted = true
    suspension.state = StackSuspension.State.Expired
    suspension.cont.locally { block() }(environment)
  }

  suspend fun <E> dismount(
    mount: StackMount<E>,
    block: suspend StackRestacker.(environment: E, StackSuspension<E>) -> Nothing,
  ): Nothing {
    require(mount.isMounted)
    mount.isMounted = false
    mount.handler.useOnce { cont -> { e -> block(e, StackSuspension(cont)) } }
  }

  suspend fun <E1, E2> switchTo(
    suspension: StackSuspension<E1>,
    block: suspend StackRestacker.(StackSuspension<E2>) -> Nothing,
  ): Nothing {
    require(suspension.state != StackSuspension.State.Expired)
    suspension.state = StackSuspension.State.Expired
    TODO() // not sure what this corresponds to?
  }

  suspend fun finish(block: suspend () -> Nothing): Nothing {
    block()
  }
}

fun <E, O> StackMount<E>.new(
  after: suspend (E, O) -> Nothing,
  block: suspend () -> O,
): StackContinuation<E, suspend () -> Nothing> = new {
  val output = block()
  restack { dismount(this@new) { environment, _ -> finish { after(environment, output) } } }
}

suspend fun <E, R> StackMount<E>.resume(
  environment: E,
  continuation: StackContinuation<E, R>,
  block: suspend (R) -> Nothing,
): Nothing {
  restack { mount(environment, this@resume, continuation.suspension) { finish { block(continuation.resumer) } } }
}

suspend fun <E, R> StackMount<E>.suspend(
  resumer: R,
  block: suspend (E, StackContinuation<E, R>) -> Nothing,
): Nothing {
  restack {
    dismount(this@suspend) { environment, suspension ->
      finish { block(environment, StackContinuation(suspension, resumer)) }
    }
  }
}
