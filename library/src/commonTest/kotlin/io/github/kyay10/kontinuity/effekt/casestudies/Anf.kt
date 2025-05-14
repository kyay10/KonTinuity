package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.handleStateful
import io.github.kyay10.kontinuity.effekt.use
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AnfTest {
  // let x = f(g(42)) in x
  private val exampleTree: Tree = Let("x", App("f", App("g", Lit(42))), Var("x"))

  @Test
  fun example() = runTestCC {
    translate(exampleTree) shouldBe CLet(
      "x", CLet("x1", CRet(CLit(42)), CLet("x2", CApp("g", CVar("x1")), CApp("f", CVar("x2")))), CRet(CVar("x"))
    )

    pretty(translate(exampleTree)) shouldBe """
      |let x = let x1 = return 42 in let x2 =
      |      g(x1)
      |    in f(x2) in return x
    """.trimMargin()

    pipeline("42") shouldBe """
      |return 42
    """.trimMargin()

    pipeline("let x = 4 in 42") shouldBe """
      |let x = return 4 in return 42
    """.trimMargin()

    pipeline("let x = let y = 2 in 1 in 42") shouldBe """
      |let x = let y = return 2 in return 1 in
      |  return 42
    """.trimMargin()

    pipeline("let x = (let y = 2 in 1) in 42") shouldBe """
      |let x = let y = return 2 in return 1 in
      |  return 42
    """.trimMargin()

    pipeline("let x = (let y = f(42) in 1) in 42") shouldBe """
      |let x = let y = let x1 = return 42 in f(
      |        x1
      |      ) in return 1 in return 42
    """.trimMargin()
  }
}

sealed interface Expr
data class CLit(val value: Int) : Expr
data class CVar(val name: String) : Expr

sealed interface Stmt
data class CLet(val name: String, val binding: Stmt, val body: Stmt) : Stmt
data class CApp(val name: String, val arg: Expr) : Stmt
data class CRet(val expr: Expr) : Stmt

fun interface Fresh {
  suspend fun MultishotScope.fresh(): String
}

context(fresh: Fresh)
suspend fun MultishotScope.fresh(): String = with(fresh) { fresh() }

suspend fun <R> MultishotScope.freshVars(block: suspend context(Fresh) MultishotScope.() -> R): R {
  data class Data(var i: Int)
  return handleStateful(Data(0), Data::copy) {
    block({
      "x${++get().i}"
    }, this)
  }
}

fun interface Bind {
  suspend fun MultishotScope.bind(stmt: Stmt): Expr
}

context(bind: Bind)
suspend fun MultishotScope.bind(stmt: Stmt): Expr = with(bind) { bind(stmt) }

context(_: Bind, _: Fresh)
suspend fun MultishotScope.toStmt(tree: Tree): Stmt = when (tree) {
  is Lit -> CRet(CLit(tree.value))
  is Var -> CRet(CVar(tree.name))
  // Here we use bind since other than App, CApp requires an expression
  is App -> CApp(tree.name, bind(toStmt(tree.arg)))
  // here we use the handler `bindHere` to mark positions where bindings
  // should be inserted.
  is Let -> CLet(tree.name, bindHere { toStmt(tree.binding) }, bindHere { toStmt(tree.body) })
}

context(_: Fresh)
suspend fun MultishotScope.bindHere(block: suspend context(Bind) MultishotScope.() -> Stmt): Stmt = handle {
  block({
    use { resume ->
      val id = fresh()
      CLet(id, it, resume(CVar(id)))
    }
  }, this)
}

suspend fun MultishotScope.translate(e: Tree): Stmt = freshVars { bindHere { toStmt(e) } }

context(_: Emit)
suspend fun MultishotScope.emit(expr: Expr) = text(
  when (expr) {
    is CLit -> expr.value.toString()
    is CVar -> expr.name
  }
)

context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun MultishotScope.emit(stmt: Stmt): Unit = when (stmt) {
  is CLet -> {
    text("let"); space(); text(stmt.name); space(); text("=")
    group {
      nested { line(); emit(stmt.binding) }
      line()
      text("in")
    }
    group { nested { line(); emit(stmt.body) } }
  }

  is CApp -> {
    text(stmt.name); parens {
      group {
        nested {
          linebreak()
          emit(stmt.arg)
        }
        linebreak()
      }
    }
  }

  is CRet -> {
    text("return"); space(); emit(stmt.expr)
  }
}

suspend fun MultishotScope.pretty(s: Stmt): String = pretty(40) { emit(s) }

suspend fun MultishotScope.pipeline(input: String): String = when (val res = parse(input) { parseExpr() }) {
  is Either.Right -> pretty(translate(res.value))
  is Either.Left -> res.value
}