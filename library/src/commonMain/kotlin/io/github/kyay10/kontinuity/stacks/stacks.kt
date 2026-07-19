package io.github.kyay10.kontinuity.stacks

import io.github.kyay10.highkt.Constructor
import io.github.kyay10.highkt.K
import io.github.kyay10.highkt.K2
import io.github.kyay10.kontinuity.stacks.StackSuspension.Cont
import io.github.kyay10.kontinuity.stacks.StackSuspension.Initial
import io.github.kyay10.regional.Regional
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

public sealed class StackSuspension<out resumption, in `this`>(internal var state: State) {
  internal enum class State {
    Pending,
    Available,
    Expired,
  }

  internal class Cont<out resumption, in `this`>(val stack: Stack<resumption>) :
    StackSuspension<resumption, `this`>(State.Pending)

  internal class Initial<resumption, in `this`>(val mount: StackMount<*, *, resumption, *>) :
    StackSuspension<resumption, `this`>(State.Available)
}

public class StackContinuation<out R, resumption, in `this`>(
  public val suspension: StackSuspension<resumption, `this`>,
  public val resumer: Local<R, resumption>,
)

public typealias LocalFun0<R, local> = suspend context(Locality<local>) () -> R

public fun <R, local> LocalFun0(block: LocalFun0<R, local>): LocalFun0<R, local> = block

context(_: Locality<local>)
public suspend operator fun <R, local> LocalFun0<R, local>.invoke(): R = this()

public typealias LocalFun0Of<R> = K<Constructor<LocalFun0<*, *>>, R>

public typealias LocalFun1<T, R, local> = suspend context(Locality<local>) (T) -> R

public fun <T, R, local> LocalFun1(block: LocalFun1<T, R, local>): LocalFun1<T, R, local> = block

context(_: Locality<local>)
public suspend operator fun <T, R, local> LocalFun1<T, R, local>.invoke(t: T): R = this(t)

public typealias LocalFun1Of<T, R> = K2<Constructor<LocalFun1<*, *, *>>, T, R>

public fun <T, resumption, continuation> ignoreInput(
  continuation: StackContinuation<LocalFun0Of<Nothing>, resumption, continuation>
): StackContinuation<LocalFun1Of<T, Nothing>, resumption, continuation> =
  StackContinuation(continuation.suspension, LocalFun1<_, _, resumption> { _ -> continuation.resumer() })

public class StackMount<arena, E, mounted : arena, in `this`> {
  @Suppress("UNCHECKED_CAST") internal var state: Local<E, *>? = null
  internal var stack: Stack<arena>? = null

  public fun <R> new(resumer: Local<R, mounted>): StackContinuation<R, out mounted, mounted> =
    StackContinuation(Initial(this), resumer)

  private var asStack: Stack<mounted>? = null

  context(trampoline: Trampoline<*>)
  internal fun asStack(): Stack<mounted> =
    asStack
      ?: Continuation(EmptyCoroutineContext) { result ->
          trampoline.resumeIntercepted(stack!!, result.fold({ it }, { it }))
        }
        .let(::Stack)
}

@Regional
public fun interface RestackerFun0<out R, in resumption> {
  context(_: Locality<local>)
  public suspend operator fun <local : resumption> StackRestacker<resumption, local>.invoke(): R = _impl()
}

context(_: Locality<local>, restacker: StackRestacker<resumption, local>)
public suspend operator fun <R, resumption, local : resumption> RestackerFun0<R, resumption>.invoke(): R = restacker()

context(_: Locality<local>)
public suspend fun <R, local> restack(block: RestackerFun0<R, local>): R =
  with(StackRestacker<local, local>()) { block() }

public class StackRestacker<out resumption, in `this` : resumption> internal constructor()

context(_: Locality<local>)
public suspend fun <resumption : arena, local : resumption, arena, E, that : mounted, mounted : arena> StackRestacker<
  resumption,
  local,
>
  .mount(
  environment: Local<E, resumption>,
  mount: StackMount<arena, E, mounted, local>,
  suspension: StackSuspension<that, mounted>,
  block: RestackerFun0<Nothing, that>,
): Nothing {
  require(mount.stack == null)
  require(suspension.state != Expired)
  suspension.state = StackSuspension.State.Expired
  mount.state = environment
  suspension.stack.swap {
    mount.stack = it
    block()
  }
}

@Regional
public fun interface DismountFun<in E, in S, in arena> {
  context(_: Locality<local>)
  public suspend operator fun <local : environment, environment : arena> StackRestacker<environment, local>.invoke(
    environment: Local<E, environment>,
    s: S,
  ): Nothing = _impl(environment, s)
}

