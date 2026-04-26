package io.github.kyay10.kontinuity

data class Stream<out A>(val value: A, val next: Producer<Stream<A>?>?) : Shareable<Stream<A>> {
  context(_: Amb, _: Exc)
  suspend fun reflect(): A {
    forEach { isLast, a -> if (isLast || flip()) return a }
    raise()
  }

  suspend fun tail(): Stream<A>? = next?.invoke()

  context(_: Sharing)
  override fun shareArgs(): Stream<A> = Stream(value.shareArgs(), next?.let { share(it) })

  companion object {
    inline operator fun <A> invoke(value: A, crossinline next: suspend () -> Stream<A>?) = Stream(value, Producer(next))
  }
}

context(_: Amb, _: Exc)
suspend fun <A> Stream<A>?.reflect(): A = bind().reflect()

suspend inline fun <A> Stream<A>?.forEach(block: (isLast: Boolean, A) -> Unit) {
  var branch = this
  while (branch != null) {
    block(branch.next == null, branch.value)
    branch = branch.tail()
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
  second: suspend context(Amb, Exc) () -> A,
): A {
  val branch = split(first) ?: return second()
  return if (flip()) branch.value
  else if (branch.next == null) second() else interleaveImpl(Producer { split(second) }, branch.next)
}

// could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some
// block
// likely no because interleave delimits its arguments
context(_: Amb, _: Exc)
private tailrec suspend fun <A> interleaveImpl(first: Producer<Stream<A>?>, second: Producer<Stream<A>?>): A {
  val (value, next) = first() ?: return second().reflect()
  return if (flip()) value else if (next == null) second().reflect() else interleaveImpl(second, next)
}

context(_: Logic, _: Amb, _: Exc)
suspend fun <A, B> fairBind(
  first: suspend context(Amb, Exc) () -> A,
  second: suspend context(Amb, Exc) (A) -> B,
): B {
  val (value, next) = split(first).bind()
  if (next == null) return second(value)
  return interleave({ second(value) }) { fairBindImpl(next, second) }
}

context(_: Logic, _: Amb, _: Exc)
private suspend fun <A, B> fairBindImpl(
  first: Producer<Stream<A>?>,
  second: suspend context(Amb, Exc) (A) -> B,
): B {
  val (value, next) = first().bind()
  return if (next == null) second(value) else interleave({ second(value) }) { fairBindImpl(next, second) }
}

context(_: Exc)
suspend inline fun <A> once(crossinline block: suspend context(Amb, Exc) () -> A): A = handle {
  effectfulLogic { discardWithFast(Result.success(block())) }
  raise()
}

suspend inline fun <A> onceOrNull(crossinline block: suspend context(Amb, Exc) () -> A): A? = handle {
  effectfulLogic { discardWithFast(Result.success(block())) }
  null
}

context(_: Exc)
suspend fun gnot(block: suspend context(Amb, Exc) () -> Unit) = ensure(!succeeds(block))

suspend inline fun succeeds(crossinline block: suspend context(Amb, Exc) () -> Unit): Boolean = handle {
  effectfulLogic {
    block()
    discardWithFast(Result.success(true))
  }
  false
}

suspend fun <A> bagOfN(count: Int = -1, block: suspend context(Amb, Exc) () -> A) = buildListLocally {
  handle {
    effectfulLogic {
      add(block())
      if (size == count) discardWithFast(Result.success(Unit))
    }
  }
}

object LogicDeep : Logic {
  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>? = handle split@{
    runListBuilder<suspend () -> A, _> {
      add {
        handle ambExc@{
          val discardAction = suspend {
            val branch = removeLastOrNull() ?: this@split.discardWithFast(Result.success(null))
            branch()
          }
          block(
            {
              use { resume ->
                add { resume.final(false) }
                resume(true)
              }
            },
            object : Exc {
              override suspend fun raise() = discardFast(discardAction)

              override fun raise(r: Unit) = discard(discardAction)
            },
          )
        }
      }
      while (isNotEmpty()) {
        val result = removeLast()()
        val isLast = isEmpty()
        useOnce { if (isLast) Stream(result, null) else Stream(result) { it(Unit) } }
      }
      null
    }
  }
}

object LogicTree : Logic {
  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A) =
    handle<Stream<A>?> {
      val amb = Amb { use { resume -> composeTrees(resume(true), Producer { resume.final(false) }) } }
      Stream(block(amb, constantExc(null)), null)
    }

  private suspend fun <A> composeTrees(stream: Stream<A>?, next: Producer<Stream<A>?>): Stream<A>? {
    val (value, nextPrime) = stream ?: return next()
    nextPrime ?: return Stream(value, next)
    return Stream(value) { composeTrees(nextPrime(), next) }
  }
}

object LogicSimple : Logic {
  override suspend fun <A> split(block: suspend context(Amb, Exc) () -> A): Stream<A>? = handle {
    effectfulLogic {
      val res = block()
      useOnce { Stream(res) { it(Unit) } }
    }
    null
  }
}

suspend fun effectfulLogic(block: suspend context(Amb, Exc) () -> Unit): Unit = handle {
  block(
    {
      useTailResumptive { resume ->
        resume(true)
        false
      }
    },
    constantExc(Unit),
  )
}
