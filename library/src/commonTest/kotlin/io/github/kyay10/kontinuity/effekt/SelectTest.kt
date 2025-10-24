package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.math.absoluteValue
import kotlin.test.Test

class SelectTest {
  @Test
  fun enumerateAll() = runTestCC {
    with(NQueens()) {
      val results = selectAll {
        nqueens(9)
      }
      results.size shouldBe 352
      tries shouldBe 8393 // y positions tried
    }
  }

  @Test
  fun backtracking() = runTestCC {
    with(NQueens()) {
      val results = selectFirst {
        nqueens(9)
      }
      results shouldBe Some(
        listOf(
          Pos(9, 5), Pos(8, 7), Pos(7, 9), Pos(6, 4), Pos(5, 2), Pos(4, 8), Pos(3, 6), Pos(2, 3), Pos(1, 1)
        )
      )
      tries shouldBe 41 // y positions tried
    }
  }
}

class NQueens {
  var tries = 0
  context(_: MultishotScope<Region>)
  suspend fun <Region> Select<Region>.nqueens(n: Int): List<Pos> {
    val ys = 1..n
    var queens = emptyList<Pos>()
    var x = 1
    while (x <= n) {
      queens = listOf(Pos(x, ys.available(x, queens).select())) + queens
      tries++
      x++
    }
    return queens
  }

  private fun IntRange.available(x: Int, queens: List<Pos>): Iterable<Int> = filter { y ->
    queens.all { !it.isAttacking(x, y) }
  }

  private fun Pos.isAttacking(x: Int, y: Int): Boolean =
    x == this.x || y == this.y || (x - this.x).absoluteValue == (y - this.y).absoluteValue
}

data class Pos(val x: Int, val y: Int)

interface Select<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun <A> Iterable<A>.select(): A
}

class SelectAll<R, IR, OR>(p: HandlerPrompt<List<R>, IR, OR>) : Select<IR>, Handler<List<R>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun <A> Iterable<A>.select(): A = use { k ->
    fold(emptyList()) { acc, elem -> acc + k(elem) }
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> selectAll(body: suspend context(NewScope<Region>) Select<NewRegion>.() -> R): List<R> =
  handle { listOf(body(SelectAll(this))) }

class SelectFirst<R, IR, OR>(p: HandlerPrompt<Option<R>, IR, OR>) : Select<IR>, Handler<Option<R>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun <A> Iterable<A>.select(): A = use { k ->
    forEach {
      val res = k(it)
      if (res is Some) return@use res
    }
    None
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> selectFirst(body: suspend context(NewScope<Region>) Select<NewRegion>.() -> R): Option<R> =
  handle { Some(body(SelectFirst(this))) }