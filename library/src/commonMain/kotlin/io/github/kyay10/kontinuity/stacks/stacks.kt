package io.github.kyay10.kontinuity.stacks

public class StackSuspension internal constructor(internal val cont: SubCont) {
  internal enum class State {
    Pending,
    Available,
    Expired,
  }

  internal var state = State.Pending
}

public class StackContinuation<out R>(public val suspension: StackSuspension, public val resumer: R)

public fun <T> ignoreInput(
  continuation: StackContinuation<suspend () -> Nothing>
): StackContinuation<suspend (T) -> Nothing> =
  StackContinuation(continuation.suspension) { _ -> continuation.resumer() }

public class StackMount<E> {
  @Suppress("UNCHECKED_CAST") internal var state: E = null as E
  internal val handler: Handler = Handler()

  public fun <R> new(resumer: R): StackContinuation<R> = StackContinuation(StackSuspension(SubCont(handler, Stack(handler))), resumer)

  internal var isMounted = false
}

public suspend fun <R> restack(block: suspend StackRestacker.() -> R): R = block(StackRestacker())

public class StackRestacker internal constructor() {
  public suspend fun <E> mount(
    environment: E,
    mount: StackMount<E>,
    suspension: StackSuspension,
    block: suspend StackRestacker.() -> Nothing,
  ): Nothing {
    require(!mount.isMounted)
    require(suspension.state != StackSuspension.State.Expired)
    mount.isMounted = true
    suspension.state = StackSuspension.State.Expired
    mount.state = environment
    suspension.cont.locally { block() }
  }

  public suspend fun <E> dismount(
    mount: StackMount<E>,
    block: suspend StackRestacker.(environment: E, StackSuspension) -> Nothing,
  ): Nothing {
    require(mount.isMounted)
    mount.isMounted = false
    mount.handler.use { cont -> block(mount.state, StackSuspension(cont).apply { state = StackSuspension.State.Pending }) }
  }

  public suspend fun switchTo(
    suspension: StackSuspension,
    block: suspend StackRestacker.(StackSuspension) -> Nothing,
  ): Nothing {
    require(suspension.state != StackSuspension.State.Expired)
    suspension.state = StackSuspension.State.Expired
    TODO() // not sure what this corresponds to?
  }

  public suspend fun finish(block: suspend () -> Nothing): Nothing {
    yieldToTrampoline()
    block()
  }
}

public fun <E, O> StackMount<E>.new(
  after: suspend (E, O) -> Nothing,
  block: suspend () -> O,
): StackContinuation<suspend () -> Nothing> = new {
  val output = block()
  restack { dismount(this@new) { environment, _ -> finish { after(environment, output) } } }
}

public suspend fun <E, R> StackMount<E>.resume(
  environment: E,
  continuation: StackContinuation<R>,
  block: suspend (R) -> Nothing,
): Nothing {
  restack { mount(environment, this@resume, continuation.suspension) { finish { block(continuation.resumer) } } }
}

public suspend fun <E, R> StackMount<E>.suspend(
  resumer: R,
  block: suspend (E, StackContinuation<R>) -> Nothing,
): Nothing {
  restack {
    dismount(this@suspend) { environment, suspension ->
      finish { block(environment, StackContinuation(suspension, resumer)) }
    }
  }
}
