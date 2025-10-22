package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.toOption
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runReader

data class Stream<out A>(val value: A, val next: (suspend context(MultishotScope) () -> Stream<A>?)?) :
  Shareable<Stream<A>> {
  context(_: Amb, _: Exc, _: MultishotScope)
  suspend fun reflect(): A {
    forEach { isLast, a -> if (isLast || flip()) return a }
    raise()
  }

  context(_: MultishotScope)
  suspend fun tail(): Stream<A>? = next?.let { it() }

  context(_: Sharing)
  override fun shareArgs(): Stream<A> = Stream(value.shareArgs(), next?.let { share(it) })
}

context(_: Amb, _: Exc, _: MultishotScope)
suspend fun <A> Stream<A>?.reflect(): A = (this ?: raise()).reflect()

context(_: MultishotScope)
suspend inline fun <A> Stream<A>?.forEach(block: (isLast: Boolean, A) -> Unit) {
  var branch = this
  while (branch != null) {
    block(branch.next == null, branch.value)
    branch = branch.tail()
  }
}

interface Logic {
  context(_: MultishotScope)
  suspend fun <A> split(block: suspend context(Amb, Exc, MultishotScope) () -> A): Stream<A>?
}

context(logic: Logic, _: MultishotScope)
suspend fun <A> split(block: suspend context(Amb, Exc, MultishotScope) () -> A): Stream<A>? = logic.split(block)

context(logic: Logic, _: Amb, _: Exc, _: MultishotScope)
suspend fun <A> interleave(
  first: suspend context(Amb, Exc, MultishotScope) () -> A,
  second: suspend context(Amb, Exc, MultishotScope) () -> A
): A {
  val branch = split(first) ?: return second()
  return if (flip()) branch.value
  else if (branch.next == null) second()
  else interleaveImpl({ split(second) }, branch.next)
}

// could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
// likely no because interleave delimits its arguments
context(_: Amb, _: Exc, _: MultishotScope)
private tailrec suspend fun <A> interleaveImpl(
  first: suspend context(MultishotScope) () -> Stream<A>?,
  second: suspend context(MultishotScope) () -> Stream<A>?
): A {
  val (value, next) = first() ?: return second().reflect()
  return if (flip()) value
  else if (next == null) second().reflect()
  else interleaveImpl(second, next)
}

context(_: Logic, _: Amb, _: Exc, _: MultishotScope)
suspend fun <A, B> fairBind(
  first: suspend context(Amb, Exc, MultishotScope) () -> A,
  second: suspend context(Amb, Exc, MultishotScope) (A) -> B
): B {
  val (value, next) = split(first) ?: raise()
  if (next == null) return second(value)
  return interleave({ second(value) }) { fairBindImpl(next, second) }
}

context(_: Logic, _: Amb, _: Exc, _: MultishotScope)
private suspend fun <A, B> fairBindImpl(
  first: suspend context(MultishotScope) () -> Stream<A>?,
  second: suspend context(Amb, Exc, MultishotScope) (A) -> B
): B {
  val (value, next) = first() ?: raise()
  return if (next == null) second(value)
  else interleave({ second(value) }) { fairBindImpl(next, second) }
}

// I think nullOnFailure/noneOnFailure are better replacements
context(_: Logic, _: Amb, _: Exc, _: MultishotScope)
suspend inline fun <A, B> ifte(
  noinline condition: suspend context(Amb, Exc, MultishotScope) () -> A,
  then: (A) -> B,
  otherwise: () -> B
): B {
  val stream = split(condition) ?: return otherwise()
  return then(stream.reflect())
}

context(_: Logic, _: Amb, _: Exc, _: MultishotScope)
suspend fun <A> nullOnFailure(block: suspend context(Amb, Exc, MultishotScope) () -> A): A? = split(block)?.reflect()

