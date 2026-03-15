package io.github.kyay10.kontinuity.effekt.hansei

import arrow.core.getOrElse
import io.github.kyay10.kontinuity.effekt.*
import io.github.kyay10.kontinuity.repeatIteratorless
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus
import kotlin.contracts.contract
import kotlin.math.pow
import kotlin.random.Random

interface Probabilistic : Exc {
  suspend fun <A> Dist<A>.dist(): A
  override suspend fun raise(): Nothing = emptyList<Probable<Nothing>>().dist()
}

interface Memory {
  fun <A> letLazy(block: suspend () -> A): suspend () -> A

  fun <A, B> memo(
    block: suspend (A) -> B
  ): suspend (A) -> B
}

context(p: Probabilistic)
suspend inline fun <A> Dist<A>.dist(): A = with(p) { dist() }
suspend inline fun <A> reify(crossinline block: suspend context(Probabilistic, Memory) () -> A): SearchTree<A> =
  probabilistic {
    memory {
      block()
    }
  }

@PublishedApi
internal suspend inline fun <A> probabilistic(crossinline block: suspend context(Probabilistic) () -> A): SearchTree<A> =
  handle {
    val result = block(object : Probabilistic {
      override suspend fun <A> Dist<A>.dist(): A = use { resume ->
        map { (p, v) ->
          Probable(p, Value.Branch { resume(v) })
        }
      }

      // Slightly faster
      override suspend fun raise(): Nothing = discardWithFast(Result.success(emptyList()))
    })
    listOf(Probable(1.0, Value.Leaf(result)))
  }

@PublishedApi
internal suspend inline fun <A> memory(crossinline block: suspend context(Memory) () -> A): A = persistentRegion {
  block(object : Memory {
    override fun <A> letLazy(block: suspend () -> A): suspend () -> A {
      val loc = field<A>()
      return { loc.getOrPut { block() } }
    }

    override fun <A, B> memo(block: suspend (A) -> B): suspend (A) -> B {
      var map by field(persistentMapOf<A, B>())
      return { a ->
        map.getOrNone(a).getOrElse {
          block(a).also { v ->
            map += a to v // we intentionally fetch `map` again, since it might've been changed by `block`
          }
        }
      }
    }
  })
}

context(_: Probabilistic)
suspend inline fun flip(p: Prob = 0.5): Boolean = listOf(
  Probable(p, true),
  Probable(1 - p, false)
).dist()

context(_: Probabilistic)
suspend inline fun uniform(count: Int): Int {
  if (count <= 0) error("count must be positive")
  if (count == 1) return 0
  val p = 1.0 / count
  var acc = 0.0
  return List(count) { i ->
    if (i == count - 1) {
      Probable(1 - acc, i)
    } else {
      val prob = Probable(p, i)
      acc += p
      prob
    }
  }.dist()
}

context(_: Probabilistic)
suspend inline fun <A> List<A>.uniformly(): A = this[uniform(size)]

context(_: Probabilistic)
suspend inline fun IntRange.uniformly(): Int = start + uniform(endInclusive - start + 1)

context(_: Probabilistic)
suspend fun geometric(p: Prob): Int {
  fun loop(n: Int): SearchTree<Int> = listOf(
    Probable(p, Value.Branch { listOf(Probable(1.0, Value.Leaf(n))) }), // Not sure why it's written like this!
    Probable(1 - p, Value.Branch { loop(n + 1) })
  )
  return loop(0).reflect()
}

context(_: Probabilistic)
suspend inline fun geometric(p: Prob, endInclusive: Int): Int = buildList {
  var pp = p / (1 - (1 - p).pow(endInclusive + 1))
  for (i in 0..endInclusive) {
    add(Probable(pp, i))
    pp *= (1 - p)
  }
}.dist()

context(_: Probabilistic)
tailrec suspend fun <A> SearchTree<A>.reflect(): A = when (val v = dist()) {
  is Value.Leaf -> v.value
  is Value.Branch -> v.searchTree().reflect()
}

context(_: Probabilistic)
suspend fun ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  if (!condition) raise()
}

context(_: Probabilistic)
suspend fun <B> variableElimination(block: suspend context(Probabilistic, Memory) () -> B): B =
  exactReify(block).reflect()

fun <A> Random.selector(): Selector<A> {
  return (selector@{ choices ->
    val total = choices.sumOf { (p, _) -> p }
    val r = nextDouble() * total // [0, total)
    var acc = 0.0
    for ((p, v) in choices) {
      acc += p
      if (r < acc) return@selector Probable(total, v)
    }
    error("Choice selection: can't happen")
  })
}

fun <A> OcamlRandom.selector(): Selector<A> = selector@{ choices ->
  val total = choices.sumOf { (p, _) -> p }
  val r = nextDouble() * total // [0, total)
  var acc = 0.0
  for ((p, v) in choices) {
    acc += p
    if (r < acc) return@selector Probable(total, v)
  }
  error("Choice selection: can't happen")
}

// uses our non-determinism
context(_: Probabilistic)
fun <A> distSelector(): Selector<A> = { choices ->
  val total = choices.sumOf { (p, _) -> p }
  Probable(total, choices.map { (p, v) -> Probable(p / total, v) }.dist())
}

// End-user inference procedure:
// compute the probability distribution for a given non-deterministic program

suspend fun <A> exactReify(block: suspend context(Probabilistic, Memory) () -> A): SearchTree<A> =
  reify(block).explore()

suspend fun <A> sampleRejection(
  selector: Selector<Value<A>>,
  samples: Int,
  block: suspend context(Probabilistic, Memory) () -> A
): SearchTree<A> = reify(block).rejectionSampleDist(selector, samples)

suspend fun <A> sampleImportance(
  selector: Selector<SearchTree<A>>,
  samples: Int,
  block: suspend context(Probabilistic, Memory) () -> A
): SearchTree<A> = reify(block).shallowExplore(3).sampleDist(selector, object : SampleRunner {
  override suspend fun <Seed> run(seed: Seed, sampler: suspend (Seed) -> Seed): Pair<Seed, Int> {
    var s = seed
    repeatIteratorless(samples) {
      s = sampler(s)
    }
    return s to samples
  }
})

context(m: Memory)
fun <A> letLazy(block: suspend () -> A): suspend () -> A = m.letLazy(block)

context(m: Memory)
fun <A, B> memo(
  block: suspend (A) -> B
): suspend (A) -> B = m.memo(block)