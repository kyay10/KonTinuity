package io.github.kyay10.kontinuity.effekt.hansei

import arrow.core.fold
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.foldIteratorless
import io.github.kyay10.kontinuity.repeatIteratorless
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.sqrt

private fun <K, V> PersistentMap<K, V>.insertWith(key: K, value: V, combine: (V, V) -> V): PersistentMap<K, V> {
  val oldValue = this[key]
  return if (oldValue == null) {
    put(key, value)
  } else {
    put(key, combine(value, oldValue))
  }
}

/**
 * Explore and flatten the tree;
 * perform exact inference to the given depth
 *
 * @param maxDepth the maximum depth to explore, or null for no limit
 */
context(_: MultishotScope<Region>)
suspend fun <A, Region> SearchTree<A, Region>.explore(maxDepth: Int? = null): SearchTree<A, Region> {
  context(_: MultishotScope<Region>)
  tailrec suspend fun SearchTree<A, Region>.loop(
    p: Prob,
    depth: Int,
    down: Boolean,
    ans: PersistentMap<A, Prob>,
    susp: PersistentList<Probable<Value<A, Region>>>,
  ): Pair<PersistentMap<A, Prob>, PersistentList<Probable<Value<A, Region>>>> = if (isEmpty()) ans to susp
  else {
    val (pt, v) = first()
    val rest = drop(1)
    when (v) {
      is Value.Leaf -> rest.loop(p, depth, down, ans.insertWith(v.value, pt * p, Prob::plus), susp)
      is Value.Branch if down -> {
        val newDown = maxDepth == null || depth < maxDepth
        val (newAns, newSusp) = v.searchTree().loop(p * pt, depth + 1, newDown, ans, susp)
        rest.loop(p, depth, down, newAns, newSusp)
      }

      is Value.Branch -> rest.loop(p, depth, down, ans, susp.add(0, Probable(pt * p, v)))
    }
  }
  val (ans, susp) = loop(1.0, 0, true, persistentHashMapOf(), persistentListOf())
  return ans.fold(susp) { acc, (v, p) ->
    acc.add(0, Probable(p, Value.Leaf(v)))
  }
}

private const val nearlyOne = 1.0 - 1e-7

/**
 * Partially explore but do not flatten the tree
 * Pre-computing choices as an optimization
 *
 * @param maxDepth the maximum depth to explore
 */
context(_: MultishotScope<Region>)
suspend fun <A, Region> SearchTree<A, Region>.shallowExplore(maxDepth: Int): SearchTree<A, Region> {
  context(_: MultishotScope<Region>)
  tailrec suspend fun SearchTree<A, Region>.loop(
    p: Prob,
    depth: Int,
    ans: PersistentMap<A, Prob>,
    susp: PersistentList<Probable<Value<A, Region>>>,
  ): Pair<PersistentMap<A, Prob>, PersistentList<Probable<Value<A, Region>>>> = if (isEmpty()) ans to susp
  else {
    val c = first()
    val (pt, v) = c
    val rest = drop(1)
    when (v) {
      is Value.Leaf -> rest.loop(p, depth, ans.insertWith(v.value, pt * p, Prob::plus), susp)
      else if depth >= maxDepth -> rest.loop(p, depth, ans, susp.add(0, c))
      is Value.Branch -> {
        val (ans, ch) = v.searchTree().loop(p * pt, depth + 1, ans, persistentListOf())
        val pTotal = ch.sumOf { it.prob }
        val acc = when {
          pTotal == 0.0 -> susp
          pTotal < nearlyOne -> {
            val ch = ch.map { Probable(it.prob / pTotal, it.value) }
            susp.add(0, Probable(pt * pTotal, Value.Branch { ch }))
          }

          else -> {
            susp.add(0, Probable(pt, Value.Branch { ch }))
          }
        }
        rest.loop(p, depth, ans, acc)
      }
    }
  }
  val (ans, susp) = loop(1.0, 0, persistentHashMapOf(), persistentListOf())
  return ans.fold(susp) { acc, (v, p) ->
    acc.add(0, Probable(p, Value.Leaf(v)))
  }
}

/**
 * Explore the tree till we find the first success --
 * the first [Value.Leaf] -- and return the resulting tree
 * Returns the empty tree otherwise
 */

