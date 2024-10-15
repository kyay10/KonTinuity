package effekt

import Reader
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ask
import pushReader
import runReader

object LogicShallow {
  suspend fun <A> split(block: suspend AmbExc.() -> A): Pair<A, suspend AmbExc.() -> A>? =
    handleStateful(ArrayDeque<suspend () -> A>(), ::ArrayDeque) split@{
      val currentAmbExc = Reader<AmbExc>()
      val ambExc = object : AmbExc, Handler<A> by HandlerPrompt() {
        override suspend fun flip(): Boolean = use { resume ->
          get().addFirst { resume(false) }
          resume(true)
        }

        override suspend fun raise(msg: String): Nothing = discard {
          val branches = get()
          if (branches.isEmpty()) this@split.discardWithFast(Result.success(null))
          else branches.removeFirst().invoke()
        }
      }
      val res = currentAmbExc.pushReader(ambExc) {
        ambExc.rehandle {
          block(object : AmbExc {
            override suspend fun flip(): Boolean = currentAmbExc.ask().flip()
            override suspend fun raise(msg: String): Nothing = currentAmbExc.ask().raise(msg)
          })
        }
      }
      val jobQueue = get()
      Pair(res) {
        jobQueue.forEach {
          if (flip()) return@Pair currentAmbExc.pushReader(this, body = it)
        }
        raise()
      }
    }

  suspend fun <A> AmbExc.reflect(pair: Pair<A, suspend AmbExc.() -> A>?): A {
    val (value, next) = pair ?: raise()
    return if (flip()) value else next()
  }

  // TODO could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
  tailrec suspend fun <A> AmbExc.interleave(first: suspend AmbExc.() -> A, second: suspend AmbExc.() -> A): A {
    val (res, branch) = split(first) ?: return second()
    return if (flip()) res
    else interleave(second, branch)
  }

  // TODO could we make this tailrec?
  suspend fun <A, B> AmbExc.fairBind(first: suspend AmbExc.() -> A, second: suspend AmbExc.(A) -> B): B {
    val (res, branch) = split(first) ?: raise()
    return interleave({ second(res) }) { fairBind(branch, second) }
  }

  // TODO I think nullOnFailure is a better replacement (or an Option variant)
  suspend inline fun <A, B> AmbExc.ifte(
    noinline condition: suspend AmbExc.() -> A,
    then: (A) -> B,
    otherwise: () -> B
  ): B {
    val (res, branch) = split(condition) ?: return otherwise()
    // TODO I think we can take out the `then` here
    return if (flip()) then(res)
    else then(branch())
  }

  suspend fun <A> AmbExc.nullOnFailure(
    block: suspend AmbExc.() -> A,
  ): A? = split(block)?.let { reflect(it) }

  suspend fun <A> AmbExc.noneOnFailure(
    block: suspend AmbExc.() -> A,
  ): Option<A> = split(block)?.let { Some(reflect(it)) } ?: None

  suspend fun <A> Exc.once(block: suspend AmbExc.() -> A): A {
    val (res, _) = split(block) ?: raise()
    return res
  }

  suspend fun <A> AmbExc.gnot(block: suspend AmbExc.() -> A) = ifte({ once(block) }, { raise() }) { }
  suspend fun <A> AmbExc.gnot2(block: suspend AmbExc.() -> A) {
    noneOnFailure { once(block) }.onSome { raise() }
  }

  suspend fun <A> Exc.gnot3(block: suspend AmbExc.() -> A) {
    if (succeeds(block)) raise()
  }

  suspend fun <A> succeeds(block: suspend AmbExc.() -> A) = split(block) != null

  suspend fun <A> bagOfN(count: Int = -1, block: suspend AmbExc.() -> A): List<A> =
    bagOfNRec(count, emptyList(), block)

  private tailrec suspend fun <A> bagOfNRec(count: Int, acc: List<A>, block: suspend AmbExc.() -> A): List<A> {
    if (count < -1 || count == 0) return emptyList()
    val (res, branch) = split(block) ?: return emptyList()
    return bagOfNRec(if (count == -1) -1 else count - 1, acc + res, branch)
  }
}

object LogicDeep {
  // wrap with a handle that'll allow turning the iteration into an iterator-like thing
  // should be handle, then stateful. Reuse loop as shown in the pdf. stateful should have
  // answer type of A, while the outer handle should be Pair
  // TODO if we make next nullable, we can detect the case where the queue has no more items
  //  and hence prevent the extra `raise` in every `reflect` over a branch
  //  This seems to correspond to the `Tree` datatype from the LogicT paper
  data class Branch<A>(val value: A, val next: suspend () -> Branch<A>?)

  // TODO a lot of these directly would benefit from directly using Branch
  //  instead of reflecting and splitting repeatedly

