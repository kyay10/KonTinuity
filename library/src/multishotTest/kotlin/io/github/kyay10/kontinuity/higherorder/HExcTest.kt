package io.github.kyay10.kontinuity.higherorder

import arrow.core.*
import io.github.kyay10.kontinuity.*
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

class HExcTest {
  context(_: Exc, state: State<Int>)
  private suspend fun decr() {
    ensure(state.value > 0)
    state.value--
  }

  context(_: Exc, recover: Recover, _: State<Int>)
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
    } shouldEq Some(0 to Unit)
    runHExcTransactional {
      runStatePair(2) {
        tripleDecr()
      }
    } shouldEq Some(1 to Unit)
    runStatePair(2) {
      runHExc {
        tripleDecr()
      }
    } shouldEq (0 to Some(Unit))
    maybe {
      subJump {
        runStatePair(2) {
          with(recover) { tripleDecr() }
        }
      }
    } shouldEq Some(1 to Unit)
    runStatePair(2) {
      runHExcTransactional {
        tripleDecr()
      }
    } shouldEq (0 to Some(Unit))
    runStatePair(2) {
      subJump {
        maybe { with(recover) { tripleDecr() } }
      }
    } shouldEq (0 to Some(Unit))
  }

  @Test
  fun withNonDetTest() = runTestCC {
    runHExc {
      bagOfN {
        recover({
          ensure(!flip())
          true
        }) { false }
      }
    } shouldEq Some(persistentListOf(false, true))
    runHExcTransactional {
      bagOfN {
        recover({
          ensure(!flip())
          true
        }) { false }
      }
    } shouldEq Some(persistentListOf(false))
    subJump {
      bagOfN {
        recover.recover({
          ensure(!flip())
          true
        }) { false }
      }
    } shouldEq listOf(false)
  }

  @Test
  fun shortCircuitTest() = runTestCC {
    runHExc { recover({ raise() }) { } } shouldEq Some(Unit)
    runHExcTransactional { recover({ raise() }) { } } shouldEq Some(Unit)
    maybe { subJump { recover.recover({ raise() }) {} } } shouldEq Some(Unit)
    runHExc { recover({ this@runHExc.raise() }) { } } shouldEq None
    runHExcTransactional { recover({ this@runHExcTransactional.raise() }) { } } shouldEq None
    maybe { subJump { recover.recover({ this@maybe.raise() }) { } } } shouldEq None
  }

  context(_: HExc, _: Amb)
  suspend fun action1() = recover({
    ensure(flip())
    true
  }) { false }

  context(_: HExc, _: Amb)
  suspend fun action2() = recover({
    ensure(!flip())
    true
  }) { false }

  @Test
  fun nonDetAcidTestTransactional() = runTestCC {
    ambList { runHExcTransactional { action1() } } shouldEq listOf(Some(true), Some(false))
    runHExcTransactional { ambList { action1() } } shouldEq Some(listOf(false))
    ambList { runHExcTransactional { action2() } } shouldEq listOf(Some(false), Some(true))
    runHExcTransactional { ambList { action2() } } shouldEq Some(listOf(false))
  }

  @Test
  fun nonDetAcidTestSimple() = runTestCC {
    ambList { runHExc { action1() } } shouldEq listOf(Some(true), Some(false))
    runHExc { ambList { action1() } } shouldEq Some(listOf(true, false))
    ambList { runHExc { action2() } } shouldEq listOf(Some(false), Some(true))
    runHExc { ambList { action2() } } shouldEq Some(listOf(false, true))
  }

  @Test
  fun nonDetAcidTestSubJump() = runTestCC {
    ambList { runHExcSubJump { action1() } } shouldEq listOf(Some(true), Some(false))
    runHExcSubJump { ambList { action1() } } shouldEq Some(listOf(false))
    ambList { runHExcSubJump { action2() } } shouldEq listOf(Some(false), Some(true))
    runHExcSubJump { ambList { action2() } } shouldEq Some(listOf(false))
  }

  @Test
  fun nonDetAcidTestHRecover() = runTestCC {
    ambList { hRecover { action1() } } shouldEq listOf(Some(true), Some(false))
    hRecover { ambList { action1() } } shouldEq Some(listOf(false))
    ambList { hRecover { action2() } } shouldEq listOf(Some(false), Some(true))
    hRecover { ambList { action2() } } shouldEq Some(listOf(false))
  }
}

interface Recover {
  suspend fun <A> recover(block: suspend Exc.() -> A, recover: suspend () -> A): A
}

context(r: Recover)
suspend fun <A> recover(block: suspend Exc.() -> A, recover: suspend () -> A): A = r.recover(block, recover)

interface HExc : Recover, Exc

suspend fun <A> runHExcTransactional(block: suspend HExc.() -> A): Option<A> = handle {
  block(object : HExc, Exc by exc {
    override suspend fun <A> recover(block: suspend Exc.() -> A, recover: suspend () -> A): A {
      val res: Option<HExc> = use { resume ->
        runHExcTransactional {
          resume(this.some())
        }.getOrElse {
          resume(None)
        }
      }
      return res.fold({ recover() }, { block(it) })
    }
  }).some()
}

suspend fun <A> runHExc(block: suspend HExc.() -> A): Option<A> = handle {
  block(object : HExc, Exc by exc {
    override suspend fun <A> recover(block: suspend Exc.() -> A, recover: suspend () -> A): A =
      runHExc(block).getOrElse { recover() }
  }).some()
}

suspend fun <A> runHExcSubJump(block: suspend HExc.() -> A): Option<A> = maybe {
  subJump {
    block(object : HExc, Recover by recover, Exc by this@maybe {})
  }
}

val SubJump.recover: Recover
  get() = object : Recover {
    override suspend fun <A> recover(block: suspend Exc.() -> A, recover: suspend () -> A): A =
      sub({ jump -> maybe(block).getOrElse { jump(Unit) } }, { recover() })
  }

suspend fun <A> hRecover(block: suspend HExc.() -> A): Option<A> = handle {
  block(object : HExc, Exc by exc {
    override suspend fun <A> recover(block: suspend Exc.() -> A, recover: suspend () -> A): A = use { resume ->
      resume locally {
        hRecover(block).getOrElse {
          discard { resume locally { recover() } }
        }
      }
    }
  }).some()
}

suspend fun <T, R> runStatePair(value: T, body: suspend State<T>.() -> R): Pair<T, R> = runState(value) {
  val res = body()
  this.value to res
}