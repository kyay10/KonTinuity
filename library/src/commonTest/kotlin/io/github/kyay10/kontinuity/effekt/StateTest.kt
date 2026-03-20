package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.Stateful
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class StateTest {
  @Test
  fun simpleRead() = runTestCC {
    stateFun(42) { getValue() } shouldBe 42
  }

  @Test
  fun countDown() = runTestCC {
    suspend fun State<Int>.countDown(): List<Int> {
      val printed = mutableListOf(Int.MIN_VALUE)
      while (getValue() > 0) {
        printed.add(getValue())
        put(getValue() - 1)
      }
      printed.add(getValue())
      return printed
    }
    stateFun(10) {
      countDown()
    } shouldBe listOf(Int.MIN_VALUE, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
  }

  @Test
  fun silentCountDown() = runTestCC(timeout = 10.minutes) {
    suspend fun State<Int>.countDown() {
      while (getValue() > 0) {
        put(getValue() - 1)
      }
    }
    stateFun(10_000) {
      countDown()
    }
  }
}

open class SpecialState<R, S>(prompt: StatefulPrompt<R, Data<S>>) : State<S>,
  StatefulHandler<R, SpecialState.Data<S>> by prompt {
  data class Data<S>(var state: S) : Stateful<Data<S>> {
    override fun fork() = copy()
  }

  override suspend fun getValue(): S = (this as StatefulHandler<R, Data<S>>).value.state
  override suspend fun put(value: S) {
    (this as StatefulHandler<R, Data<S>>).value.state = value
  }
}

class StateFun<R, S>(p: HandlerPrompt<suspend (S) -> R>) : State<S>, Handler<suspend (S) -> R> by p {
  override suspend fun getValue(): S = useOnce { k ->
    { s ->
      k(s)(s)
    }
  }

  override suspend fun put(value: S) = useOnce { k ->
    {
      k(Unit)(value)
    }
  }
}

suspend fun <R, S> stateFun(init: S, block: suspend State<S>.() -> R): R = handle {
  val res = block(StateFun(this))
  suspendOneArg { res }
}(init)

private fun <S, R> suspendOneArg(block: suspend (S) -> R): suspend (S) -> R = block

interface State<S> {
  suspend fun getValue(): S
  suspend fun put(value: S)
}