context(_: MultishotScope<Region>)
tailrec suspend fun <A, Region> SearchTree<A, Region>.firstSuccess(): SearchTree<A, Region> = if (isEmpty()) this
else {
  val (pt, v) = first()
  val rest = drop(1)
  when (v) {
    is Value.Leaf -> this
    is Value.Branch -> // Unclear, expand and do BFS
      (rest + v.searchTree().map { it.copy(prob = pt * it.prob) }).firstSuccess()
  }
}

/**
 * Compute the bounds on the probability of evidence
 *
 * @param size max size of the queue
 */
/*
  A bounds estimator: obtain the bounds on the probabilty
   of evidence.
   The object probabilistic program must return (), or fail.
   Currently I don't know how to assign bounds when several values
   may be returned.
   This restriction seems consistent with Problog, which too determines
   bounds on the probability of a query.

   We traverse the tree breadth-first. If the number of unexplored branches
   raises above the threshold, we discard the branch with the lowest
   probability mass. A discarded branch with the probability mass p contributes
   0 to the current lower bound and p to the current upper bound.
   A successful branch with mass p contributes p to both bounds.
   A failed branch contributes 0 to both bounds.
 */
context(_: MultishotScope<Region>)
suspend fun <A, Region> SearchTree<A, Region>.boundedExplore(size: Int): ClosedFloatingPointRange<Prob> {
  context(_: MultishotScope<Region>)
  tailrec suspend fun SearchTree<A, Region>.loop(
    explore: Boolean,
    pc: Prob,
    low: Prob,
    high: Prob,
    jqueue: PersistentMap<Prob, PersistentList<suspend context(MultishotScope<Region>) () -> SearchTree<A, Region>>>,
    jsize: Int
  ): ClosedFloatingPointRange<Prob> {
    val (p, v) = firstOrNull() ?: return when (jsize) {
      0 -> low..high
      else -> {
        val p = jqueue.keys.max()
        val maxValue = jqueue[p]!!
        val jqueue = if (maxValue.size == 1) jqueue.remove(p) else jqueue.put(p, maxValue.removeAt(0))
        maxValue.first()().loop(jsize < size, p, low, high, jqueue, jsize - 1)
      }
    }
    val rest = drop(1)
    return when (v) {
      is Value.Leaf -> {
        val pe = pc * p
        rest.loop(explore, pc, low + pe, high + pe, jqueue, jsize)
      }

      is Value.Branch if explore ->
        rest.loop(explore, pc, low, high, jqueue.insertWith(pc * p, persistentListOf(v.searchTree)) { a, b ->
          a.addAll(b)
        }, jsize + 1)


      is Value.Branch -> rest.loop(explore, pc, low, high + pc * p, jqueue, jsize)
    }
  }

  return loop(true, 1.0, 0.0, 0.0, persistentHashMapOf(), 0)
}

interface SampleRunner {
  context(_: MultishotScope<Region>)
  suspend fun <Seed, Region> run(seed: Seed, sampler: suspend context(MultishotScope<Region>) (Seed) -> Seed): Pair<Seed, Int>
}

/**
 * Approximate inference: sampling
 *
 * @param selector selector among the branches
 */

context(_: MultishotScope<Region>)
suspend fun <A, Region> SearchTree<A, Region>.rejectionSampleDist(
  selector: Selector<Value<A, Region>, Region>,
  iterations: Int,
): SearchTree<A, Region> {
  context(_: MultishotScope<Region>)
  tailrec suspend fun SearchTree<A, Region>.loop(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
  ): PersistentMap<A, Prob> {
    if (isEmpty()) return ans
    val (p, v) = singleOrNull() ?: selector(this).let { (total, th) -> // choose a thread randomly
      return listOf(Probable(1.0, th)).loop(contribution * total, ans)
    }
    return when (v) {
      is Value.Leaf -> ans.insertWith(v.value, p * contribution, Prob::plus)

      is Value.Branch -> v.searchTree().loop(p * contribution, ans)
    }
  }

  var ans = persistentHashMapOf<A, Prob>()
  repeatIteratorless(iterations) {
    ans = loop(1.0, ans)
  }
  println("rejection sampling: done $iterations worlds")
  return ans.map { (v, p) ->
    Probable(p / iterations, Value.Leaf(v))
  }
}

/**
 * Explore with lookahead sampling
 */

