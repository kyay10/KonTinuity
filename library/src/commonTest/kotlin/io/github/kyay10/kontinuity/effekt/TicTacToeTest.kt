package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.forEachIteratorless
import io.github.kyay10.kontinuity.runTestCC
import kotlin.test.Ignore
import kotlin.test.Test

private const val n = 5  // Dimension of the board
private const val m = 4 // Number of consecutive marks needed for win
private const val globalBreadthLimit = 6

// Ported from section 7 of https://okmij.org/ftp/papers/LogicT.pdf
class TicTacToeTest {
  @Test
  fun aiPrimeTest() = runTestCC {
    with(LogicTree) {
      onceOrNull {
        game(Mark.X, aiPrime, Mark.O, aiPrime)
      }
    }
  }
  @Ignore
  @Test
  fun aiTest() = runTestCC {
    with(LogicTree) {
      onceOrNull {
        game(Mark.X, ai, Mark.O, ai)
      }
    }
  }
}

enum class Mark {
  X, O;

  val other: Mark
    get() = if (this == X) O else X
}

data class Location(val x: Int, val y: Int)
typealias Board = Map<Location, Mark>

data class Game(val winner: Pair<Location, Mark>?, val moves: List<Location>, val board: Board)
typealias MoveFn = (Location) -> Location

private val moveLocFn = listOf<Pair<MoveFn, MoveFn>>(
  { (x, y): Location -> Location(x - 1, y) } to { (x, y) -> Location(x + 1, y) }, // up/down the column y
  { (x, y): Location -> Location(x, y - 1) } to { (x, y) -> Location(x, y + 1) }, // left/right the row x
  { (x, y): Location -> Location(x - 1, y - 1) } to { (x, y) -> Location(x + 1, y + 1) }, // diagonal \
  { (x, y): Location -> Location(x - 1, y + 1) } to { (x, y) -> Location(x + 1, y - 1) } // diagonal /
)

val Location.isGood: Boolean
  get() = x in 0..<n && y in 0..<n

// Move as far as possible from the location `loc` into the direction specified
// by `moveFn` so long as the new location is still marked by `mark`. Return the
// last location marked by `mark` and the number of moves performed.
fun extendLoc(board: Board, moveFn: MoveFn, mark: Mark, loc: Location): Pair<Int, Location> {
  tailrec fun loop(n: Int, currentLoc: Location, nextLoc: Location): Pair<Int, Location> =
    if (nextLoc.isGood && board[nextLoc] == mark) {
      loop(n + 1, nextLoc, moveFn(nextLoc))
    } else {
      n to currentLoc
    }
  return loop(0, loc, moveFn(loc))
}

// Find the maximum cluster size for a given `mark` starting from a location `loc`.
// It uses the `extendLoc` function in both directions of each move function pair
// and combines the results.
fun maxCluster(board: Board, mark: Mark, loc: Location) = moveLocFn.map { (moveFn1, moveFn2) ->
  val (n1, end1) = extendLoc(board, moveFn1, mark, loc)
  val (n2, _) = extendLoc(board, moveFn2, mark, loc)
  (n1 + n2 + 1) to end1
}.maxBy { it.first }

// Initialize a new game with an empty board, all possible moves, and no winner.
fun newGame(): Game {
  val moves = (0 until n).flatMap { x -> (0 until n).map { y -> Location(x, y) } }
  return Game(winner = null, moves = moves, board = emptyMap())
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
  val updatedBoard = game.board + (loc to player)
  val (clusterSize, clusterLoc) = maxCluster(updatedBoard, player, loc)
  val winner = if (clusterSize >= m) clusterLoc to player else null
  return Game(
    winner = winner,
    moves = game.moves - loc,
    board = updatedBoard
  )
}

typealias PlayerProc = suspend (Mark, Game) -> Pair<Int, Game>

context(_: Logic)
tailrec suspend fun game(
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
    println(showBoard(game.board))
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
suspend fun <T> List<T>.choose(): T {
  forEachIteratorless {
    if (flip()) return it
  }
  raise()
}


// AI player logic using minimax algorithm
context(_: Logic)
val ai: PlayerProc
  get() = { player, game ->
    when {
      game.winner != null || game.moves.isEmpty() -> estimateState(player, game) to game
      else -> {
        val possibleMoves = bagOfN(5) {
          val move = game.moves.choose()
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
suspend fun firstMoveWins(player: Mark, game: Game): Location {
  val move = game.moves.choose()
  val nextGame = takeMove(player, move, game)
  ensure(nextGame.winner?.second == player)
  return move
}

// Depth-limited minimax search
context(_: Logic)
suspend fun minmax(
  self: suspend (Int, Int, Mark, Game) -> Pair<Int, Game>,
  depthLimit: Int,
  breadthLimit: Int,
  player: Mark,
  game: Game
): Pair<Int, Game> {
  val possibleMoves = bagOfN(breadthLimit) {
    val move = game.moves.choose()
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
context(_: Logic)
val aiPrime: PlayerProc
  get() = { player, game ->
    suspend fun aiPrimeLimited(depthLimit: Int, breadthLimit: Int, player: Mark, game: Game): Pair<Int, Game> =
      when {
        game.winner != null || game.moves.isEmpty() -> estimateState(player, game) to game
        else -> {
          onceOrNull { firstMoveWins(player.other, game) }?.let { loc ->
            val nextGame = takeMove(player, loc, game)
            val (score, _) = aiPrimeLimited(depthLimit, breadthLimit, player.other, nextGame)
            -score to nextGame
          } ?: minmax(::aiPrimeLimited, depthLimit, breadthLimit, player, game)
        }
      }
    aiPrimeLimited(m, globalBreadthLimit, player, game)
  }