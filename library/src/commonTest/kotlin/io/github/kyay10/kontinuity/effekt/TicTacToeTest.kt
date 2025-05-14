package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.forEachIteratorless
import io.github.kyay10.kontinuity.runTestCC
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

private const val n = 10  // Dimension of the board
private const val m = 4 // Number of consecutive marks needed for win
private const val globalBreadthLimit = 10

// Ported from section 7 of https://okmij.org/ftp/papers/LogicT.pdf
class TicTacToeTest {
  @Ignore
  @Test
  fun aiPrimeTest() = runTestCC(timeout = 10.minutes) {
    game(Mark.X, aiPrime, Mark.O, aiPrime)
  }

  @Ignore
  @Test
  fun aiTest() = runTestCC {
    game(Mark.X, ai, Mark.O, ai)
  }
}

enum class Mark {
  X, O;

  val other: Mark
    get() = if (this == X) O else X
}

data class Location(val x: Int, val y: Int)
typealias Board = PersistentMap<Location, Mark>

data class Game(val winner: Pair<Location, Mark>?, val moves: PersistentList<Location>, val board: Board)

enum class Move {
  UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT;

  val inverse: Move
    get() = when (this) {
      UP -> DOWN
      DOWN -> UP
      LEFT -> RIGHT
      RIGHT -> LEFT
      UP_LEFT -> DOWN_RIGHT
      UP_RIGHT -> DOWN_LEFT
      DOWN_LEFT -> UP_RIGHT
      DOWN_RIGHT -> UP_LEFT
    }

  operator fun invoke(loc: Location): Location = when (this) {
    UP -> Location(loc.x - 1, loc.y)
    DOWN -> Location(loc.x + 1, loc.y)
    LEFT -> Location(loc.x, loc.y - 1)
    RIGHT -> Location(loc.x, loc.y + 1)
    UP_LEFT -> Location(loc.x - 1, loc.y - 1)
    UP_RIGHT -> Location(loc.x - 1, loc.y + 1)
    DOWN_LEFT -> Location(loc.x + 1, loc.y - 1)
    DOWN_RIGHT -> Location(loc.x + 1, loc.y + 1)
  }
}

val Location.isGood: Boolean
  get() = x in 0..<n && y in 0..<n

// Move as far as possible from the location `loc` into the direction specified
// by `move` so long as the new location is still marked by `mark`. Return the
// last location marked by `mark` and the number of moves performed.
fun extendLoc(board: Board, move: Move, mark: Mark, loc: Location): Pair<Int, Location> {
  return extendLocImpl(board, move, mark, 0, loc, move(loc))
}
private tailrec fun extendLocImpl(board: Board, move: Move, mark: Mark, n: Int, currentLoc: Location, nextLoc: Location): Pair<Int, Location> =
  if (nextLoc.isGood && board[nextLoc] == mark) {
    extendLocImpl(board, move, mark, n + 1, nextLoc, move(nextLoc))
  } else {
    n to currentLoc
  }

// Find the maximum cluster size for a given `mark` starting from a location `loc`.
// It uses the `extendLoc` function in both directions of each move function pair
// and combines the results.
fun maxCluster(board: Board, mark: Mark, loc: Location): Pair<Int, Location> {
  val (upScore, upLocation) = maxCluster(board, mark, loc, Move.UP)
  val (leftScore, leftLocation) = maxCluster(board, mark, loc, Move.LEFT)
  val (upLeftScore, upLeftLocation) = maxCluster(board, mark, loc, Move.UP_LEFT)
  val (upRightScore, upRightLocation) = maxCluster(board, mark, loc, Move.UP_RIGHT)
  return when {
    upScore >= leftScore && upScore >= upLeftScore && upScore >= upRightScore -> upScore to upLocation
    leftScore >= upScore && leftScore >= upLeftScore && leftScore >= upRightScore -> leftScore to leftLocation
    upLeftScore >= upScore && upLeftScore >= leftScore && upLeftScore >= upRightScore -> upLeftScore to upLeftLocation
    else -> upRightScore to upRightLocation
  }
}

private fun maxCluster(board: Board, mark: Mark, loc: Location, move: Move): Pair<Int, Location> {
  val (n1, end1) = extendLoc(board, move, mark, loc)
  val (n2, _) = extendLoc(board, move.inverse, mark, loc)
  return (n1 + n2 + 1) to end1
}


// Initialize a new game with an empty board, all possible moves, and no winner.
fun newGame(): Game {
  val moves = (0 until n).flatMap { x -> (0 until n).map { y -> Location(x, y) } }.toPersistentList()
  return Game(winner = null, moves = moves, board = persistentMapOf())
}

// Generate a string representation of the board.
fun showBoard(board: Board): String {
  return (0 until n).joinToString("\n", postfix = "\n-----------------------\n") { i ->
    (0 until n).joinToString("") { j ->
      board[Location(i, j)]?.let { " $it" } ?: " ."
    }
  }
}

