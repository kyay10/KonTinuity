package io.github.kyay10.kontinuity.stacks

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

public fun interface LocalFun0<out R, in local> : Local<LocalFun0<R, *>, local> {
  context(_: Locality<local>)
  public suspend operator fun invoke(): R
}

public fun <R, local> Local<LocalFun0<R, *>, local>.fix(): LocalFun0<R, local> = this as LocalFun0

public fun interface LocalFun1<in T, out R, in local> : Local<LocalFun1<T, R, *>, local> {
  context(_: Locality<local>)
  public suspend operator fun invoke(t: T): R
}

public fun <T, R, local> Local<LocalFun1<T, R, *>, local>.fix(): LocalFun1<T, R, local> = this as LocalFun1

public fun <T, resumption, continuation> ignoreInput(
  continuation: StackContinuation<LocalFun0<Nothing, *>, resumption, continuation>
): StackContinuation<LocalFun1<T, Nothing, *>, resumption, continuation> =
  StackContinuation(continuation.suspension, LocalFun1 { _ -> continuation.resumer.fix()() })

public class StackMount<arena, E, mounted : arena, in `this`> {
  @Suppress("UNCHECKED_CAST") internal var state: Local<E, *>? = null
  internal var stack: Stack<arena>? = null

  public fun <R> new(resumer: Local<R, mounted>): StackContinuation<R, out mounted, mounted> =
    StackContinuation(StackSuspension.Initial(this), resumer)

  private var asStack: Stack<mounted>? = null

  context(trampoline: Trampoline<*>)
  internal fun asStack(): Stack<mounted> =
    asStack
      ?: Continuation(EmptyCoroutineContext) { result ->
          trampoline.resumeIntercepted(stack!!, result.fold({ it }, { it }))
        }
        .let(::Stack)
}

public interface RestackerFun0<out R, in resumption> {
  context(_: Locality<local>)
  public suspend operator fun <local : resumption> StackRestacker<resumption, local>.invoke(): R
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

public interface DismountFun<in E, in S, in arena> {
  context(_: Locality<local>)
  public suspend operator fun <local : environment, environment : arena> StackRestacker<environment, local>.invoke(
    environment: Local<E, environment>,
    s: S,
  ): Nothing

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
  @Suppress("UNCHECKED_CAST") stack.swap { block(mount.state as Local<E, arena>, StackSuspension.Cont(it)) }
}

public interface SwitchToFun<in A, in resumption> {
  context(_: Locality<local>)
  public suspend operator fun <local : resumption> StackRestacker<resumption, local>.invoke(a: A): Nothing
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
  suspension.stack.swap { block(StackSuspension.Cont(it)) }
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

public interface AfterFun<in E, in O, in arena> {
  context(_: Locality<environment>)
  public suspend operator fun <environment : arena> invoke(environment: Local<E, environment>, o: O): Nothing
}

public fun <arena, E, O, mounted : arena> StackMount<arena, E, mounted, mounted>.new(
  after: AfterFun<E, O, arena>,
  block: suspend context(Locality<mounted>) () -> O,
): StackContinuation<LocalFun0<Nothing, *>, out mounted, mounted> =
  new(
    LocalFun0 {
      val output = block()
      restack(
        object : RestackerFun0<Nothing, mounted> {
          context(_: Locality<local>)
          override suspend fun <local : mounted> StackRestacker<mounted, local>.invoke() =
            dismount(
              this@new,
              object : DismountFun<E, StackSuspension<mounted, mounted>, arena> {
                context(_: Locality<local>)
                override suspend fun <local : environment, environment : arena> StackRestacker<environment, local>
                  .invoke(
                  environment: Local<E, environment>,
                  s: StackSuspension<mounted, mounted>,
                ) = finish { after(environment, output) }
              },
            )
        }
      )
    }
  )

public interface ResumeFun<in R, in mounted> {
  context(_: Locality<resumption>)
  public suspend operator fun <resumption : mounted> invoke(resumption: Local<R, resumption>): Nothing
}

context(locality: Locality<local>)
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
  restack(
    object : RestackerFun0<Nothing, local> {
      context(_: Locality<local2>)
      override suspend fun <local2 : local> StackRestacker<local, local2>.invoke() =
        mount(
          environment,
          this@resume,
          continuation.suspension,
          object : RestackerFun0<Nothing, resumption> {
            context(_: Locality<local>)
            override suspend fun <local : resumption> StackRestacker<resumption, local>.invoke() = finish {
              block(continuation.resumer)
            }
          },
        )
    }
  )
}

public interface SuspendFun<in E, in C, in arena> {
  context(_: Locality<environment>)
  public suspend operator fun <environment : arena> invoke(environment: Local<E, environment>, c: C): Nothing
}

context(locality: Locality<local>)
public suspend fun <arena, E, R, mounted : arena, local : mounted> StackMount<arena, E, mounted, local>.suspend(
  resumer: Local<R, local>,
  block: SuspendFun<E, StackContinuation<R, local, mounted>, arena>,
): Nothing {
  restack(
    object : RestackerFun0<Nothing, local> {
      context(_: Locality<local2>)
      override suspend fun <local2 : local> StackRestacker<local, local2>.invoke() =
        dismount(
          this@suspend,
          object : DismountFun<E, StackSuspension<local, mounted>, arena> {
            context(_: Locality<local3>)
            override suspend fun <local3 : environment, environment : arena> StackRestacker<environment, local3>.invoke(
              environment: Local<E, environment>,
              s: StackSuspension<local, mounted>,
            ) = finish { block(environment, StackContinuation(s, resumer)) }
          },
        )
    }
  )
}