context(_: Locality<local>, restacker: StackRestacker<environment, local>)
public suspend operator fun <E, S, arena, local : environment, environment : arena> DismountFun<E, S, arena>.invoke(
  environment: Local<E, environment>,
  s: S,
): Nothing = restacker(environment, s)

context(_: Locality<local>)
public suspend fun <resumption : mounted, local : resumption, arena, E, mounted : arena> StackRestacker<
  resumption,
  local,
>
  .dismount(
  mount: StackMount<arena, E, mounted, local>,
  block: DismountFun<E, StackSuspension<resumption, mounted>, arena>,
): Nothing {
  val stack = mount.stack
  requireNotNull(stack)
  mount.stack = null
  @Suppress("UNCHECKED_CAST") stack.swap { block(mount.state as Local<E, arena>, Cont(it)) }
}

@Regional
public fun interface SwitchToFun<in A, in resumption> {
  context(_: Locality<local>)
  public suspend operator fun <local : resumption> StackRestacker<resumption, local>.invoke(a: A): Nothing = _impl(a)
}

context(_: Locality<local>, restacker: StackRestacker<resumption, local>)
public suspend operator fun <A, resumption, local : resumption> SwitchToFun<A, resumption>.invoke(a: A): Nothing =
  restacker(a)

context(_: Locality<local>)
public suspend fun <resumption : suspension, local : resumption, that, suspension> StackRestacker<resumption, local>
  .switchTo(
  suspension: StackSuspension<that, suspension>,
  block: SwitchToFun<StackSuspension<resumption, suspension>, that>,
): Nothing {
  require(suspension.state != StackSuspension.State.Expired)
  suspension.state = StackSuspension.State.Expired
  suspension.stack.swap { block(Cont(it)) }
}

context(locality: Locality<local>)
public suspend fun <resumption, local : resumption> StackRestacker<resumption, local>.finish(
  block: suspend context(Locality<resumption>) () -> Nothing
): Nothing {
  when (locality) {
    is Trampoline -> locality.yield()
  }
  block()
}

context(locality: Locality<*>)
internal val <resumption> StackSuspension<resumption, *>.stack: Stack<resumption>
  get() =
    when (locality) {
      is Trampoline ->
        when (this) {
          is Initial -> mount.asStack()
          is Cont -> stack
        }
    }

context(locality: Locality<local>, _: StackRestacker<resumption, local>)
internal suspend fun <resumption, local : resumption, that> Stack<that>.swap(
  block: suspend context(Locality<that>) StackRestacker<that, that>.(Stack<local>) -> Nothing
): Nothing =
  when (locality) {
    is Trampoline -> locality.swap(this@swap) { with(StackRestacker<that, that>()) { block(it) } }
  }

@Regional
public fun interface AfterFun<in E, in O, in arena> {
  context(_: Locality<environment>)
  public suspend operator fun <environment : arena> invoke(environment: Local<E, environment>, o: O): Nothing =
    _impl(environment, o)
}

public fun <arena, E, O, mounted : arena> StackMount<arena, E, mounted, mounted>.new(
  after: AfterFun<E, O, arena>,
  block: suspend context(Locality<mounted>) () -> O,
): StackContinuation<LocalFun0Of<Nothing>, out mounted, mounted> =
  new(
    LocalFun0 {
      val output = block()
      restack { dismount(this@new) { environment, _ -> finish { after(environment, output) } } }
    }
  )

@Regional
public fun interface ResumeFun<in R, in mounted> {
  context(_: Locality<resumption>)
  public suspend operator fun <resumption : mounted> invoke(resumption: Local<R, resumption>): Nothing =
    _impl(resumption)
}

context(_: Locality<local>)
public suspend fun <arena, E, R, mounted : arena, resumption : mounted, local : arena> StackMount<
  arena,
  E,
  mounted,
  local,
>
  .resume(
  environment: Local<E, local>,
  continuation: StackContinuation<R, resumption, mounted>,
  block: ResumeFun<R, mounted>,
): Nothing {
  restack { mount(environment, this@resume, continuation.suspension) { finish { block(continuation.resumer) } } }
}

@Regional
public fun interface SuspendFun<in E, in C, in arena> {
  context(_: Locality<environment>)
  public suspend operator fun <environment : arena> invoke(environment: Local<E, environment>, c: C): Nothing =
    _impl(environment, c)
}

context(locality: Locality<local>)
public suspend fun <arena, E, R, mounted : arena, local : mounted> StackMount<arena, E, mounted, local>.suspend(
  resumer: Local<R, local>,
  block: SuspendFun<E, StackContinuation<R, local, mounted>, arena>,
): Nothing {
  restack { dismount(this@suspend) { environment, s -> finish { block(environment, StackContinuation(s, resumer)) } } }
}
