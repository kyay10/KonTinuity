import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TreeTest {
  @Test
  fun simple() = runTest {
    val tree1 = Node(Node(Leaf(1), Leaf(2)), Leaf(3))
    val tree2 = Node(Leaf(1), Node(Leaf(2), Leaf(3)))
    runCC {
      sameFringe(tree1.shiftSequence(), tree2.shiftSequence()) shouldBe true
      tree1.shiftSequence().toList() shouldBe listOf(1, 2, 3)
      tree2.shiftSequence().toList() shouldBe listOf(1, 2, 3)

      sameFringe(tree1.controlSequence(), tree2.controlSequence()) shouldBe false
      tree1.controlSequence().toList() shouldBe tree1.breadthFirstList()
      tree2.controlSequence().toList() shouldBe tree2.breadthFirstList()
    }
  }

  // Adapted from https://legacy.cs.indiana.edu/~sabry/teaching/intro/wi98/code/feb20/SameFringe.java
  @Test
  fun sabry() = runTest {
    val firstTree = run {
      val t1 = Node(Leaf(1), Leaf(2))
      val t2 = Node(t1, Leaf(3))
      val t3 = Node(Leaf(4), Leaf(5))
      val t4 = Node(t3, Leaf(6))
      Node(t2, t4)
    }

    val secondTree = run {
      val t1 = Node(Leaf(1), Leaf(2))
      val t2 = Node(t1, Leaf(3))
      val t3 = Node(t2, Leaf(4))
      val t4 = Node(t3, Leaf(5))
      Node(t4, Leaf(6))
    }

    val subTree = Node(Leaf(77), Leaf(2))
    val thirdTree = run {
      val t2Third = Node(subTree, Leaf(3))
      val t3Third = Node(t2Third, Leaf(4))
      val t4Third = Node(t3Third, Leaf(5))
      Node(t4Third, Leaf(6))
    }

    runCC {
      sameFringe(firstTree.shiftSequence(), thirdTree.shiftSequence()) shouldBe false
      firstTree.shiftSequence().toList() shouldBe listOf(1, 2, 3, 4, 5, 6)
      secondTree.shiftSequence().toList() shouldBe listOf(1, 2, 3, 4, 5, 6)
      thirdTree.shiftSequence().toList() shouldBe listOf(77, 2, 3, 4, 5, 6)
      subTree.shiftSequence().toList() shouldBe listOf(77, 2)
      firstTree.controlSequence().toList() shouldBe firstTree.breadthFirstList()
      secondTree.controlSequence().toList() shouldBe secondTree.breadthFirstList()
      thirdTree.controlSequence().toList() shouldBe thirdTree.breadthFirstList()
      subTree.controlSequence().toList() shouldBe subTree.breadthFirstList()
      sameFringe(firstTree.shiftSequence(), secondTree.shiftSequence()) shouldBe true
      sameFringe(firstTree.shiftSequence(), subTree.shiftSequence()) shouldBe false
    }
  }

  @Test
  fun sameBreadth() = runTest {
    val firstTree = run {
      val t1 = Node(Leaf(77), Leaf(2))
      val t2 = Node(t1, Leaf(3))
      val t3 = Node(t2, Leaf(4))
      val t4 = Node(t3, Leaf(5))
      Node(t4, Leaf(6))
    }
    val secondTree = run {
      val t1 = Node(Leaf(77), Leaf(2))
      val t2 = Node(Leaf(3), t1)
      val t3 = Node(Leaf(4), t2)
      val t4 = Node(Leaf(5), t3)
      Node(Leaf(6), t4)
    }
    runCC {
      firstTree.shiftSequence().toList() shouldBe listOf(77, 2, 3, 4, 5, 6)
      secondTree.shiftSequence().toList() shouldBe listOf(6, 5, 4, 3, 77, 2)
      sameFringe(firstTree.shiftSequence(), secondTree.shiftSequence()) shouldBe false
      sameFringe(firstTree.controlSequence(), secondTree.controlSequence()) shouldBe true
      firstTree.controlSequence().toList() shouldBe firstTree.breadthFirstList()
      secondTree.controlSequence().toList() shouldBe secondTree.breadthFirstList()
    }
  }

  @Test
  fun sequencesAreImmutable() = runTest {
    val firstTree = run {
      val t1 = Node(Leaf(77), Leaf(2))
      val t2 = Node(t1, Leaf(3))
      val t3 = Node(t2, Leaf(4))
      val t4 = Node(t3, Leaf(5))
      Node(t4, Leaf(6))
    }
    val secondTree = run {
      val t1 = Node(Leaf(77), Leaf(2))
      val t2 = Node(Leaf(3), t1)
      val t3 = Node(Leaf(4), t2)
      val t4 = Node(Leaf(5), t3)
      Node(Leaf(6), t4)
    }
    runCC {
      val firstSequence = firstTree.shiftSequence()
      val secondSequence = secondTree.shiftSequence()
      val firstList = listOf(77, 2, 3, 4, 5, 6)
      val secondList = listOf(6, 5, 4, 3, 77, 2)
      repeat(100) {
        firstSequence.toList() shouldBe firstList
        secondSequence.toList() shouldBe secondList
      }
    }
    runCC {
      val firstSequence = firstTree.controlSequence()
      val secondSequence = secondTree.controlSequence()
      val firstList = firstTree.breadthFirstList()
      val secondList = secondTree.breadthFirstList()
      repeat(100) {
        firstSequence.toList() shouldBe firstList
        secondSequence.toList() shouldBe secondList
      }
    }
  }
}

// Adapted from https://www.brics.dk/RS/05/2/BRICS-RS-05-2.pdf
private sealed interface Tree
private data class Leaf(val value: Int) : Tree
private data class Node(val left: Tree, val right: Tree) : Tree

private sealed interface Sequence {
  data object End : Sequence
  data class Next(val value: Int, val next: suspend () -> Sequence) : Sequence
}

private suspend fun Sequence.toList(): List<Int> = buildList {
  var current: Sequence = this@toList
  while (current is Sequence.Next) {
    add(current.value)
    current = current.next()
  }
}

private tailrec suspend fun sameFringe(s1: Sequence, s2: Sequence): Boolean = when {
  s1 is Sequence.End && s2 is Sequence.End -> true
  s1 is Sequence.Next && s2 is Sequence.Next -> s1.value == s2.value && sameFringe(s1.next(), s2.next())
  else -> false
}

private suspend fun Tree.shiftSequence(): Sequence = newReset {
  visitShift(this@shiftSequence)
  Sequence.End
}

private suspend fun Prompt<Sequence>.visitShift(tree: Tree): Unit = when (tree) {
  is Leaf -> shift { k -> Sequence.Next(tree.value) { k(Unit) } }
  is Node -> {
    visitShift(tree.left)
    visitShift(tree.right)
  }
}

private suspend fun Tree.controlSequence(): Sequence = newReset {
  visitControl(this@controlSequence)
}

private suspend fun Prompt<Sequence>.visitControl(tree: Tree): Sequence = when (tree) {
  is Leaf -> control { k -> Sequence.Next(tree.value) { reset { k(Sequence.End) } } }
  is Node -> control { k ->
    k(Sequence.End)
    visitControl(tree.left)
    visitControl(tree.right)
  }
}

private fun Tree.breadthFirstList(): List<Int> = buildList {
  val queue = mutableListOf<Tree>()
  queue.add(this@breadthFirstList)
  while (queue.isNotEmpty()) {
    val current = queue.removeFirst()
    when (current) {
      is Leaf -> add(current.value)
      is Node -> {
        queue.add(current.left)
        queue.add(current.right)
      }
    }
  }
}