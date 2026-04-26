package io.github.kyay10.kontinuity.hansei

import io.github.kyay10.kontinuity.runTestCC
import io.github.kyay10.kontinuity.shouldEq
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class NestedTest {
  context(_: Probabilistic, _: Memory)
  suspend fun testn11(): SearchTree<Boolean> {
    val initialFlip = flip()
    return exactReify { !initialFlip || flip() }
  }

  context(_: Probabilistic, _: Memory)
  suspend fun testn12(random: OcamlRandom): SearchTree<Boolean> {
    val initialFlip = flip()
    random.reinit(17)
    return sampleRejection(random.selector(), 10) { !initialFlip || flip() }
  }

  @Test
  fun baseline() = runTestCC {
    val first = Probable(0.5, Value.Leaf(listOf(Probable(1.0, Value.Leaf(true)))))
    exactReify { testn11() } should
      { res ->
        res.size shouldEq 2
        res shouldContain first
        res
          .single { it != first }
          .should {
            it.prob shouldEq 0.5
            it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder
              listOf(Probable(0.5, Value.Leaf(true)), Probable(0.5, Value.Leaf(false)))
          }
      }
    exactReify { testn12(random(17)) } should
      { res ->
        res.size shouldEq 2
        res shouldContain first
        res
          .single { it != first }
          .should {
            it.prob shouldEq 0.5
            it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder
              listOf(Probable(0.6, Value.Leaf(true)), Probable(0.4, Value.Leaf(false)))
          }
      }
    val random17 = random(17)
    sampleRejection(random17.selector(), 10) { testn12(random17) } should
      { res ->
        res.size shouldEq 2
        res shouldContain first
        res
          .single { it != first }
          .should {
            it.prob shouldEq 0.5
            it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder
              listOf(Probable(0.6, Value.Leaf(true)), Probable(0.4, Value.Leaf(false)))
          }
      }
  }

  @Test
  fun testn2() = runTestCC {
    val first = Probable(0.5, Value.Leaf(listOf(Probable(1.0, Value.Leaf(true)))))
    exactReify {
      val coin = flip()
      exactReify { flip() || coin }
    } should
      { res ->
        res.size shouldEq 2
        res shouldContain first
        res
          .single { it != first }
          .should {
            it.prob shouldEq 0.5
            it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder
              listOf(Probable(0.5, Value.Leaf(true)), Probable(0.5, Value.Leaf(false)))
          }
      }
    exactReify {
      val coin = flip()
      sampleRejection(random(17).selector(), 10) { flip() || coin }
    } should
      { res ->
        res.size shouldEq 2
        res shouldContain first
        res
          .single { it != first }
          .should {
            it.prob shouldEq 0.5
            it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder
              listOf(Probable(0.6, Value.Leaf(true)), Probable(0.4, Value.Leaf(false)))
          }
      }
    exactReify {
      val coin = flip()
      exactReify { coin || flip() }
    } should
      { res ->
        res.size shouldEq 2
        res shouldContain first
        res
          .single { it != first }
          .should {
            it.prob shouldEq 0.5
            it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder
              listOf(Probable(0.5, Value.Leaf(true)), Probable(0.5, Value.Leaf(false)))
          }
      }
  }
}
