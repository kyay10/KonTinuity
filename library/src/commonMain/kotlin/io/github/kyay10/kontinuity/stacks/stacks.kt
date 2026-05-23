package io.github.kyay10.kontinuity.stacks

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

public sealed class StackSuspension(internal var state: State) {
  internal enum class State {
    Pending,
    Available,
    Expired,
  }

  internal class Cont(val stack: Stack) : StackSuspension(State.Pending)

  internal class Initial(val mount: StackMount<*>) : StackSuspension(State.Available)
}

public class StackContinuation<out R>(public val suspension: StackSuspension, public val resumer: R)

public class StackMount<E> {
  @Suppress("UNCHECKED_CAST") internal var state: E = null as E
  internal var stack: Stack? = null

  public fun <R> new(resumer: R): StackContinuation<R> = StackContinuation(StackSuspension.Initial(this), resumer)

  private var asStack: Stack? = null

  context(trampoline: Trampoline)
  internal fun asStack(): Stack =
    asStack
      ?: Continuation(EmptyCoroutineContext) { result ->
          trampoline.resumeIntercepted(stack!!, result.fold({ it }, { it }))
        }
        .let(::Stack)
}

context(_: Locality)
public suspend fun <R> restack(block: suspend context(Locality) StackRestacker.() -> R): R = block(StackRestacker())

public class StackRestacker internal constructor() {
  context(_: Locality)
  public suspend fun <E> mount(
    environment: E,
    mount: StackMount<E>,
    suspension: StackSuspension,
    block: suspend context(Locality) StackRestacker.() -> Nothing,
  ): Nothing {
    require(mount.stack == null)
    require(suspension.state != Expired)
    suspension.state = Expired
    mount.state = environment
    suspension.stack.swap {
      mount.stack = it
      block()
    }
  }

  context(_: Locality)
  public suspend fun <E> dismount(
    mount: StackMount<E>,
    block: suspend context(Locality) StackRestacker.(environment: E, StackSuspension) -> Nothing,
  ): Nothing {
    val stack = mount.stack
    requireNotNull(stack)
    mount.stack = null
    stack.swap { block(mount.state, StackSuspension.Cont(it)) }
  }

  context(_: Locality)
  public suspend fun switchTo(
    suspension: StackSuspension,
    block: suspend context(Locality) StackRestacker.(StackSuspension) -> Nothing,
  ): Nothing {
    require(suspension.state != StackSuspension.State.Expired)
    suspension.state = StackSuspension.State.Expired
    suspension.stack.swap { block(StackSuspension.Cont(it)) }
  }

  context(locality: Locality)
  private val StackSuspension.stack: Stack
    get() =
      when (locality) {
        is Trampoline ->
          when (this) {
            is Initial -> mount.asStack()
            is Cont -> stack
          }
      }

  context(locality: Locality)
  public suspend fun finish(block: suspend context(Locality) () -> Nothing): Nothing {
    when (locality) {
      is Trampoline -> locality.yield()
    }
    block()
  }
}

public fun <E, O> StackMount<E>.new(
  after: suspend context(Locality) (E, O) -> Nothing,
  block: suspend context(Locality) () -> O,
): StackContinuation<suspend context(Locality) () -> Nothing> = new {
  val output = block()
  restack { dismount(this@new) { environment, _ -> finish { after(environment, output) } } }
}

context(locality: Locality)
public suspend fun <E, R> StackMount<E>.resume(
  environment: E,
  continuation: StackContinuation<R>,
  block: suspend context(Locality) (R) -> Nothing,
): Nothing {
  restack { mount(environment, this@resume, continuation.suspension) { finish { block(continuation.resumer) } } }
}

context(locality: Locality)
public suspend fun <E, R> StackMount<E>.suspend(
  resumer: R,
  block: suspend context(Locality) (E, StackContinuation<R>) -> Nothing,
): Nothing {
  restack {
    dismount(this@suspend) { environment, suspension ->
      finish { block(environment, StackContinuation(suspension, resumer)) }
    }
  }
}
