package io.github.kyay10.kontinuity.effekt

import arrow.core.Option
import arrow.core.toOption
import io.github.kyay10.kontinuity.ask
import io.github.kyay10.kontinuity.runReader
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

data class Stream<out A>(val value: A, val next: (suspend () -> Stream<A>?)?) {
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
}

context(logic: Logic)
suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>? = logic.split(block)

context(logic: Logic, _: Amb, _: Exc)
suspend fun <A> interleave(
  first: suspend context(Amb, Exc) () -> A,
  second: suspend context(Amb, Exc) () -> A
): A {
  val branch = split(first) ?: return second()
  return if (flip()) branch.value
  else if (branch.next == null) second()
  else interleaveImpl({ split(second) }, branch.next)
}

// could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
// likely no because interleave delimits its arguments
context(_: Amb, _: Exc)
private tailrec suspend fun <A> interleaveImpl(
  first: suspend () -> Stream<A>?,
  second: suspend () -> Stream<A>?
): A {
  val (value, next) = first() ?: return second().reflect()
  return if (flip()) value
  else if (next == null) second().reflect()
  else interleaveImpl(second, next)
}

// TODO could we make this tailrec?
context(_: Logic, _: Amb, _: Exc)
suspend fun <A, B> fairBind(
  first: suspend context(Amb, Exc) () -> A,
  second: suspend context(Amb, Exc) (A) -> B
): B {
  val (value, next) = split(first) ?: raise()
  if (next == null) return second(value)
  return interleave({ second(value) }) { fairBindImpl(next, second) }
}

context(_: Logic, _: Amb, _: Exc)
private suspend fun <A, B> fairBindImpl(
  first: suspend () -> Stream<A>?,
  second: suspend context(Amb, Exc) (A) -> B
): B {
  val (value, next) = first() ?: raise()
  return if (next == null) second(value)
  else interleave({ second(value) }) { fairBindImpl(next, second) }
}

// I think nullOnFailure/noneOnFailure are better replacements
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
suspend fun <A> nullOnFailure(block: suspend context(Amb, Exc) () -> A): A? = split(block)?.reflect()

context(_: Logic, _: Amb, _: Exc)
suspend fun <A> noneOnFailure(block: suspend context(Amb, Exc) () -> A): Option<A> =
  split(block).toOption().map { it.reflect() }

context(_: Exc, _: Logic)
suspend fun <A> once(block: suspend context(Amb, Exc) () -> A): A = (split(block) ?: raise()).value

context(_: Logic)
suspend fun <A> onceOrNull(block: suspend context(Amb, Exc) () -> A): A? = split(block)?.value

context(_: Logic)
suspend fun <A> onceOrNone(block: suspend context(Amb, Exc) () -> A): Option<A> =
  split(block).toOption().map { it.value }

context(_: Exc, _: Logic)
suspend fun <A> gnot(block: suspend context(Amb, Exc) () -> A) {
  if (succeeds(block)) raise()
}

context(_: Logic)
suspend fun <A> succeeds(block: suspend context(Amb, Exc) () -> A) = split(block) != null

context(logic: Logic)
suspend fun <A> bagOfN(count: Int = -1, block: suspend context(Amb, Exc) () -> A): List<A> =
  bagOfNImpl(count, persistentListOf()) { split(block) }

private tailrec suspend fun <A> bagOfNImpl(
  count: Int,
  acc: PersistentList<A>,
  branch: suspend () -> Stream<A>?
): List<A> {
  if (count < -1 || count == 0) return acc
  val (value, next) = branch() ?: return acc
  val newAcc = acc.add(value)
  return if (next == null) newAcc
  else bagOfNImpl(if (count == -1) -1 else count - 1, newAcc, next)
}

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
            useWithFinal { (resumeCopy, resumeFinal) ->
              ask().addFirst { resumeFinal(false) }
              resumeCopy(true)
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
        useOnce {
          Stream(result, if (isLast) null else ({ it(Unit) }))
        }
      }
      null
    }
  }
}

object LogicTree : Logic {
  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A) = handle<Stream<A>?> {
    Stream(block({
      useWithFinal { (resumeCopy, resumeFinal) ->
        composeTrees(resumeCopy(true)) { resumeFinal(false) }
      }
    }) {
      discardWithFast(Result.success(null))
    }, null)
  }

  private suspend fun <A> composeTrees(stream: Stream<A>?, next: suspend () -> Stream<A>?): Stream<A>? {
    val (value, nextPrime) = stream ?: return next()
    nextPrime ?: return Stream(value, next)
    return Stream(value) { composeTrees(nextPrime(), next) }
  }
}

object LogicSimple : Logic {
  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>? = handle {
    effectfulLogic {
      val res = block()
      useOnce {
        Stream(res) { it(Unit) }
      }
    }
    null
  }

  private suspend fun effectfulLogic(block: suspend context(Amb, Exc) () -> Unit): Unit = handle {
    block({
      useWithFinal { (resumeCopy, resumeFinal) ->
        resumeCopy(true)
        resumeFinal(false)
      }
    }) {
      discardWithFast(Result.success(Unit))
    }
  }
}