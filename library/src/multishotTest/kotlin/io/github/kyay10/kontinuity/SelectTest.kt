package io.github.kyay10.kontinuity

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.some
import kotlin.math.absoluteValue
import kotlin.test.Test

class SelectTest {
  @Test
  fun enumerateAll() = runTestCC {
    with(NQueens()) {
      val results = selectAll { nqueens(9) }
      results.size shouldEq 352
      tries shouldEq 8393 // y positions tried
    }
  }

  @Test
  fun backtracking() = runTestCC {
    with(NQueens()) {
      val results = selectFirst { nqueens(9) }
      results shouldEq
        Some(listOf(Pos(9, 5), Pos(8, 7), Pos(7, 9), Pos(6, 4), Pos(5, 2), Pos(4, 8), Pos(3, 6), Pos(2, 3), Pos(1, 1)))
      tries shouldEq 41 // y positions tried
    }
  }
}

class NQueens {
  var tries = 0

  context(_: Select)
  suspend fun nqueens(n: Int): List<Pos> {
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

  private fun IntRange.available(x: Int, queens: List<Pos>): List<Int> = filter { y ->
    queens.all { !it.isAttacking(x, y) }
  }

  private fun Pos.isAttacking(x: Int, y: Int): Boolean =
    x == this.x || y == this.y || (x - this.x).absoluteValue == (y - this.y).absoluteValue
}

data class Pos(val x: Int, val y: Int)

interface Select {
  suspend fun <A> List<A>.select(): A
}

context(s: Select)
suspend fun <A> List<A>.select(): A = with(s) { this@select.select() }

suspend fun <R> selectAll(body: suspend context(Select) () -> R): List<R> = handle {
  listOf(
    body(
      object : Select {
        override suspend fun <A> List<A>.select(): A = use { k ->
          buildListLocally { this@select.forEachIteratorless { addAll(k(it)) } }
        }
      }
    )
  )
}

suspend fun <R> selectFirst(body: suspend context(Select) () -> R): Option<R> = handle {
  Some(
    body(
      object : Select {
        override suspend fun <A> List<A>.select(): A = use { k ->
          forEachIteratorless { e ->
            k(e).onSome {
              return@use it.some()
            }
          }
          None
        }
      }
    )
  )
}
