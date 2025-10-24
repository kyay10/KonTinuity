package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.toOption
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.runReader

data class Stream<out A, in Region>(
  val value: A,
  val next: (suspend context(MultishotScope<Region>) () -> Stream<A, Region>?)?
) : Shareable<Stream<A, Region>> {
  context(_: MultishotScope<Region>)
  suspend fun tail(): Stream<A, Region>? = next?.let { it() }

  context(_: Sharing)
  override fun shareArgs(): Stream<A, Region> = Stream(value.shareArgs(), next?.let { share(it) })
}

context(_: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend fun <A, Region> Stream<A, Region>?.reflect(): A {
  forEach { isLast, a -> if (isLast || flip()) return a }
  raise()
}

context(_: MultishotScope<Region>)
suspend inline fun <A, Region> Stream<A, Region>?.forEach(block: (isLast: Boolean, A) -> Unit) {
  var branch = this
  while (branch != null) {
    block(branch.next == null, branch.value)
    branch = branch.tail()
  }
}

interface Logic {
  context(_: MultishotScope<Region>)
  suspend fun <A, Region> split(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): Stream<A, Region>?
}

context(logic: Logic, _: MultishotScope<Region>)
suspend fun <A, Region> split(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): Stream<A, Region>? =
  logic.split(block)

context(logic: Logic, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend fun <A, Region> interleave(
  first: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A,
  second: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A
): A {
  val branch = split(first) ?: return second()
  return if (flip()) branch.value
  else if (branch.next == null) second()
  else interleaveImpl({ split(second) }, branch.next)
}

// could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
// likely no because interleave delimits its arguments
context(_: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
private tailrec suspend fun <A, Region> interleaveImpl(
  first: suspend context(MultishotScope<Region>) () -> Stream<A, Region>?,
  second: suspend context(MultishotScope<Region>) () -> Stream<A, Region>?
): A {
  val (value, next) = first() ?: return second().reflect()
  return if (flip()) value
  else if (next == null) second().reflect()
  else interleaveImpl(second, next)
}

context(_: Logic, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend fun <A, B, Region> fairBind(
  first: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A,
  second: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) (A) -> B
): B {
  val (value, next) = split(first) ?: raise()
  if (next == null) return second(value)
  return interleave({ second(value) }) { fairBindImpl(next, second) }
}

context(_: Logic, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
private suspend fun <A, B, Region> fairBindImpl(
  first: suspend context(MultishotScope<Region>) () -> Stream<A, Region>?,
  second: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) (A) -> B
): B {
  val (value, next) = first() ?: raise()
  return if (next == null) second(value)
  else interleave({ second(value) }) { fairBindImpl(next, second) }
}

// I think nullOnFailure/noneOnFailure are better replacements
context(_: Logic, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend inline fun <A, B, Region> ifte(
  noinline condition: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A,
  then: (A) -> B,
  otherwise: () -> B
): B {
  val stream = split(condition) ?: return otherwise()
  return then(stream.reflect())
}

context(_: Logic, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend fun <A, Region> nullOnFailure(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): A? =
  split(block)?.reflect()

context(_: Logic, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend fun <A, Region> noneOnFailure(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): Option<A> =
  split(block).toOption().map { it.reflect() }

context(_: Exc<Region>, _: MultishotScope<Region>)
suspend fun <A, Region> once(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): A =
  handle {
    effectfulLogic {
      discardWithFast(Result.success(block()))
    }
    raise()
  }

context(_: MultishotScope<Region>)
suspend fun <A, Region> onceOrNull(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): A? =
  handle {
    effectfulLogic {
      discardWithFast(Result.success(block()))
    }
    null
  }

context(_: MultishotScope<Region>)
suspend fun <A, Region> onceOrNone(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): Option<A> =
  handle {
    effectfulLogic {
      discardWithFast(Result.success(Some(block())))
    }
    None
  }

context(_: Exc<Region>, _: MultishotScope<Region>)
suspend fun <Region> gnot(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> Unit) {
  if (succeeds(block)) raise()
}

context(_: MultishotScope<Region>)
suspend fun <Region> succeeds(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> Unit): Boolean =
  handle {
    effectfulLogic {
      block()
      discardWithFast(Result.success(true))
    }
    false
  }

context(_: MultishotScope<Region>)
suspend fun <A, Region> bagOfN(
  count: Int = -1,
  block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A
): List<A> =
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

  // Preserves the intersection type from the scope
  context(_: MultishotScope<Region>)
  private fun <Region, A> ArrayDequeOfFunctionsIn() = ArrayDeque<suspend context(MultishotScope<Region>) () -> A>()

  context(_: MultishotScope<Region>)
  override suspend fun <A, Region> split(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): Stream<A, Region>? =
    handle split@{
      runReader(ArrayDequeOfFunctionsIn<_, A>(), ::ArrayDeque) {
        ask().addFirst {
          handle ambExc@{
            context(Amb {
              useWithFinal { (resumeCopy, resumeFinal) ->
                ask().addFirst { resumeFinal(false) }
                resumeCopy(true)
              }
            }, Exc<AmbExcRegion> {
              discard {
                val branches = ask()
                if (branches.isEmpty()) this@split.discardWithFast(Result.success(null))
                else branches.removeFirst()()
              }
            }) { block() }
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
  context(_: MultishotScope<Region>)
  override suspend fun <A, Region> split(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A) =
    handle<Stream<A, Region>?, _> {
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

  context(_: MultishotScope<Region>)
  private suspend fun <A, Region> composeTrees(
    stream: Stream<A, Region>?,
    next: suspend context(MultishotScope<Region>) () -> Stream<A, Region>?
  ): Stream<A, Region>? {
    val (value, nextPrime) = stream ?: return next()
    nextPrime ?: return Stream(value, next)
    return Stream(value) { composeTrees(nextPrime(), next) }
  }
}

object LogicSimple : Logic {
  context(_: MultishotScope<Region>)
  override suspend fun <A, Region> split(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> A): Stream<A, Region>? =
    handle {
      effectfulLogic {
        val res = block()
        useOnce {
          Stream(res) { it(Unit) }
        }
      }
      null
    }
}

context(_: MultishotScope<Region>)
suspend fun <Region> effectfulLogic(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> Unit): Unit =
  handle {
    context(Amb {
      useTailResumptiveTwice { resume ->
        resume(true)
        false
      }
    }, Exc { discardWithFast(Result.success(Unit)) }) {
      block()
    }
  }