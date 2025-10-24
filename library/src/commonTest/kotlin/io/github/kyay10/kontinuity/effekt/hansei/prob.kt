package io.github.kyay10.kontinuity.effekt.hansei

import arrow.core.getOrElse
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.effekt.*
import io.github.kyay10.kontinuity.repeatIteratorless
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus
import kotlin.contracts.contract
import kotlin.math.pow
import kotlin.random.Random

interface Probabilistic<in Region> : Exc<Region> {
  context(_: MultishotScope<Region>)
  suspend fun <A> Dist<A>.dist(): A

  context(_: MultishotScope<Region>)
  override suspend fun raise(msg: String): Nothing = emptyList<Probable<Nothing>>().dist()
}

interface Memory {
  fun <A, Region> letLazy(block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A

  fun <A, B, Region> memo(
    block: suspend context(MultishotScope<Region>) (A) -> B
  ): suspend context(MultishotScope<Region>) (A) -> B
}

context(p: Probabilistic<Region>, _: MultishotScope<Region>)
suspend inline fun <A, Region> Dist<A>.dist(): A = with(p) { dist() }

context(_: MultishotScope<Region>)
suspend inline fun <A, Region> reify(crossinline block: suspend context(Probabilistic<NewRegion>, Memory, NewScope<Region>) () -> A): SearchTree<A, Region> =
  probabilistic {
    memory {
      block()
    }
  }

@PublishedApi
context(_: MultishotScope<Region>)
internal suspend fun <A, Region> probabilistic(block: suspend context(Probabilistic<NewRegion>, NewScope<Region>) () -> A): SearchTree<A, Region> =
  handle {
    val result = context(object : Probabilistic<HandleRegion> {
      context(_: MultishotScope<HandleRegion>)
      override suspend fun <A> Dist<A>.dist(): A = use { resume ->
        map { (p, v) ->
          Probable(p, Value.Branch { resume(v) })
        }
      }

      // Slightly faster
      context(_: MultishotScope<HandleRegion>)
      override suspend fun raise(msg: String): Nothing = discardWithFast(Result.success(emptyList()))
    }) { block() }
    listOf(Probable(1.0, Value.Leaf(result)))
  }

@PublishedApi
context(_: MultishotScope<Region>)
internal suspend inline fun <A, Region> memory(crossinline block: suspend context(Memory, MultishotScope<Region>) () -> A): A =
  persistentRegion {
    context(object : Memory {
      override fun <A, Region> letLazy(block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A {
        val loc = field<A>()
        return {
          loc.getOrNone().getOrElse {
            block().also { loc.set(it) }
          }
        }
      }

      override fun <A, B, Region> memo(block: suspend context(MultishotScope<Region>) (A) -> B): suspend context(MultishotScope<Region>) (A) -> B {
        var map by field(persistentMapOf<A, B>())
        return { a ->
          map.getOrNone(a).getOrElse {
            block(a).also { v ->
              map += a to v // we intentionally fetch `map` again, since it might've been changed by `block`
            }
          }
        }
      }
    }) { block() }
  }

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> flip(p: Prob = 0.5): Boolean = listOf(
  Probable(p, true),
  Probable(1 - p, false)
).dist()

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> uniform(count: Int): Int {
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

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend inline fun <A, Region> List<A>.uniformly(): A = this[uniform(size)]

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> IntRange.uniformly(): Int = start + uniform(endInclusive - start + 1)

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend fun <Region> geometric(p: Prob): Int {
  fun loop(n: Int): SearchTree<Int, Region> = listOf(
    Probable(p, Value.Branch { listOf(Probable(1.0, Value.Leaf(n))) }), // Not sure why it's written like this!
    Probable(1 - p, Value.Branch { loop(n + 1) })
  )
  return loop(0).reflect()
}

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> geometric(p: Prob, endInclusive: Int): Int = buildList {
  var pp = p / (1 - (1 - p).pow(endInclusive + 1))
  for (i in 0..endInclusive) {
    add(Probable(pp, i))
    pp *= (1 - p)
  }
}.dist()

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
tailrec suspend fun <A, Region> SearchTree<A, Region>.reflect(): A = when (val v = dist()) {
  is Value.Leaf -> v.value
  is Value.Branch -> v.searchTree().reflect()
}

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend fun <Region> ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  if (!condition) raise()
}

context(_: Probabilistic<Region>, _: MultishotScope<Region>)
suspend fun <B, Region> variableElimination(block: suspend context(Probabilistic<NewRegion>, Memory, NewScope<Region>) () -> B): B =
  exactReify(block).reflect()

fun <A> Random.selector(): Selector<A, Any?> {
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

fun <A> OcamlRandom.selector(): Selector<A, Any?> = selector@{ choices ->
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
context(_: Probabilistic<Region>)
fun <A, Region> distSelector(): Selector<A, Region> = { choices ->
  val total = choices.sumOf { (p, _) -> p }
  Probable(total, choices.map { (p, v) -> Probable(p / total, v) }.dist())
}

// End-user inference procedure:
// compute the probability distribution for a given non-deterministic program

context(_: MultishotScope<Region>)
suspend fun <A, Region> exactReify(block: suspend context(Probabilistic<NewRegion>, Memory, NewScope<Region>) () -> A): SearchTree<A, Region> =
  reify(block).explore()

context(_: MultishotScope<Region>)
suspend fun <A, Region> sampleRejection(
  selector: Selector<Value<A, Region>, Region>,
  samples: Int,
  block: suspend context(Probabilistic<NewRegion>, Memory, NewScope<Region>) () -> A
): SearchTree<A, Region> = reify(block).rejectionSampleDist(selector, samples)

context(_: MultishotScope<Region>)
suspend fun <A, Region> sampleImportance(
  selector: Selector<SearchTree<A, Region>, Region>,
  samples: Int,
  block: suspend context(Probabilistic<NewRegion>, Memory, NewScope<Region>) () -> A
): SearchTree<A, Region> = reify(block).shallowExplore(3).sampleDist(selector, object : SampleRunner {
  context(_: MultishotScope<Region>)
  override suspend fun <Seed, Region> run(
    seed: Seed,
    sampler: suspend context(MultishotScope<Region>) (Seed) -> Seed
  ): Pair<Seed, Int> {
    var s = seed
    repeatIteratorless(samples) {
      s = sampler(s)
    }
    return s to samples
  }
})

context(m: Memory, _: MultishotScope<Region>)
fun <A, Region> letLazy(block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A =
  m.letLazy(block)

context(m: Memory, _: MultishotScope<Region>)
fun <A, B, Region> memo(
  block: suspend context(MultishotScope<Region>) (A) -> B
): suspend context(MultishotScope<Region>) (A) -> B = m.memo(block)