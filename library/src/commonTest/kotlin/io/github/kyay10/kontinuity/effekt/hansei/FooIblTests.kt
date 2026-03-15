package io.github.kyay10.kontinuity.effekt.hansei

import io.github.kyay10.kontinuity.RequiresMultishot
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.test.Test

@RequiresMultishot
class FooIblTests {
  @Test
  fun `test foo ibl program 1`() = runTestCC {
    val result = exactReify {
      val x = flip(0.6)
      val y = x
      val z = x
      ensure(z)
      Pair(y, z)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.6, Value.Leaf(Pair(true, true)))
    )
  }

  @Test
  fun `test foo ibl program 2`() = runTestCC {
    val result = exactReify {
      val f: suspend (Char, Char) -> Char = { x: Char, y: Char ->
        if (flip(0.2)) x else y
      }
      Pair(f('d', listOf(Probable(0.4, 'b'), Probable(0.6, 'c')).dist()), 'e')
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.2, Value.Leaf(Pair('d', 'e'))),
      Probable(0.48, Value.Leaf(Pair('c', 'e'))),
      Probable(0.320000000000000062, Value.Leaf(Pair('b', 'e')))
    )
  }

  @Test
  fun `test foo ibl program 3`() = runTestCC {
    val result = exactReify {
      val f = if (flip(0.1)) {
        listOf(Probable(0.7, 'a'), Probable(0.3, 'b')).dist()
      } else {
        listOf(Probable(0.2, 'a'), Probable(0.8, 'b')).dist()
      }
      ensure(f == 'a')
      true
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.25, Value.Leaf(true))
    )
  }

  @Test
  fun `test iblt program 2`() = runTestCC {
    val result = exactReify {
      val (mz, mw) =
        listOf(
          Probable(0.01, 'a' to 'b'),
          Probable(0.02, 'a' to 'c'),
          Probable(0.97, 'd' to 'e')
        ).dist()
      ensure(mz == 'a')
      when (mw) {
        'b' -> flip(0.9)
        'c' -> flip(0.6)
        else -> flip(0.2)
      }
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.021, Value.Leaf(true)),
      Probable(0.009, Value.Leaf(false)) // Original was 0.00900000000000000105 for some reason
    )
  }

  @Test
  fun `test music tr 4-1`() = runTestCC {
    val result = exactReify {
      val f = suspend { flip(0.1) }
      val g = suspend { flip(0.3) }
      ensure(listOf(Probable(0.8, f), Probable(0.2, g)).dist()())
      true
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.14, Value.Leaf(true))
    )
  }

  @Test
  fun `test music tr 4-2`() = runTestCC {
    val result = exactReify {
      val f = suspend { flip(0.01) }
      val (mp, mq) = if (f()) 'a' to suspend { flip(0.3) } else 'b' to suspend { true }
      ensure(mp == 'a')
      mq()
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.003, Value.Leaf(true)),
      Probable(0.00699999999999999928, Value.Leaf(false))
    )
  }
}