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
  private suspend fun decr() {
    val x = state.get()
    if (x > 0) state.set(x - 1) else raise(Unit)
  }

  context(r: Raise<Unit>, recover: Recover, state: State<Int>)
  private suspend fun tripleDecr() {
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
        recover.recover({ raise(Unit) }) {}
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
        recover.recover<Nothing, Nothing>({ raise(Unit) }) { it }
      }
    } shouldBe Left(Unit)
  }

  suspend fun Amb.action1(err: HExc<Unit>) = err.recover({
    if (flip()) true else raise(Unit)
  }) { false }

  suspend fun Amb.action2(err: HExc<Unit>) = err.recover({
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

interface Recover {
  suspend fun <E, A> recover(block: suspend Raise<E>.() -> A, recover: suspend (E) -> A): A
}

interface HExc<E> : Recover, Raise<E>

suspend fun <E, A> runHExcTransactional(block: suspend HExc<E>.() -> A): Either<E, A> = handle {
  block(object : HExc<E> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))
    override suspend fun <E, A> recover(block: suspend Raise<E>.() -> A, recover: suspend (E) -> A): A {
      val res: Either<E, HExc<E>> = use { resume ->
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

suspend fun <E, A> runHExc(block: suspend HExc<E>.() -> A): Either<E, A> = handle {
  block(object : HExc<E> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))
    override suspend fun <E, A> recover(block: suspend Raise<E>.() -> A, recover: suspend (E) -> A): A =
      runHExc(block).getOrElse { recover(it) }
  }).right()
}

suspend fun <E, A> runHExcSubJump(block: suspend HExc<E>.() -> A): Either<E, A> = runEither {
  subJump {
    block(object : HExc<E>, Recover by recover, Raise<E> by this@runEither {})
  }
}

suspend fun <E, A> runEither(block: suspend Raise<E>.() -> A): Either<E, A> = handle {
  block(Raise(::Left)).right()
}

val SubJump.recover: Recover
  get() = object : Recover {
    override suspend fun <E, A> recover(block: suspend Raise<E>.() -> A, recover: suspend (E) -> A): A =
      sub({ jump -> runEither(block).getOrElse { jump(it) } }, { recover(it) })
  }

suspend fun <E, A> hRecover(block: suspend HExc<E>.() -> A): Either<E, A> = handle {
  block(object : HExc<E> {
    override fun raise(r: E) = discardWith(Result.success(Left(r)))
    override suspend fun <E, A> recover(block: suspend Raise<E>.() -> A, recover: suspend (E) -> A): A = use { resume ->
      resume.locally {
        hRecover(block).getOrElse { error ->
          discard { resume.locally { recover(error) } }
        }
      }
    }
  }).right()
}

suspend fun <T, R> runStatePair(value: T, body: suspend State<T>.() -> R): Pair<T, R> = runState(value) {
  val res = body()
  get() to res
}