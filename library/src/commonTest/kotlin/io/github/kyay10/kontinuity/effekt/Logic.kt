package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.toOption
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.ask

data class Stream<out A>(val value: A, val next: (suspend MultishotScope.() -> Stream<A>?)?) : Shareable<Stream<A>> {
  context(_: Sharing)
  override fun shareArgs(): Stream<A> = Stream(value.shareArgs(), next?.let { share(it) })
}

context(_: Amb, _: Exc)
suspend fun <A> MultishotScope.reflect(s: Stream<A>?): A {
  s ?: raise()
  forEach(s) { isLast, a -> if (isLast || flip()) return a }
  raise()
}

suspend fun <A> MultishotScope.tail(s: Stream<A>): Stream<A>? = s.next?.let { it() }

suspend inline fun <A> MultishotScope.forEach(s: Stream<A>?, block: (isLast: Boolean, A) -> Unit) {
  var branch = s
  while (branch != null) {
    block(branch.next == null, branch.value)
    branch = tail(branch)
  }
}

interface Logic {
  suspend fun <A> MultishotScope.split(block: suspend context(Amb, Exc) MultishotScope.() -> A): Stream<A>?
}

context(logic: Logic)
suspend fun <A> MultishotScope.split(block: suspend context(Amb, Exc) MultishotScope.() -> A): Stream<A>? =
  with(logic) { split(block) }

context(logic: Logic, _: Amb, _: Exc)
suspend fun <A> MultishotScope.interleave(
  first: suspend context(Amb, Exc) MultishotScope.() -> A,
  second: suspend context(Amb, Exc) MultishotScope.() -> A
): A {
  val branch = split(first) ?: return second()
  return if (flip()) branch.value
  else if (branch.next == null) second()
  else interleaveImpl({ split(second) }, branch.next)
}

// could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
// likely no because interleave delimits its arguments
context(_: Amb, _: Exc)
private tailrec suspend fun <A> MultishotScope.interleaveImpl(
  first: suspend MultishotScope.() -> Stream<A>?,
  second: suspend MultishotScope.() -> Stream<A>?
): A {
  val (value, next) = first() ?: return reflect(second())
  return if (flip()) value
  else if (next == null) reflect(second())
  else interleaveImpl(second, next)
}

context(_: Logic, _: Amb, _: Exc)
suspend fun <A, B> MultishotScope.fairBind(
  first: suspend context(Amb, Exc) MultishotScope.() -> A,
  second: suspend context(Amb, Exc) MultishotScope.(A) -> B
): B {
  val (value, next) = split(first) ?: raise()
  if (next == null) return second(value)
  return interleave({ second(value) }) { fairBindImpl(next, second) }
}

context(_: Logic, _: Amb, _: Exc)
private suspend fun <A, B> MultishotScope.fairBindImpl(
  first: suspend MultishotScope.() -> Stream<A>?,
  second: suspend context(Amb, Exc) MultishotScope.(A) -> B
): B {
  val (value, next) = first() ?: raise()
  return if (next == null) second(value)
  else interleave({ second(value) }) { fairBindImpl(next, second) }
}

// I think nullOnFailure/noneOnFailure are better replacements
context(_: Logic, _: Amb, _: Exc)
suspend inline fun <A, B> MultishotScope.ifte(
  noinline condition: suspend context(Amb, Exc) MultishotScope.() -> A,
  then: (A) -> B,
  otherwise: () -> B
): B {
  val stream = split(condition) ?: return otherwise()
  return then(reflect(stream))
}

context(_: Logic, _: Amb, _: Exc)
suspend fun <A> MultishotScope.nullOnFailure(block: suspend context(Amb, Exc) MultishotScope.() -> A): A? =
  reflect(split(block))

context(_: Logic, _: Amb, _: Exc)
suspend fun <A> MultishotScope.noneOnFailure(block: suspend context(Amb, Exc) MultishotScope.() -> A): Option<A> =
  split(block).toOption().map { reflect(it) }

context(_: Exc)
suspend inline fun <A> MultishotScope.once(crossinline block: suspend context(Amb, Exc) MultishotScope.() -> A): A =
  handle {
  effectfulLogic {
    discardWithFast(Result.success(block()))
  }
  raise()
}

