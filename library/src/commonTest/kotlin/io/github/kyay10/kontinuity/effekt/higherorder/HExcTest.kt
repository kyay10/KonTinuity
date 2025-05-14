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
    val x = get()
    if (x > 0) set(x - 1) else raise(Unit)
  }

  context(r: Raise<Unit>, recover: Recover, state: State<Int>)
  private suspend fun MultishotScope.tripleDecr() {
    decr()
    recover({
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
      runHExc<Unit, _> {
        bagOfN {
          recover({
            if (flip()) raise(Unit)
            true
          }) { false }
        }
      } shouldBe Right(listOf(false, true))
      runHExcTransactional<Unit, _> {
        bagOfN {
          recover({
            if (flip()) raise(Unit)
            true
          }) { false }
        }
      } shouldBe Right(listOf(false))
      subJump {
        bagOfN {
          with(recover) {
            recover({
            if (flip()) raise(Unit)
            true
            }) { false }
          }
        }
      } shouldBe listOf(false)
    }
  }

  @Test
  fun shortCircuitTest() = runTestCC {
    runHExc<Unit, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runHExcTransactional<Unit, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runEither<Nothing, _> {
      subJump {
        with(recover) { recover({ raise(Unit) }) {} }
      }
    } shouldBe Right(Unit)
    runHExc {
      recover<Nothing, Nothing>({
        raise(Unit)
      }) { it }
    } shouldBe Left(Unit)
    runHExcTransactional {
      recover<Nothing, Nothing>({
        raise(Unit)
      }) { it }
    } shouldBe Left(Unit)
    runEither {
      subJump {
        with(recover) { recover<Nothing, Nothing>({ raise(Unit) }) { it } }
      }
    } shouldBe Left(Unit)
  }

  context(_: Amb, err: HExc<Unit>)
  suspend fun MultishotScope.action1() = recover({
    if (flip()) true else raise(Unit)
  }) { false }

  context(_: Amb, err: HExc<Unit>)
  suspend fun MultishotScope.action2() = recover({
    if (flip()) raise(Unit) else true
  }) { false }

  @Test
  fun nonDetAcidTestTransactional() = runTestCC {
    collect {
      runHExcTransactional {
        action1()
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExcTransactional {
      collect {
        action1()
      }
    } shouldBe Right(listOf(false))
    collect {
      runHExcTransactional {
        action2()
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExcTransactional {
      collect {
        action2()
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun nonDetAcidTestSimple() = runTestCC {
    collect {
      runHExc {
        action1()
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExc {
      collect {
        action1()
      }
    } shouldBe Right(listOf(true, false))
    collect {
      runHExc {
        action2()
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExc {
      collect {
        action2()
      }
    } shouldBe Right(listOf(false, true))
  }

  @Test
  fun nonDetAcidTestSubJump() = runTestCC {
    collect {
      runHExcSubJump {
        action1()
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExcSubJump {
      collect {
        action1()
      }
    } shouldBe Right(listOf(false))
    collect {
      runHExcSubJump {
        action2()
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExcSubJump {
      collect {
        action2()
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun nonDetAcidTestHRecover() = runTestCC {
    collect {
      hRecover {
        action1()
      }
    } shouldBe listOf(Right(true), Right(false))
    hRecover {
      collect {
        action1()
      }
    } shouldBe Right(listOf(false))
    collect {
      hRecover {
        action2()
      }
    } shouldBe listOf(Right(false), Right(true))
    hRecover {
      collect {
        action2()
      }
    } shouldBe Right(listOf(false))
  }
}

interface Recover {
  suspend fun <E, A> MultishotScope.recover(
    block: suspend context(Raise<E>) MultishotScope.() -> A,
    recover: suspend MultishotScope.(E) -> A
  ): A
}

context(r: Recover)
suspend fun <E, A> MultishotScope.recover(
  block: suspend context(Raise<E>) MultishotScope.() -> A,
  recover: suspend MultishotScope.(E) -> A
): A =
  with(r) { recover(block, recover) }

interface HExc<E> : Recover, Raise<E>

suspend fun <E, A> MultishotScope.runHExcTransactional(block: suspend context(HExc<E>) MultishotScope.() -> A): Either<E, A> =
  handle {
  block(object : HExc<E> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))
    override suspend fun <E, A> MultishotScope.recover(
      block: suspend context(Raise<E>) MultishotScope.() -> A,
      recover: suspend MultishotScope.(E) -> A
    ): A {
      val res: Either<E, HExc<E>> = use { resume ->
        runHExcTransactional {
          resume(given<HExc<E>>().right())
        }.getOrElse {
          resume(it.left())
        }
      }
      return res.fold({ recover(it) }, { with(it) { block() } })
    }
  }, this).right()
}

suspend fun <E, A> MultishotScope.runHExc(block: suspend context(HExc<E>) MultishotScope.() -> A): Either<E, A> =
  handle {
  block(object : HExc<E> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))
    override suspend fun <E, A> MultishotScope.recover(
      block: suspend context(Raise<E>) MultishotScope.() -> A,
      recover: suspend MultishotScope.(E) -> A
    ): A =
      runHExc(block).getOrElse { recover(it) }
  }, this).right()
}

suspend fun <E, A> MultishotScope.runHExcSubJump(block: suspend context(HExc<E>) MultishotScope.() -> A): Either<E, A> =
  runEither {
  subJump {
    block(object : HExc<E>, Recover by recover, Raise<E> by given<Raise<E>>() {}, this)
  }
}

suspend fun <E, A> MultishotScope.runEither(block: suspend context(Raise<E>) MultishotScope.() -> A): Either<E, A> =
  handle {
    block(Raise(::Left), this).right()
}

context(_: SubJump)
val recover: Recover
  get() = object : Recover {
    override suspend fun <E, A> MultishotScope.recover(
      block: suspend context(Raise<E>) MultishotScope.() -> A,
      recover: suspend MultishotScope.(E) -> A
    ): A =
      sub({ jump -> runEither(block).getOrElse { jump(it) } }, { recover(it) })
  }

suspend fun <E, A> MultishotScope.hRecover(block: suspend context(HExc<E>) MultishotScope.() -> A): Either<E, A> =
  handle {
  block(object : HExc<E> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))
    override suspend fun <E, A> MultishotScope.recover(
      block: suspend context(Raise<E>) MultishotScope.() -> A,
      recover: suspend MultishotScope.(E) -> A
    ): A = use { resume ->
      resume.locally {
        hRecover(block).getOrElse { error ->
          discard { resume.locally { recover(error) } }
        }
      }
    }
  }, this).right()
}

suspend fun <T, R> MultishotScope.runStatePair(
  value: T,
  body: suspend context(State<T>) MultishotScope.() -> R
): Pair<T, R> = runState(value) {
  val res = body()
  get() to res
}