package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.ask
import io.github.kyay10.kontinuity.runReader

class Stream<out A>(val value: A, val next: (suspend () -> Stream<A>?)?) {
  operator fun component1(): A = value
  operator fun component2(): suspend context(Amb, Exc) () -> A = tail

  val tail: suspend context(Amb, Exc) () -> A get() = { next?.invoke().reflect() }

  context(_: Amb, _: Exc)
  suspend fun reflect(): A {
    forEach { isLast, a -> if (isLast || flip()) return a }
    raise()
  }
}

context(_: Amb, _: Exc)
suspend fun <A> Stream<A>?.reflect(): A = (this ?: raise()).reflect()

suspend inline fun <A> Stream<A>?.forEach(block: (isLast: Boolean, A) -> Unit) {
  var branch = this
  while (branch != null) {
    block(branch.next == null, branch.value)
    branch = branch.next?.invoke()
  }
}

interface Logic {
  suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>?

  context(_: Amb, _: Exc)
  suspend fun <A> interleave(
    first: suspend context(Amb, Exc) () -> A,
    second: suspend context(Amb, Exc) () -> A
  ): A = interleaveImpl(first, second)

  // TODO could we make this tailrec?
  context(_: Amb, _: Exc)
  suspend fun <A, B> fairBind(
    first: suspend context(Amb, Exc) () -> A,
    second: suspend context(Amb, Exc) (A) -> B
  ): B {
    val (res, branch) = split(first) ?: raise()
    return interleave({ second(res) }) { fairBind(branch, second) }
  }

  suspend fun <A> bagOfN(count: Int = -1, block: suspend context(Amb, Exc) () -> A): List<A> =
    bagOfNRec(count, emptyList(), block)

  companion object {

    // could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
    // likely no because interleave delimits its arguments
    context(_: Amb, _: Exc)
    private tailrec suspend fun <A> Logic.interleaveImpl(
      first: suspend context(Amb, Exc) () -> A,
      second: suspend context(Amb, Exc) () -> A
    ): A {
      val (res, branch) = split(first) ?: return second()
      return if (flip()) res
      else interleaveImpl(second, branch)
    }

    private tailrec suspend fun <A> Logic.bagOfNRec(
      count: Int,
      acc: List<A>,
      block: suspend context(Amb, Exc) () -> A
    ): List<A> {
      if (count < -1 || count == 0) return acc
      val (res, branch) = split(block) ?: return acc
      return bagOfNRec(if (count == -1) -1 else count - 1, acc + res, branch)
    }
  }
}

context(logic: Logic)
suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>? = logic.split(block)

context(logic: Logic, _: Amb, _: Exc)
suspend fun <A> interleave(
  first: suspend context(Amb, Exc) () -> A,
  second: suspend context(Amb, Exc) () -> A
): A = logic.interleave(first, second)

// TODO could we make this tailrec?
context(logic: Logic, _: Amb, _: Exc)
suspend fun <A, B> fairBind(
  first: suspend context(Amb, Exc) () -> A,
  second: suspend context(Amb, Exc) (A) -> B
): B = logic.fairBind(first, second)

//  I think nullOnFailure/noneOnFailure are better replacements
context(_: Logic, _: Amb, _: Exc)
suspend inline fun <A, B> ifte(
  noinline condition: suspend context(Amb, Exc) () -> A,
  then: (A) -> B,
  otherwise: () -> B
): B {
  val stream = split(condition) ?: return otherwise()
  return then(stream.reflect())
}

context(_: Logic, _: Amb, _: Exc)
suspend fun <A> nullOnFailure(
  block: suspend context(Amb, Exc) () -> A,
): A? = split(block)?.reflect()

context(_: Logic, _: Amb, _: Exc)
suspend fun <A> noneOnFailure(
  block: suspend context(Amb, Exc) () -> A,
): Option<A> = split(block)?.let { Some(it.reflect()) } ?: None

context(_: Exc, _: Logic)
suspend fun <A> once(block: suspend context(Amb, Exc) () -> A): A {
  val (res, _) = split(block) ?: raise()
  return res
}

context(_: Logic)
suspend fun <A> onceOrNull(block: suspend context(Amb, Exc) () -> A): A? {
  val (res, _) = split(block) ?: return null
  return res
}

