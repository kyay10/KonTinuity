package io.github.kyay10.kontinuity.effekt.hansei

import arrow.core.fold
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
suspend fun <A> SearchTree<A>.explore(maxDepth: Int? = null): SearchTree<A> {
  tailrec suspend fun SearchTree<A>.loop(
    p: Prob,
    depth: Int,
    down: Boolean,
    ans: PersistentMap<A, Prob>,
    susp: PersistentList<Probable<Value<A>>>,
  ): Pair<PersistentMap<A, Prob>, PersistentList<Probable<Value<A>>>> = if (isEmpty()) ans to susp
  else {
    val (pt, v) = first()
    val rest = drop(1)
    when (v) {
      is Value.Leaf<A> -> rest.loop(p, depth, down, ans.insertWith(v.value, pt * p, Prob::plus), susp)
      is Value.Branch<A> if down -> {
        val newDown = maxDepth == null || depth < maxDepth
        val (newAns, newSusp) = @Suppress("NON_TAIL_RECURSIVE_CALL") v.searchTree()
          .loop(p * pt, depth + 1, newDown, ans, susp)
        rest.loop(p, depth, down, newAns, newSusp)
      }

      is Value.Branch<A> -> rest.loop(p, depth, down, ans, susp.add(0, Probable(pt * p, v)))
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
suspend fun <A> SearchTree<A>.shallowExplore(maxDepth: Int): SearchTree<A> {
  tailrec suspend fun SearchTree<A>.loop(
    p: Prob,
    depth: Int,
    ans: PersistentMap<A, Prob>,
    susp: PersistentList<Probable<Value<A>>>,
  ): Pair<PersistentMap<A, Prob>, PersistentList<Probable<Value<A>>>> = if (isEmpty()) ans to susp
  else {
    val c = first()
    val (pt, v) = c
    val rest = drop(1)
    when (v) {
      is Value.Leaf<A> -> rest.loop(p, depth, ans.insertWith(v.value, pt * p, Prob::plus), susp)
      else if depth >= maxDepth -> rest.loop(p, depth, ans, susp.add(0, c))
      is Value.Branch<A> -> {
        val (ans, ch) = @Suppress("NON_TAIL_RECURSIVE_CALL") v.searchTree()
          .loop(p * pt, depth + 1, ans, persistentListOf())
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

tailrec suspend fun <A> SearchTree<A>.firstSuccess(): SearchTree<A> = if (isEmpty()) this
else {
  val (pt, v) = first()
  val rest = drop(1)
  when (v) {
    is Value.Leaf<A> -> this
    is Value.Branch<A> -> // Unclear, expand and do BFS
      (rest + v.searchTree().map { it.copy(pt * it.prob) }).firstSuccess()
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
suspend fun <A> SearchTree<A>.boundedExplore(size: Int): ClosedFloatingPointRange<Prob> {
  tailrec suspend fun SearchTree<A>.loop(
    explore: Boolean,
    pc: Prob,
    low: Prob,
    high: Prob,
    jqueue: PersistentMap<Prob, PersistentList<suspend () -> SearchTree<A>>>,
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
      is Value.Leaf<A> -> {
        val pe = pc * p
        rest.loop(explore, pc, low + pe, high + pe, jqueue, jsize)
      }

      is Value.Branch<A> if explore ->
        rest.loop(explore, pc, low, high, jqueue.insertWith(pc * p, persistentListOf(v.searchTree)) { a, b ->
          a.addAll(b)
        }, jsize + 1)


      is Value.Branch<A> -> rest.loop(explore, pc, low, high + pc * p, jqueue, jsize)
    }
  }

  return loop(true, 1.0, 0.0, 0.0, persistentHashMapOf(), 0)
}

interface SampleRunner {
  suspend fun <Seed> run(seed: Seed, sampler: suspend (Seed) -> Seed): Pair<Seed, Int>
}

/**
 * Approximate inference: sampling
 *
 * @param selector selector among the branches
 */

suspend fun <A> SearchTree<A>.rejectionSampleDist(
  selector: Selector<Value<A>>,
  iterations: Int,
): SearchTree<A> {
  tailrec suspend fun SearchTree<A>.loop(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
  ): PersistentMap<A, Prob> {
    if (isEmpty()) return ans
    val (p, v) = singleOrNull() ?: selector(this).let { (total, th) -> // choose a thread randomly
      return listOf(Probable(1.0, th)).loop(contribution * total, ans)
    }
    return when (v) {
      is Value.Leaf<A> -> ans.insertWith(v.value, p * contribution, Prob::plus)

      is Value.Branch<A> -> v.searchTree().loop(p * contribution, ans)
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

suspend fun <A> SearchTree<A>.sampleDist(
  selector: Selector<SearchTree<A>>,
  sampleRunner: SampleRunner,
): SearchTree<A> {
  // Explores the branch a bit
  suspend fun Probable<Value<A>>.lookAhead(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
    acc: PersistentList<Probable<SearchTree<A>>>,
  ): Pair<PersistentMap<A, Prob>, PersistentList<Probable<SearchTree<A>>>> {
    val (p, v) = this
    return when (v) {
      is Value.Leaf<A> -> ans.insertWith(v.value, p * contribution, Prob::plus) to acc
      is Value.Branch<A> -> {
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

  tailrec suspend fun SearchTree<A>.loop(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
  ): PersistentMap<A, Prob> {
    if (isEmpty()) return ans
    val (p, v) = singleOrNull()
      ?: foldIteratorless(ans to persistentListOf<Probable<SearchTree<A>>>()) { (ans, acc), e ->
        e.lookAhead(contribution, ans, acc)
      }.let { (ans, acc) ->
        if (acc.isEmpty()) return ans
        val (total, th) = selector(acc)
        return th.loop(contribution * total, ans)
      }
    return when (v) {
      is Value.Leaf<A> -> ans.insertWith(v.value, p * contribution, Prob::plus)

      is Value.Branch<A> -> v.searchTree().loop(p * contribution, ans)
    }
  }

  tailrec suspend fun SearchTree<A>.makeThreads(
    contribution: Prob,
    ans: PersistentMap<A, Prob>,
  ): SearchTree<A> {
    val (ans, acc) = foldIteratorless(ans to persistentListOf<Probable<SearchTree<A>>>()) { (ans, acc), e ->
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
        val (old, old2) = getOrPut(v) { 0.0 to 0.0 }
        this[v] = (old + p) to (old2 + p * p)
      }
    }
  }
  val n = (seeds.last - seeds.first + 1).toDouble()
  return answers.map { (v, s) ->
    val (mean, variance) = s
    Statistic(v, mean / n, sqrt((variance - mean * mean / n) / n))
  }
}

fun <A> CDist<A>.normalize(): Probable<Dist<A>> {
  val total: Prob = sumOf { it.prob }
  return Probable(total, map { it.copy(prob = it.prob / total) })
}