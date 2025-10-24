package io.github.kyay10.kontinuity.effekt.higherorder

import arrow.core.*
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.raise.Raise
import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.effekt.Amb
import io.github.kyay10.kontinuity.effekt.LogicDeep
import io.github.kyay10.kontinuity.effekt.LogicSimple
import io.github.kyay10.kontinuity.effekt.LogicTree
import io.github.kyay10.kontinuity.effekt.bagOfN
import io.github.kyay10.kontinuity.effekt.collect
import io.github.kyay10.kontinuity.effekt.discard
import io.github.kyay10.kontinuity.effekt.discardWith
import io.github.kyay10.kontinuity.effekt.flip
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use
import io.kotest.matchers.shouldBe
import kotlin.Int
import kotlin.Nothing
import kotlin.Pair
import kotlin.Result
import kotlin.Unit
import kotlin.collections.listOf
import kotlin.test.Test
import kotlin.to
import kotlin.with

class HExcTest {
  context(r: Raise<Unit>, state: State<Int>)
  private fun decr() {
    val x = state.get()
    if (x > 0) state.set(x - 1) else raise(Unit)
  }

  context(r: Raise<Unit>, recover: Recover<Region>, state: State<Int>, _: MultishotScope<Region>)
  private suspend fun <Region> tripleDecr() {
    decr()
    recover.recover({
      decr()
      decr()
    }) {}
  }

  @Test
  fun tripleDecrTest() = runTestCC {
    runHExc {
      runStatePair(2) {
        tripleDecr()
      }
    } shouldBe Right(0 to Unit)
    runHExcTransactional {
      runStatePair(2) {
        tripleDecr()
      }
    } shouldBe Right(1 to Unit)
    runStatePair(2) {
      runHExc {
        tripleDecr()
      }
    } shouldBe (0 to Right(Unit))
    runEither {
      subJump {
        runStatePair(2) {
          with(recover) { tripleDecr() }
        }
      }
    } shouldBe Right(1 to Unit)
    runStatePair(2) {
      runHExcTransactional {
        tripleDecr()
      }
    } shouldBe (0 to Right(Unit))
    runStatePair(2) {
      subJump {
        runEither { with(recover) { tripleDecr() } }
      }
    } shouldBe (0 to Right(Unit))
  }

  @Test
  fun withNonDetTest() = runTestCC {
    for (logic in listOf(LogicDeep, LogicTree, LogicSimple)) with(logic) {
      runHExc<Unit, _, _> {
        bagOfN {
          recover({
            if (flip()) raise(Unit)
            true
          }) { false }
        }
      } shouldBe Right(listOf(false, true))
      runHExcTransactional<Unit, _, _> {
        bagOfN {
          recover({
            if (flip()) raise(Unit)
            true
          }) { false }
        }
      } shouldBe Right(listOf(false))
      subJump {
        bagOfN {
          recover.recover({
            if (flip()) raise(Unit)
            true
          }) { false }
        }
      } shouldBe listOf(false)
    }
  }

  @Test
  fun shortCircuitTest() = runTestCC {
    runHExc<Unit, _, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runHExcTransactional<Unit, _, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runEither<Nothing, _, _> {
      subJump {
        recover.recover({ raise(Unit) }) {}
      }
    } shouldBe Right(Unit)
    runHExc {
      recover<Nothing, Nothing, _>({
        raise(Unit)
      }) { it }
    } shouldBe Left(Unit)
    runHExcTransactional {
      recover<Nothing, Nothing, _>({
        raise(Unit)
      }) { it }
    } shouldBe Left(Unit)
    runEither {
      subJump {
        recover.recover<Nothing, Nothing, _>({ raise(Unit) }) { it }
      }
    } shouldBe Left(Unit)
  }

  context(_: MultishotScope<Region>)
  suspend fun <Region> Amb<Region>.action1(err: HExc<Unit, Region>) = err.recover({
    if (flip()) true else raise(Unit)
  }) { false }

  context(_: MultishotScope<Region>)
  suspend fun <Region> Amb<Region>.action2(err: HExc<Unit, Region>) = err.recover({
    if (flip()) raise(Unit) else true
  }) { false }