context(_: Logic, _: Amb, _: Exc, _: MultishotScope)
suspend fun <A> noneOnFailure(block: suspend context(Amb, Exc, MultishotScope) () -> A): Option<A> =
  split(block).toOption().map { it.reflect() }

context(_: Exc, _: MultishotScope)
suspend inline fun <A> once(crossinline block: suspend context(Amb, Exc, MultishotScope) () -> A): A = handle {
  effectfulLogic {
    discardWithFast(Result.success(block()))
  }
  raise()
}

context(_: MultishotScope)
suspend inline fun <A> onceOrNull(crossinline block: suspend context(Amb, Exc, MultishotScope) () -> A): A? = handle {
  effectfulLogic {
    discardWithFast(Result.success(block()))
  }
  null
}

context(_: MultishotScope)
suspend inline fun <A> onceOrNone(crossinline block: suspend context(Amb, Exc, MultishotScope) () -> A): Option<A> =
  handle {
    effectfulLogic {
      discardWithFast(Result.success(Some(block())))
    }
    None
  }

context(_: Exc, _: MultishotScope)
suspend fun gnot(block: suspend context(Amb, Exc, MultishotScope) () -> Unit) {
  if (succeeds(block)) raise()
}

context(_: MultishotScope)
suspend inline fun succeeds(crossinline block: suspend context(Amb, Exc, MultishotScope) () -> Unit): Boolean = handle {
  effectfulLogic {
    block()
    discardWithFast(Result.success(true))
  }
  false
}

context(_: MultishotScope)
suspend fun <A> bagOfN(count: Int = -1, block: suspend context(Amb, Exc, MultishotScope) () -> A): List<A> =
  handleStateful(if (count == -1) ArrayDeque<A>() else ArrayDeque(count), ::ArrayDeque) {
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

  context(_: MultishotScope)
  override suspend fun <A> split(block: suspend context(Amb, Exc, MultishotScope) () -> A): Stream<A>? = handle split@{
    runReader(ArrayDeque<suspend context(MultishotScope) () -> A>(), ::ArrayDeque) {
      ask().addFirst {
        handle ambExc@{
          context(Amb {
            useWithFinal { (resumeCopy, resumeFinal) ->
              ask().addFirst { resumeFinal(false) }
              resumeCopy(true)
            }
          }, Exc {
            discard {
              val branches = ask()
              if (branches.isEmpty()) this@split.discardWithFast(Result.success(null))
              else branches.removeFirst()()
            }
          }) {
            block()
          }
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
  context(_: MultishotScope)
  override suspend fun <A> split(block: suspend context(Amb, Exc, MultishotScope) () -> A) = handle<Stream<A>?> {
    context(Amb {
      useWithFinal { (resumeCopy, resumeFinal) ->
        composeTrees(resumeCopy(true)) { resumeFinal(false) }
      }
    }, Exc {
      discardWithFast(Result.success(null))
    }) {
      Stream(block(), null)
    }
  }

  context(_: MultishotScope)
  private suspend fun <A> composeTrees(stream: Stream<A>?, next: suspend context(MultishotScope) () -> Stream<A>?): Stream<A>? {
    val (value, nextPrime) = stream ?: return next()
    nextPrime ?: return Stream(value, next)
    return Stream(value) { composeTrees(nextPrime(), next) }
  }
}

object LogicSimple : Logic {
  context(_: MultishotScope)
  override suspend fun <A> split(block: suspend context(Amb, Exc, MultishotScope) () -> A): Stream<A>? = handle {
    effectfulLogic {
      val res = block()
      useOnce {
        Stream(res) { it(Unit) }
      }
    }
    null
  }
}

context(_: MultishotScope)
suspend fun effectfulLogic(block: suspend context(Amb, Exc, MultishotScope) () -> Unit): Unit = handle {
  context(Amb {
    useTailResumptiveTwice { resume ->
      resume(true)
      false
    }
  }, Exc { discardWithFast(Result.success(Unit)) }) {
    block()
  }
}