  // TODO we could emulate shallow handlers with state
  //  and thus split can be simpler, and can just return the list of jobs as List<suspend AmbExc.() -> A>

  suspend fun <A> split(block: suspend AmbExc.() -> A): Branch<A>? = handle split@{
    runReader(ArrayDeque<suspend () -> A>(), ::ArrayDeque) {
      ask().addFirst {
        handle ambExc@{
          block(object : AmbExc {
            override suspend fun flip(): Boolean = use { resume ->
              ask().addFirst { resume(false) }
              resume(true)
            }

            override suspend fun raise(msg: String): Nothing = discard {
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
        use {
          Branch(result) { it(Unit) }
        }
      }
      null
    }
  }

  // Opposite of split
  suspend fun <A> AmbExc.reflect(branch: Branch<A>?): A {
    branch.forEach {
      if (flip()) return@reflect it
    }
    raise()
  }

  // TODO some way to know which is the last value
  suspend inline fun <A> Branch<A>?.forEach(block: (A) -> Unit) {
    var branch = this
    while (branch != null) {
      block(branch.value)
      branch = branch.next()
    }
  }

  // TODO could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
//  likely no because interleave delimits its arguments
  suspend fun <A> AmbExc.interleave(first: suspend AmbExc.() -> A, second: suspend AmbExc.() -> A): A {
    val (res, branch) = split(first) ?: return second()
    return if (flip()) res
    else interleave2({ split(second) }, branch)
  }

  tailrec suspend fun <A> AmbExc.interleave2(first: suspend () -> Branch<A>?, second: suspend () -> Branch<A>?): A {
    val (res, branch) = first() ?: return reflect(second())
    return if (flip()) res
    else interleave2(second, branch)
  }

  // TODO could we make this tailrec?
  suspend fun <A, B> AmbExc.fairBind(first: suspend AmbExc.() -> A, second: suspend AmbExc.(A) -> B): B {
    val (res, branch) = split(first) ?: raise()
    return interleave({ second(res) }) { fairBind({ reflect(branch()) }, second) }
  }

  suspend fun <A, B> AmbExc.fairBind2(first: suspend () -> Branch<A>?, second: suspend AmbExc.(A) -> B): B {
    val (res, branch) = first() ?: raise()
    return interleave({ second(res) }) { fairBind2(branch, second) }
  }

  // TODO I think nullOnFailure or noneOnFailure is a better replacement
  suspend inline fun <A, B> AmbExc.ifte(
    noinline condition: suspend AmbExc.() -> A,
    then: (A) -> B,
    otherwise: () -> B
  ): B {
    val res = split(condition) ?: return otherwise()
    return then(reflect(res))
  }

  suspend fun <A> AmbExc.nullOnFailure(
    block: suspend AmbExc.() -> A,
  ): A? = split(block)?.let { reflect(it) }

  suspend fun <A> AmbExc.noneOnFailure(
    block: suspend AmbExc.() -> A,
  ): Option<A> = split(block)?.let { Some(reflect(it)) } ?: None

  suspend fun <A> Exc.once(block: suspend AmbExc.() -> A): A {
    val (res, _) = split(block) ?: raise()
    return res
  }

  suspend fun <A> Exc.onceOrNull(block: suspend AmbExc.() -> A): A? {
    val (res, _) = split(block) ?: return null
    return res
  }

  suspend fun <A> Exc.onceOrNone(block: suspend AmbExc.() -> A): Option<A> {
    val (res, _) = split(block) ?: return None
    return Some(res)
  }

  suspend fun <A> AmbExc.gnot(block: suspend AmbExc.() -> A) = ifte({ once(block) }, { raise() }) { }
  suspend fun <A> AmbExc.gnot2(block: suspend AmbExc.() -> A) {
    noneOnFailure { once(block) }.onSome { raise() }
  }

  suspend fun <A> Exc.gnot3(block: suspend AmbExc.() -> A) {
    if (succeeds(block)) raise()
  }

  suspend fun <A> succeeds(block: suspend AmbExc.() -> A) = split(block) != null

  suspend fun <A> bagOfN(count: Int = -1, block: suspend AmbExc.() -> A): List<A> = bagOfNRec(count, emptyList(), block)

  private tailrec suspend fun <A> bagOfNRec(count: Int, acc: List<A>, block: suspend AmbExc.() -> A): List<A> {
    if (count < -1 || count == 0) return acc
    val (res, branch) = split(block) ?: return acc
    return bagOfNRec(if (count == -1) -1 else count - 1, acc + res) { reflect(branch()) }
  }

  suspend fun <A> bagOfN2(count: Int = -1, block: suspend AmbExc.() -> A): List<A> =
    bagOfNRec2(count, emptyList()) { split(block) }

  private tailrec suspend fun <A> bagOfNRec2(count: Int, acc: List<A>, branch: suspend () -> Branch<A>?): List<A> {
    if (count < -1 || count == 0) return acc
    val (res, branch) = branch() ?: return acc
    return bagOfNRec2(if (count == -1) -1 else count - 1, acc + res, branch)
  }
}

interface Logic : AmbExc {
  suspend fun <A> split(block: suspend Logic.() -> A): Pair<A, suspend Logic.() -> A>?

  companion object {
    sealed interface Tree<out A>
    data object HZero : Tree<Nothing>
    data class HOne<out A>(val value: A) : Tree<A>
    data class HChoice<out A>(val value: A, val next: suspend () -> Tree<A>) : Tree<A>

    class LogicImpl<A>(p: HandlerPrompt<Tree<A>>) : Logic, Handler<Tree<A>> by p {
      override suspend fun raise(msg: String): Nothing = discardWithFast(Result.success(HZero))
      override suspend fun flip(): Boolean = use { resume ->
        composeTrees(resume(true)) { resume(false) }
      }

      override suspend fun <A> split(block: suspend Logic.() -> A): Pair<A, suspend Logic.() -> A>? =
        reflectSR(reifyLogic(block))
    }

    private fun <A> reflectSR(tree: Tree<A>): Pair<A, suspend Logic.() -> A>? = when (tree) {
      HZero -> null
      is HOne -> Pair(tree.value) { raise() }
      is HChoice -> {
        val next = tree.next
        Pair(tree.value) { reflect(reflectSR(next())) }
      }
    }

    suspend fun <A> Logic.reflect(pair: Pair<A, suspend Logic.() -> A>?): A {
      val (value, next) = pair ?: raise()
      return if (flip()) value else next()
    }

    private suspend fun <A> composeTrees(value: Tree<A>, next: suspend () -> Tree<A>): Tree<A> = when (value) {
      is HZero -> next()
      is HOne -> HChoice(value.value, next)
      is HChoice -> {
        val nextPrime = value.next
        HChoice(value.value) { composeTrees(nextPrime(), next) }
      }
    }

    private suspend fun <A> reifyLogic(block: suspend Logic.() -> A): Tree<A> = handle {
      HOne(block(LogicImpl(this)))
    }

    // TODO could we make this return Boolean? maybe would need access to the o.g. prompt I guess, or we can do this in some block
    tailrec suspend fun <A> Logic.interleave(first: suspend Logic.() -> A, second: suspend Logic.() -> A): A {
      val (res, branch) = split(first) ?: return second()
      return if (flip()) res
      else interleave(second, branch)
    }

    // TODO could we make this tailrec?
    suspend fun <A, B> Logic.fairBind(first: suspend Logic.() -> A, second: suspend Logic.(A) -> B): B {
      val (res, branch) = split(first) ?: raise()
      return interleave({ second(res) }) { fairBind(branch, second) }
    }

    // TODO I think nullOnFailure is a better replacement (or an Option variant)
    suspend inline fun <A, B> Logic.ifte(
      noinline condition: suspend Logic.() -> A,
      then: (A) -> B,
      otherwise: () -> B
    ): B {
      val (res, branch) = split(condition) ?: return otherwise()
      // TODO I think we can take out the `then` here
      return if (flip()) then(res)
      else then(branch())
    }

    suspend fun <A> Logic.nullOnFailure(
      block: suspend Logic.() -> A,
    ): A? = split(block)?.let { reflect(it) }

    suspend fun <A> Logic.noneOnFailure(
      block: suspend Logic.() -> A,
    ): Option<A> = split(block)?.let { Some(reflect(it)) } ?: None

    suspend fun <A> Logic.once(block: suspend Logic.() -> A): A {
      val (res, _) = split(block) ?: raise()
      return res
    }

    suspend fun <A> Logic.gnot(block: suspend Logic.() -> A) = ifte({ once(block) }, { raise() }) { }
    suspend fun <A> Logic.gnot2(block: suspend Logic.() -> A) {
      noneOnFailure { once(block) }.onSome { raise() }
    }

    suspend fun <A> Logic.gnot3(block: suspend Logic.() -> A) {
      if (succeeds(block)) raise()
    }

    suspend fun <A> Logic.succeeds(block: suspend Logic.() -> A) = split(block) != null

    suspend fun <A> Logic.bagOfN(count: Int = -1, block: suspend Logic.() -> A): List<A> =
      bagOfNRec(count, emptyList(), block)

    private tailrec suspend fun <A> Logic.bagOfNRec(count: Int, acc: List<A>, block: suspend Logic.() -> A): List<A> {
      if (count < -1 || count == 0) return emptyList()
      val (res, branch) = split(block) ?: return emptyList()
      return bagOfNRec(if (count == -1) -1 else count - 1, acc + res, branch)
    }
  }
}