context(_: MultishotScope<Region>)
suspend fun <A, Region> SearchTree<A, Region>.sampleDist(
  selector: Selector<SearchTree<A, Region>, Region>,
  sampleRunner: SampleRunner,
): SearchTree<A, Region> {
  // Explores the branch a bit
  context(_: MultishotScope<Region>)
  suspend fun Probable<Value<A, Region>>.lookAhead(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
    acc: PersistentList<Probable<SearchTree<A, Region>>>,
  ): Pair<PersistentMap<A, Prob>, PersistentList<Probable<SearchTree<A, Region>>>> {
    val (p, v) = this
    return when (v) {
      is Value.Leaf -> ans.insertWith(v.value, p * contribution, Prob::plus) to acc
      is Value.Branch -> {
        val ch = v.searchTree()
        if (ch.isEmpty()) return ans to acc
        if (ch.size == 1) {
          val (p1, v2) = ch.first()
          if (v2 is Value.Leaf<A>) {
            return ans.insertWith(v2.value, p * p1 * contribution, Prob::plus) to acc
          }
        }
        val total = ch.sumOf { it.prob }
        ans to acc.add(
          0, if (total < nearlyOne)
            Probable(p * total, ch.map { it.copy(prob = it.prob / total) })
          else
            Probable(p, ch)
        )
      }
    }
  }

  context(_: MultishotScope<Region>)
  tailrec suspend fun SearchTree<A, Region>.loop(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
  ): PersistentMap<A, Prob> {
    if (isEmpty()) return ans
    val (p, v) = singleOrNull()
      ?: foldIteratorless(ans to persistentListOf<Probable<SearchTree<A, Region>>>()) { (ans, acc), e ->
        e.lookAhead(contribution, ans, acc)
      }.let { (ans, acc) ->
        if (acc.isEmpty()) return ans
        val (total, th) = selector(acc)
        return th.loop(contribution * total, ans)
      }
    return when (v) {
      is Value.Leaf -> ans.insertWith(v.value, p * contribution, Prob::plus)

      is Value.Branch -> v.searchTree().loop(p * contribution, ans)
    }
  }

  context(_: MultishotScope<Region>)
  tailrec suspend fun SearchTree<A, Region>.makeThreads(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
  ): SearchTree<A, Region> {
    val (ans, acc) = foldIteratorless(ans to persistentListOf<Probable<SearchTree<A, Region>>>()) { (ans, acc), e ->
      e.lookAhead(contribution, ans, acc)
    }
    if (acc.isEmpty()) // pre-exploration solved the problem
      return ans.map { (v, p) -> Probable(p, Value.Leaf(v)) }
    if (acc.size == 1) acc.first().let { (p, ch) -> // Only one choice. Make more
      return ch.makeThreads(contribution * p, ans)
    }
    val cch = acc.asReversed()
    val (ans2, samples) = sampleRunner.run(persistentHashMapOf<A, Prob>()) {
      // cch are already pre-explored
      val (total, th) = selector(cch)
      th.loop(contribution * total, it)
    }
    println("sample importance: done $samples worlds")
    return ans.fold(ans2) { ans, (v, p) ->
      ans.insertWith(v, samples * p, Prob::plus)
    }.map { (v, p) ->
      Probable(p / samples, Value.Leaf(v))
    }
  }
  return makeThreads(1.0, persistentHashMapOf())
}

data class Statistic<out A>(val value: A, val mean: Double, val stddev: Double)

// Not multishot safe for now!
inline fun <A> statistics(
  seeds: IntRange,
  sampler: (Int) -> List<Probable<A>>,
): List<Statistic<A>> {
  val answers = buildMap<A, Pair<Prob, Prob>>(17) {
    for (seed in seeds) {
      for ((p, v) in sampler(seed)) {
        val (old, old2) = this[v] ?: run {
          this[v] = p to p * p
          continue
        }
        this[v] = (old + p) to (old2 + p * p)
      }
    }
  }
  val n = (seeds.endInclusive - seeds.start + 1).toDouble()
  return answers.map { (v, s) ->
    val (mean, variance) = s
    Statistic(v, mean / n, sqrt((variance - mean * mean / n) / n))
  }
}

fun <A> CDist<A>.normalize(): Probable<Dist<A>> {
  val total: Prob = sumOf { it.prob }
  return Probable(total, map { it.copy(prob = it.prob / total) })
}