  @Test
  fun nonDetAcidTestTransactional() = runTestCC {
    collect {
      runHExcTransactional {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExcTransactional {
      collect {
        action1(this@runHExcTransactional)
      }
    } shouldBe Right(listOf(false))
    collect {
      runHExcTransactional {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExcTransactional {
      collect {
        action2(this@runHExcTransactional)
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun nonDetAcidTestSimple() = runTestCC {
    collect {
      runHExc {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExc {
      collect {
        action1(this@runHExc)
      }
    } shouldBe Right(listOf(true, false))
    collect {
      runHExc {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExc {
      collect {
        action2(this@runHExc)
      }
    } shouldBe Right(listOf(false, true))
  }

  @Test
  fun nonDetAcidTestSubJump() = runTestCC {
    collect {
      runHExcSubJump {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExcSubJump {
      collect {
        action1(this@runHExcSubJump)
      }
    } shouldBe Right(listOf(false))
    collect {
      runHExcSubJump {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExcSubJump {
      collect {
        action2(this@runHExcSubJump)
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun nonDetAcidTestHRecover() = runTestCC {
    collect {
      hRecover {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    hRecover {
      collect {
        action1(this@hRecover)
      }
    } shouldBe Right(listOf(false))
    collect {
      hRecover {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    hRecover {
      collect {
        action2(this@hRecover)
      }
    } shouldBe Right(listOf(false))
  }
}

interface Recover<in Region> {
  context(_: MultishotScope<IR>)
  suspend fun <E, A, IR: Region> recover(
    block: suspend context(MultishotScope<IR>) Raise<E>.() -> A,
    recover: suspend context(MultishotScope<IR>) (E) -> A
  ): A
}

interface HExc<E, in Region> : Recover<Region>, Raise<E>

context(_: MultishotScope<Region>)
suspend fun <E, A, Region> runHExcTransactional(block: suspend context(NewScope<Region>) HExc<E, NewRegion>.() -> A): Either<E, A> = handle {
  block(object : HExc<E, HandleRegion> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))

    context(_: MultishotScope<IR>)
    override suspend fun <E, A, IR: HandleRegion> recover(
      block: suspend context(MultishotScope<IR>) Raise<E>.() -> A,
      recover: suspend context(MultishotScope<IR>) (E) -> A
    ): A {
      val res: Either<E, Raise<E>> = use { resume ->
        runHExcTransactional {
          resume(this.right())
        }.getOrElse {
          resume(it.left())
        }
      }
      return res.fold({ recover(it) }, { block(it) })
    }
  }).right()
}

context(_: MultishotScope<Region>)
suspend fun <E, A, Region> runHExc(block: suspend context(NewScope<Region>) HExc<E, NewRegion>.() -> A): Either<E, A> = handle {
  block(object : HExc<E, HandleRegion> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))

    context(_: MultishotScope<IR>)
    override suspend fun <E, A, IR: HandleRegion> recover(
      block: suspend context(MultishotScope<IR>) Raise<E>.() -> A,
      recover: suspend context(MultishotScope<IR>) (E) -> A
    ): A =
      runHExc(block).getOrElse { recover(it) }
  }).right()
}

context(_: MultishotScope<Region>)
suspend fun <E, A, Region> runHExcSubJump(block: suspend context(NewScope<Region>) HExc<E, NewRegion>.() -> A): Either<E, A> = runEither {
  subJump {
    block(object : HExc<E, SubJumpRegion>, Recover<SubJumpRegion> by recover, Raise<E> by this@runEither {})
  }
}

context(_: MultishotScope<Region>)
suspend fun <E, A, Region> runEither(block: suspend context(MultishotScope<Region>) Raise<E>.() -> A): Either<E, A> = handle {
  block(Raise(::Left)).right()
}

val <Region> SubJump<Region>.recover: Recover<Region>
  get() = object : Recover<Region> {
    context(_: MultishotScope<IR>)
    override suspend fun <E, A, IR: Region> recover(
      block: suspend context(MultishotScope<IR>) Raise<E>.() -> A,
      recover: suspend context(MultishotScope<IR>) (E) -> A
    ): A =
      sub({ jump -> runEither(block).getOrElse { jump(it) } }, { recover(it) })
  }

context(_: MultishotScope<Region>)
suspend fun <E, A, Region> hRecover(block: suspend context(NewScope<Region>) HExc<E, NewRegion>.() -> A): Either<E, A> = handle {
  block(object : HExc<E, HandleRegion> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))

    context(_: MultishotScope<IR>)
    override suspend fun <E, A, IR: HandleRegion> recover(
      block: suspend context(MultishotScope<IR>) Raise<E>.() -> A,
      recover: suspend context(MultishotScope<IR>) (E) -> A
    ): A = use<suspend context(MultishotScope<IR>) () -> A, _, _, _> { resume ->
      resume {
        hRecover(block).getOrElse { error ->
          discard { resume { recover(error) } }
        }
      }
    }()
  }).right()
}

context(_: MultishotScope<Region>)
suspend fun <T, R, Region> runStatePair(value: T, body: suspend context(MultishotScope<Region>) State<T>.() -> R): Pair<T, R> =
  runState(value) {
    val res = body()
    get() to res
  }