// Account for the move into location `loc` by the player `p`.
fun takeMove(player: Mark, loc: Location, game: Game): Game {
  val updatedBoard = game.board.put(loc, player)
  val (clusterSize, clusterLoc) = maxCluster(updatedBoard, player, loc)
  val winner = if (clusterSize >= m) clusterLoc to player else null
  return Game(
    winner = winner,
    moves = game.moves.remove(loc),
    board = updatedBoard
  )
}

typealias PlayerProc = suspend MultishotScope.(Mark, Game) -> Pair<Int, Game>

tailrec suspend fun MultishotScope.game(
  player1Mark: Mark,
  player1: PlayerProc,
  player2Mark: Mark,
  player2: PlayerProc,
  game: Game = newGame()
): Unit = when {
  game.winner != null -> {
    println("${game.winner.first} wins!")
  }

  game.moves.isEmpty() -> {
    println("Draw!")
  }

  else -> {
    val (_, nextGame) = player1(player1Mark, game)
    //println(showBoard(game.board))
    game(player2Mark, player2, player1Mark, player1, nextGame)
  }
}

val humanPlayer: PlayerProc = humanPlayer@{ mark, game ->
  println("Your (i, j) move as $mark")
  println(showBoard(game.board))
  val (i, j) = readln().split(",").map { it.toInt() }
  val loc = Location(i, j)
  if (loc !in game.moves) {
    println("Invalid move, try again.")
    return@humanPlayer humanPlayer(mark, game)
  }
  1 to takeMove(mark, loc, game)
}

context(_: Amb, _: Exc)
suspend fun <T> MultishotScope.choose(list: List<T>): T {
  list.forEachIteratorless {
    if (flip()) return it
  }
  raise()
}

context(_: Amb, _: Exc)
suspend fun MultishotScope.choose(ints: IntProgression): Int {
  ints.forEachIteratorless {
    if (flip()) return it
  }
  raise()
}

context(_: Amb, _: Exc)
tailrec suspend fun <T> MultishotScope.chooseBinary(list: List<T>, start: Int = 0, endExclusive: Int = list.size): T {
  if (start == endExclusive) raise()
  if (start + 1 == endExclusive) return list[start]
  val mid = (start + endExclusive) / 2
  return if (flip()) chooseBinary(list, start, mid) else chooseBinary(list, mid, endExclusive)
}


// AI player logic using minimax algorithm
val ai: PlayerProc
  get() = { player, game ->
    when {
      game.winner != null || game.moves.isEmpty() -> estimateState(player, game) to game
      else -> {
        val possibleMoves = bagOfN(5) {
          val move = choose(game.moves)
          val nextGame = takeMove(player, move, game)
          val (score, _) = ai(player.other, nextGame)
          -score to nextGame
        }
        possibleMoves.maxBy { it.first }
      }
    }
  }

// Estimate the state of the game for scoring
fun estimateState(player: Mark, game: Game): Int = when {
  game.winner?.second == player -> Int.MAX_VALUE
  game.winner != null -> Int.MIN_VALUE
  game.moves.isEmpty() -> 0 // Draw
  else -> 10
}

// Check if the current player can win in one move
context(_: Amb, _: Exc)
suspend fun MultishotScope.firstMoveWins(player: Mark, game: Game): Location {
  val move = choose(game.moves)
  val nextGame = takeMove(player, move, game)
  ensure(nextGame.winner?.second == player)
  return move
}

// Depth-limited minimax search
suspend fun MultishotScope.minmax(
  self: suspend MultishotScope.(Int, Int, Mark, Game) -> Pair<Int, Game>,
  depthLimit: Int,
  breadthLimit: Int,
  player: Mark,
  game: Game
): Pair<Int, Game> {
  val possibleMoves = bagOfN(breadthLimit) {
    val move = choose(game.moves)
    val nextGame = takeMove(player, move, game)
    if (depthLimit <= 0) estimateState(player, nextGame) to nextGame
    else {
      val (score, _) = self(depthLimit - 1, breadthLimit, player.other, nextGame)
      -score to nextGame
    }
  }
  return possibleMoves.maxBy { it.first }
}

// Optimized AI with depth and breadth limits
val aiPrime: PlayerProc
  get() = { player, game ->
    suspend fun MultishotScope.aiPrimeLimited(depthLimit: Int, breadthLimit: Int, player: Mark, game: Game): Pair<Int, Game> =
      when {
        game.winner != null || game.moves.isEmpty() -> estimateState(player, game) to game
        else -> {
          onceOrNull { firstMoveWins(player.other, game) }?.let { loc ->
            val nextGame = takeMove(player, loc, game)
            val (score, _) = aiPrimeLimited(depthLimit, breadthLimit, player.other, nextGame)
            -score to nextGame
          } ?: minmax(MultishotScope::aiPrimeLimited, depthLimit, breadthLimit, player, game)
        }
      }
    aiPrimeLimited(m, globalBreadthLimit, player, game)
  }