package io.github.kyay10.kontinuity.stacks

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

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

  internal fun asStack(trampoline: Trampoline): Stack =
    asStack
      ?: Continuation(trampoline) { result -> trampoline.resumeIntercepted(stack!!, result.fold({ it }, { it })) }
        .let(::Stack)
}

public suspend fun <R> restack(block: suspend StackRestacker.() -> R): R =
  block(StackRestacker(currentCoroutineContext() as Trampoline))

public class StackRestacker internal constructor(internal val trampoline: Trampoline) {
  public suspend fun <E> mount(
    environment: E,
    mount: StackMount<E>,
    suspension: StackSuspension,
    block: suspend StackRestacker.() -> Nothing,
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

  public suspend fun <E> dismount(
    mount: StackMount<E>,
    block: suspend StackRestacker.(environment: E, StackSuspension) -> Nothing,
  ): Nothing {
    val stack = mount.stack
    requireNotNull(stack)
    mount.stack = null
    stack.swap { block(mount.state, StackSuspension.Cont(it)) }
  }

  public suspend fun switchTo(
    suspension: StackSuspension,
    block: suspend StackRestacker.(StackSuspension) -> Nothing,
  ): Nothing {
    require(suspension.state != StackSuspension.State.Expired)
    suspension.state = StackSuspension.State.Expired
    suspension.stack.swap { block(StackSuspension.Cont(it)) }
  }

  private val StackSuspension.stack: Stack
    get() =
      when (this) {
        is Initial -> mount.asStack(trampoline)
        is Cont -> stack
      }

  private suspend fun Stack.swap(block: suspend (Stack) -> Nothing): Nothing = suspendCoroutineUninterceptedOrReturn {
    try {
      val _ = block.startCoroutineUninterceptedOrReturn(Stack(it), this@swap.frames)
    } catch (e: Throwable) {
      trampoline.resumeIntercepted(this@swap, e)
    }
    COROUTINE_SUSPENDED
  }

  public suspend fun finish(block: suspend () -> Nothing): Nothing {
    suspendCoroutineUninterceptedOrReturn {
      trampoline.yield(it)
      COROUTINE_SUSPENDED
    }
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
