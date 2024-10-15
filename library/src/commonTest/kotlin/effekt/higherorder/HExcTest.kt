package effekt.higherorder

import State
import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import control0
import effekt.*
import get
import io.kotest.matchers.shouldBe
import runState
import runTestCC
import set
import kotlin.test.Test

class HExcTest {
  suspend fun HExc<Unit>.decr(state: State<Int>) {
    val x = state.get()
    if (x > 0) state.set(x - 1) else raise(Unit)
  }

  suspend fun HExc<Unit>.tripleDecr(state: State<Int>) {
    decr(state)
    recover({
      decr(state)
      decr(state)
    }) {}
  }

  @Test
  fun tripleDecrTest() = runTestCC {
    runHExc<Unit, _> {
      runStateReturningBoth(2) {
        tripleDecr(this)
      }
    } shouldBe Right(1 to Unit)
    runStateReturningBoth(2) {
      runHExc {
        tripleDecr(this@runStateReturningBoth)
      }
    } shouldBe (0 to Right(Unit))
  }

  @Test
  fun withNonDetTest() = runTestCC {
    runHExc<Unit, _> {
      LogicDeep.bagOfN {
        recover({
          if (flip()) raise(Unit)
          true
        }) { false }
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun shortCircuitTest() = runTestCC {
    runHExc<Unit, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runHExc<Unit, _> {
      recover({
        this@runHExc.raise(Unit)
      }) { }
    } shouldBe Left(Unit)
  }

  @Test
  fun tripleDecrTest2() = runTestCC {
    runHExcControlO<Unit, _> {
      runStateReturningBoth(2) {
        tripleDecr(this)
      }
    } shouldBe Right(1 to Unit)
    runStateReturningBoth(2) {
      runHExcControlO {
        tripleDecr(this@runStateReturningBoth)
      }
    } shouldBe (0 to Right(Unit))
  }

  @Test
  fun withNonDetTest2() = runTestCC {
    runHExcControlO<Unit, _> {
      LogicDeep.bagOfN {
        recover({
          if (flip()) raise(Unit)
          true
        }) { false }
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun shortCircuitTest2() = runTestCC {
    runHExcControlO<Unit, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runHExcControlO<Unit, _> {
      recover({
        this@runHExcControlO.raise(Unit)
      }) { }
    } shouldBe Right(Unit)
  }

  @Test
  fun tripleDecrTestSimple() = runTestCC {
    runHExcSimple<Unit, _> {
      runStateReturningBoth(2) {
        tripleDecr(this)
      }
    } shouldBe Right(0 to Unit)
    runStateReturningBoth(2) {
      runHExcSimple {
        tripleDecr(this@runStateReturningBoth)
      }
    } shouldBe (0 to Right(Unit))
  }

  @Test
  fun withNonDetTestSimple() = runTestCC {
    runHExcSimple<Unit, _> {
      LogicDeep.bagOfN {
        recover({
          if (flip()) raise(Unit)
          true
        }) { false }
      }
    } shouldBe Right(listOf(false, true))
  }

  @Test
  fun shortCircuitTestSimple() = runTestCC {
    runHExcSimple<Unit, _> {
      recover({
        raise(Unit)
      }) { }
    } shouldBe Right(Unit)
    runHExcSimple<Unit, _> {
      recover({
        this@runHExcSimple.raise(Unit)
      }) { }
    } shouldBe Left(Unit)
  }

  suspend fun Amb.action1(err: HExc<Unit>) = err.recover({
    if (flip()) true else raise(Unit)
  }) { false }

  suspend fun Amb.action2(err: HExc<Unit>) = err.recover({
    if (flip()) raise(Unit) else true
  }) { false }

  @Test
  fun nonDetAcidTest() = runTestCC {
    collect {
      runHExc {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExc {
      collect {
        action1(this@runHExc)
      }
    } shouldBe Right(listOf(false))
    collect {
      runHExc {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExc {
      collect {
        action2(this@runHExc)
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun nonDetAcidTest2() = runTestCC {
    collect {
      runHExcControlO {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExcControlO {
      collect {
        action1(this@runHExcControlO)
      }
    } shouldBe Left(Unit)
    collect {
      runHExcControlO {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExcControlO {
      collect {
        action2(this@runHExcControlO)
      }
    } shouldBe Right(listOf(false))
  }

  @Test
  fun nonDetAcidTestSimple() = runTestCC {
    collect {
      runHExcSimple {
        action1(this)
      }
    } shouldBe listOf(Right(true), Right(false))
    runHExcSimple {
      collect {
        action1(this@runHExcSimple)
      }
    } shouldBe Right(listOf(true, false))
    collect {
      runHExcSimple {
        action2(this)
      }
    } shouldBe listOf(Right(false), Right(true))
    runHExcSimple {
      collect {
        action2(this@runHExcSimple)
      }
    } shouldBe Right(listOf(false, true))
  }
}

interface HExc<E> {
  suspend fun raise(value: E): Nothing
  suspend fun <A> recover(block: suspend HExc<E>.() -> A, recover: suspend (E) -> A): A
}

suspend fun <E, A> runHExc(block: suspend HExc<E>.() -> A): Either<E, A> = handle {
  block(object : HExc<E> {
    override suspend fun raise(value: E) = discardWithFast(Result.success(Left(value)))
    override suspend fun <A> recover(block: suspend HExc<E>.() -> A, recover: suspend (E) -> A): A {
      val res: Either<E, HExc<E>> = use { resume ->
        runHExc {
          resume(this.right())
        }.getOrElse {
          resume(it.left())
        }
      }
      return res.fold({ recover(it) }, { block(it) })
    }
  }).right()
}

suspend fun <E, A> runHExcControlO(block: suspend HExc<E>.() -> A): Either<E, A> = handle {
  block(object : HExc<E> {
    override suspend fun raise(value: E) = discardWithFast(Result.success(Left(value)))
    override suspend fun <A> recover(block: suspend HExc<E>.() -> A, recover: suspend (E) -> A): A {
      val res: Either<E, HExc<E>> = use { resume ->
        rehandle {
          resume(this.right()).fold({
            resume(it.left())
          }, ::Right)
        }
      }
      return res.fold({ recover(it) }, {
        block(it).also { prompt.control0 { it(Unit) } }
      })
    }
  }).right()
}

suspend fun <E, A> runHExcSimple(block: suspend HExc<E>.() -> A): Either<E, A> = handle {
  block(object : HExc<E> {
    override suspend fun raise(value: E) = discardWithFast(Result.success(Left(value)))
    override suspend fun <A> recover(block: suspend HExc<E>.() -> A, recover: suspend (E) -> A): A =
      runHExcSimple(block).getOrElse { recover(it) }
  }).right()
}

suspend fun <T, R> runStateReturningBoth(value: T, body: suspend State<T>.() -> R): Pair<T, R> = runState(value) {
  val res = body()
  get() to res
}