suspend inline fun <A> MultishotScope.onceOrNull(crossinline block: suspend context(Amb, Exc) MultishotScope.() -> A): A? =
  handle {
  effectfulLogic {
    discardWithFast(Result.success(block()))
  }
  null
}

suspend inline fun <A> MultishotScope.onceOrNone(crossinline block: suspend context(Amb, Exc) MultishotScope.() -> A): Option<A> =
  handle {
  effectfulLogic {
    discardWithFast(Result.success(Some(block())))
  }
  None
}

context(_: Exc)
suspend fun MultishotScope.gnot(block: suspend context(Amb, Exc) MultishotScope.() -> Unit) {
  if (succeeds(block)) raise()
}

suspend inline fun MultishotScope.succeeds(crossinline block: suspend context(Amb, Exc) MultishotScope.() -> Unit): Boolean =
  handle {
  effectfulLogic {
    block()
    discardWithFast(Result.success(true))
  }
  false
}

suspend fun <A> MultishotScope.bagOfN(
  count: Int = -1,
  block: suspend context(Amb, Exc) MultishotScope.() -> A
): List<A> =
  handleStateful(if(count == -1) ArrayDeque<A>() else ArrayDeque(count), ::ArrayDeque) {
    effectfulLogic {
      val res = block()
      val list = get()
      list.add(res)
      if (list.size == count) {
        discardWithFast(Result.success(list))
      }
    }
    get()
  }

object LogicDeep : Logic {
  // wrap with a handle that'll allow turning the iteration into an iterator-like thing
  // should be handle, then stateful. Reuse loop as shown in the pdf. stateful should have
  // answer type of A, while the outer handle should be Pair

  // we could emulate shallow handlers with state
  //  and thus split can be simpler, and can just return the list of jobs as List<suspend AmbExc.() -> A>

  override suspend fun <A> MultishotScope.split(block: suspend context(Amb, Exc) MultishotScope.() -> A): Stream<A>? =
    handle {
      runReader(ArrayDeque<suspend MultishotScope.() -> A>(), ::ArrayDeque) {
      ask().addFirst {
        handle {
          block({
            useWithFinal { (resumeCopy, resumeFinal) ->
              ask().addFirst { resumeFinal(false) }
              resumeCopy(true)
            }
          }, {
            discard {
              val branches = ask()
              if (branches.isEmpty()) discardWithFast(Result.success(null))
              else branches.removeFirst()()
            }
          }, this)
        }
      }
      while (true) {
        val branch = ask().removeFirstOrNull() ?: break
        val result = branch()
        val isLast = ask().isEmpty()
        useOnce {
          Stream(result, if (isLast) null else ({ it(Unit) }))
        }
      }
      null
    }
  }
}

object LogicTree : Logic {
  override suspend fun <A> MultishotScope.split(block: suspend context(Amb, Exc) MultishotScope.() -> A) =
    handle<Stream<A>?> {
    Stream(block({
      useWithFinal { (resumeCopy, resumeFinal) ->
        composeTrees(resumeCopy(true)) { resumeFinal(false) }
      }
    }, {
      discardWithFast(Result.success(null))
    }, this), null)
  }

  private suspend fun <A> MultishotScope.composeTrees(
    stream: Stream<A>?,
    next: suspend MultishotScope.() -> Stream<A>?
  ): Stream<A>? {
    val (value, nextPrime) = stream ?: return next()
    nextPrime ?: return Stream(value, next)
    return Stream(value) { composeTrees(nextPrime(), next) }
  }
}

object LogicSimple : Logic {
  override suspend fun <A> MultishotScope.split(block: suspend context(Amb, Exc) MultishotScope.() -> A): Stream<A>? = handle {
    effectfulLogic {
      val res = block()
      useOnce {
        Stream(res) { it(Unit) }
      }
    }
    null
  }
}

suspend fun MultishotScope.effectfulLogic(block: suspend context(Amb, Exc) MultishotScope.() -> Unit): Unit = handle {
  block({
    useTailResumptiveTwice { resume ->
      resume(true)
      false
    }
  }, {
    discardWithFast(Result.success(Unit))
  }, this)
}