context(__: Logic)
suspend fun <A> onceOrNone(block: suspend context(Amb, Exc) () -> A): Option<A> {
  val (res, _) = split(block) ?: return None
  return Some(res)
}

context(_: Exc, _: Logic)
suspend fun <A> gnot(block: suspend context(Amb, Exc) () -> A) {
  if (succeeds(block)) raise()
}

context(_: Logic)
suspend fun <A> succeeds(block: suspend context(Amb, Exc) () -> A) = split(block) != null

context(logic: Logic)
suspend fun <A> bagOfN(count: Int = -1, block: suspend context(Amb, Exc) () -> A): List<A> =
  logic.bagOfN(count, block)

object LogicDeep : Logic {
  // wrap with a handle that'll allow turning the iteration into an iterator-like thing
  // should be handle, then stateful. Reuse loop as shown in the pdf. stateful should have
  // answer type of A, while the outer handle should be Pair

  // we could emulate shallow handlers with state
  //  and thus split can be simpler, and can just return the list of jobs as List<suspend AmbExc.() -> A>

  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>? = handle split@{
    runReader(ArrayDeque<suspend () -> A>(), ::ArrayDeque) {
      ask().addFirst {
        handle ambExc@{
          block({
            use { resume ->
              ask().addFirst { resume(false) }
              resume(true)
            }
          }, {
            discard {
              val branches = ask()
              if (branches.isEmpty()) this@split.discardWithFast(Result.success(null))
              else branches.removeFirst().invoke()
            }
          })
        }
      }
      while (true) {
        val branch = ask().removeFirstOrNull() ?: break
        val result = branch()
        val isLast = ask().isEmpty()
        use {
          Stream(result, if(isLast) null else ({ it(Unit) }))
        }
      }
      null
    }
  }

  context(_: Amb, _: Exc)
  override suspend fun <A> interleave(
    first: suspend context(Amb, Exc) () -> A,
    second: suspend context(Amb, Exc) () -> A
  ): A {
    val branch = split(first) ?: return second()
    return if (flip()) branch.value
    else if (branch.next == null) second()
    else interleave2({ split(second) }, branch.next)
  }

  context(_: Amb, _: Exc)
  tailrec suspend fun <A> interleave2(first: suspend () -> Stream<A>?, second: suspend () -> Stream<A>?): A {
    val branch = first() ?: return second().reflect()
    return if (flip()) branch.value
    else if (branch.next == null) second().reflect()
    else interleave2(second, branch.next)
  }

  context(_: Amb, _: Exc)
  suspend fun <A, B> fairBind2(first: suspend () -> Stream<A>?, second: suspend context(Amb, Exc) (A) -> B): B {
    val branch = first() ?: raise()
    return if (branch.next == null) second(branch.value)
    else
      interleave({ second(branch.value) }) { fairBind2(branch.next, second) }
  }

  suspend fun <A> bagOfN2(count: Int = -1, block: suspend context(Amb, Exc) () -> A): List<A> =
    bagOfNRec2(count, emptyList()) { split(block) }

  private tailrec suspend fun <A> bagOfNRec2(count: Int, acc: List<A>, branch: suspend () -> Stream<A>?): List<A> {
    if (count < -1 || count == 0) return acc
    val branch = branch() ?: return acc
    return if (branch.next == null) acc + branch.value
    else
      bagOfNRec2(if (count == -1) -1 else count - 1, acc + branch.value, branch.next)
  }
}

object LogicTree : Logic {

  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A) = reifyLogic(block)

  private suspend fun <A> composeTrees(value: Stream<A>?, next: suspend () -> Stream<A>?): Stream<A>? = when (value) {
    null -> next()
    else -> {
      val nextPrime = value.next ?: return Stream(value.value, next)
      Stream(value.value) { composeTrees(nextPrime(), next) }
    }
  }

  private suspend fun <A> reifyLogic(block: suspend context(Amb, Exc) () -> A): Stream<A>? = handle {
    Stream(block({
      useWithFinal { (resumeCopy, resumeFinal) ->
        composeTrees(resumeCopy(true)) { resumeFinal(false) }
      }
    }) {
      discardWithFast(Result.success(null))
    }, null)
  }
}