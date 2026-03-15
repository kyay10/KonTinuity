package io.github.kyay10.kontinuity.effekt.hansei

import io.github.kyay10.kontinuity.RequiresMultishot
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

@RequiresMultishot
class NestedTest {
  context(_: Probabilistic, _: Memory)
  suspend fun testn11(): SearchTree<Boolean> = exactReify(
    listOf<suspend context(Probabilistic, Memory) () -> Boolean>(
      { flip() },
      { true }
    ).uniformly())

  context(_: Probabilistic, _: Memory)
  suspend fun testn12(random: OcamlRandom): SearchTree<Boolean> {
    val block = listOf<suspend context(Probabilistic, Memory) () -> Boolean>(
      { flip() },
      { true }
    ).uniformly()
    random.reinit(17)
    return sampleRejection(random.selector(), 10, block)
  }

  @Test
  fun baseline() = runTestCC {
    val first = Probable(0.5, Value.Leaf(listOf(Probable(1.0, Value.Leaf(true)))))
    exactReify { testn11() } should { res ->
      res.size shouldBe 2
      res shouldContain first
      res.single { it != first }.should {
        it.prob shouldBe 0.5
        it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder listOf(
          Probable(0.5, Value.Leaf(true)),
          Probable(0.5, Value.Leaf(false)),
        )
      }
    }
    exactReify { testn12(random(17)) } should { res ->
      res.size shouldBe 2
      res shouldContain first
      res.single { it != first }.should {
        it.prob shouldBe 0.5
        it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder listOf(
          Probable(0.6, Value.Leaf(true)),
          Probable(0.4, Value.Leaf(false)),
        )
      }
    }
    val random17 = random(17)
    sampleRejection(random17.selector(), 10) { testn12(random17) } should { res ->
      res.size shouldBe 2
      res shouldContain first
      res.single { it != first }.should {
        it.prob shouldBe 0.5
        it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder listOf(
          Probable(0.6, Value.Leaf(true)),
          Probable(0.4, Value.Leaf(false)),
        )
      }
    }
  }

  @Test
  fun testn2() = runTestCC {
    val first = Probable(0.5, Value.Leaf(listOf(Probable(1.0, Value.Leaf(true)))))
    exactReify {
      val coin = flip()
      exactReify { flip() || coin }
    } should { res ->
      res.size shouldBe 2
      res shouldContain first
      res.single { it != first }.should {
        it.prob shouldBe 0.5
        it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder listOf(
          Probable(0.5, Value.Leaf(true)),
          Probable(0.5, Value.Leaf(false)),
        )
      }
    }
    exactReify {
      val coin = flip()
      sampleRejection(random(17).selector(), 10) { flip() || coin }
    } should { res ->
      res.size shouldBe 2
      res shouldContain first
      res.single { it != first }.should {
        it.prob shouldBe 0.5
        it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder listOf(
          Probable(0.6, Value.Leaf(true)),
          Probable(0.4, Value.Leaf(false)),
        )
      }
    }
    exactReify {
      val coin = flip()
      exactReify { coin || flip() }
    } should { res ->
      res.size shouldBe 2
      res shouldContain first
      res.single { it != first }.should {
        it.prob shouldBe 0.5
        it.value.shouldBeInstanceOf<Value.Leaf<SearchTree<Boolean>>>().value shouldContainExactlyInAnyOrder listOf(
          Probable(0.5, Value.Leaf(true)),
          Probable(0.5, Value.Leaf(false)),
        )
      }
    }
  }
}