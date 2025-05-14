package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class StateTest {
  @Test
  fun simpleRead() = runTestCC {
    stateFun(42) { get() } shouldBe 42
  }

  @Test
  fun countDown8() = runTestCC {
    context(_: Amb)
    suspend fun MultishotScope.countDown(
      s1: State<Int>,
      s2: State<Int>,
      s3: State<Int>,
      s4: State<Int>,
      s5: State<Int>,
      s6: State<Int>,
      s7: State<Int>,
      s8: State<Int>
    ): Boolean {
      while (with(s1){ get() > 0 }) {
        with(s1) { put(get() - 1) }
        with(s2) { put(get() - 1) }
        with(s3) { put(get() - 1) }
        with(s4) { put(get() - 1) }
        with(s5) { put(get() - 1) }
        with(s6) { put(get() - 1) }
        with(s7) { put(get() - 1) }
        with(s8) { put(get() - 1) }
      }
      return flip()
    }

    val init = 1_000
    val res = ambList {
      specialState(init) s1@{
        val s1 = given<State<Int>>()
        specialState(init) s2@{
          val s2 = given<State<Int>>()
          specialState(init) s3@{
            val s3 = given<State<Int>>()
            specialState(init) s4@{
              val s4 = given<State<Int>>()
              specialState(init) s5@{
                val s5 = given<State<Int>>()
                specialState(init) s6@{
                  val s6 = given<State<Int>>()
                  specialState(init) s7@{
                    val s7 = given<State<Int>>()
                    specialState(init) s8@{
                      val s8 = given<State<Int>>()
                      countDown(s1, s2, s3, s4, s5, s6, s7, s8)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    res shouldBe listOf(true, false)
  }

  @Test
  fun countDown() = runTestCC {
    context(_: State<Int>)
    suspend fun MultishotScope.countDown(): List<Int> {
      val printed = mutableListOf(Int.MIN_VALUE)
      while (get() > 0) {
        printed.add(get())
        put(get() - 1)
      }
      printed.add(get())
      return printed
    }
    stateFun(10) {
      countDown()
    } shouldBe listOf(Int.MIN_VALUE, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
  }

  @Test
  fun silentCountDown() = runTestCC(timeout = 10.minutes) {
    context(_: State<Int>)
    suspend fun MultishotScope.countDown() {
      while (get() > 0) {
        put(get() - 1)
      }
    }
    stateFun(10_000) {
      countDown()
    }
  }

  @Test
  fun ambientState1() = runTestCC {
    context(_: Amb, _: CrazyState)
    suspend fun MultishotScope.ex1() {
      if (flip()) {
        put(1)
      }
    }
    ambList {
      myState(0) {
        ex1()
        get()
      }
    } shouldBe listOf(1, 0)
    myState(0) {
      ambList {
        ex1()
        get()
      }
    } shouldBe listOf(1, 1)
  }

  @Test
  fun ambientState2() = runTestCC {
    var isFirst = true
    myState(0) {
      get() shouldBe 0
      foo()
      get() shouldBe if (isFirst) 2 else 13
      isFirst = false
      put(42)
      get() shouldBe 42
    }
  }

  @Test
  fun ambientState3() = runTestCC {
    var first = true
    myState(0) {
      get() shouldBe 0
      bar()
      get() shouldBe if (first) 3 else 43
      put(42)
      get() shouldBe 42
      first = false
    }
  }
}

interface CrazyState : State<Int> {
  suspend fun MultishotScope.foo()
  suspend fun MultishotScope.bar()
}

context(cs: CrazyState)
suspend fun MultishotScope.foo() = with(cs) { foo() }
context(cs: CrazyState)
suspend fun MultishotScope.bar() = with(cs) { bar() }

class MyState<R>(prompt: StatefulPrompt<R, Data<Int>>) : CrazyState,
  SpecialState<R, Int>(prompt) {
  override suspend fun MultishotScope.foo() {
    put(2)
    use { k ->
      k(Unit)
      put(13)
      k(Unit)
    }
  }

  override suspend fun MultishotScope.bar() {
    put(2)
    use { k ->
      put(get() + 1)
      k(Unit)
      put(get() + 1)
      k(Unit)
    }
  }
}

suspend fun <R> MultishotScope.myState(init: Int, block: suspend context(CrazyState) MultishotScope.() -> R): R =
  handleStateful<R, SpecialState.Data<Int>>(SpecialState.Data(init)) {
    block(MyState(given<StatefulPrompt<R, SpecialState.Data<Int>>>()), this)
  }

open class SpecialState<R, S>(prompt: StatefulPrompt<R, Data<S>>) : State<S>,
  StatefulHandler<R, SpecialState.Data<S>> by prompt {
  data class Data<S>(var state: S) : Stateful<Data<S>> {
    override fun fork() = copy()
  }

  override suspend fun MultishotScope.get(): S = value.state
  override suspend fun MultishotScope.put(v: S) {
    value.state = v
  }
}

suspend fun <R, S> MultishotScope.specialState(init: S, block: suspend context(State<S>) MultishotScope.() -> R): R =
  handleStateful<R, SpecialState.Data<S>>(SpecialState.Data(init)) {
    block(SpecialState(given<StatefulPrompt<R, SpecialState.Data<S>>>()), this)
  }

class StateFun<R, S>(p: HandlerPrompt<suspend MultishotScope.(S) -> R>) : State<S>, Handler<suspend MultishotScope.(S) -> R> by p {
  override suspend fun MultishotScope.get(): S = useOnce { k ->
    { s ->
      k(s)(s)
    }
  }

  override suspend fun MultishotScope.put(v: S) = useOnce { k ->
    {
      k(Unit)(v)
    }
  }
}

suspend fun <R, S> MultishotScope.stateFun(init: S, block: suspend context(State<S>) MultishotScope.() -> R): R = handle {
  val res = block(StateFun(given<HandlerPrompt<suspend MultishotScope.(S) -> R>>()), this)
  suspendOneArg { res }
}(init)

private fun <S, R> suspendOneArg(block: suspend MultishotScope.(S) -> R): suspend MultishotScope.(S) -> R = block

interface State<S> {
  suspend fun MultishotScope.get(): S
  suspend fun MultishotScope.put(v: S)
}

context(s: State<S>)
suspend fun <S> MultishotScope.get(): S = with(s) { get() }

context(s: State<S>)
suspend fun <S> MultishotScope.put(value: S) = with(s) { put(value) }