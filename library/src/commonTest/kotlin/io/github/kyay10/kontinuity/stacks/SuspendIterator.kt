/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package io.github.kyay10.kontinuity.stacks

interface SuspendIterator<out T> {
  suspend operator fun next(): T

  suspend operator fun hasNext(): Boolean
}

fun <T> Iterator<T>.asSuspendIterator(): SuspendIterator<T> =
  object : SuspendIterator<T> {
    override suspend fun next(): T = next()

    override suspend fun hasNext(): Boolean = hasNext()
  }

operator fun <T> SuspendIterator<T>.iterator(): SuspendIterator<T> = this

suspend inline fun <T> SuspendIterator<T>.forEach(block: (T) -> Unit) {
  for (e in this) block(e)
}

// Use integer constants instead of enum to improve performance:
// 1) switch over enum values require loading ordinal value and mapping it to a correct tableswitch offset
//    using SwitchMap array, which is slightly slower compared to comparing integers (assuming that "switch" statement
//    has only a few cases);
// 2) at least on JVM, usage of integer states may enable more optimizations; in particular, for the code looping over
// the iterator,
//    the JIT can fully eliminate state manipulation (assuming that iterator's methods were inlined and
//    an iterator's allocation was eliminated) and use only a logic from a computeNext implementation to control
// iteration.
private object State {
  const val NOT_READY: Int = 0
  const val READY: Int = 1
  const val DONE: Int = 2
  const val FAILED: Int = 3
}

/**
 * A base class to simplify implementing iterators so that implementations only have to implement [computeNext] to
 * implement the iterator, calling [done] when the iteration is complete.
 */
abstract class AbstractSuspendIterator<T> : SuspendIterator<T> {
  private var state = State.NOT_READY
  private var nextValue: T? = null

  override suspend fun hasNext(): Boolean {
    return when (state) {
      State.DONE -> false
      State.READY -> true
      State.NOT_READY -> tryToComputeNext()
      else -> throw IllegalArgumentException("hasNext called when the iterator is in the FAILED state.")
    }
  }

  override suspend fun next(): T {
    if (state == State.READY) {
      state = State.NOT_READY
      @Suppress("UNCHECKED_CAST")
      return nextValue as T
    }
    if (state == State.DONE || !tryToComputeNext()) {
      throw NoSuchElementException()
    }
    state = State.NOT_READY
    @Suppress("UNCHECKED_CAST")
    return nextValue as T
  }

  private suspend fun tryToComputeNext(): Boolean {
    state = State.FAILED
    computeNext()
    return state == State.READY
  }

  /**
   * Computes the next item in the iterator.
   *
   * This callback method should call one of these two methods:
   *
   * * [setNext] with the next value of the iteration
   * * [done] to indicate there are no more elements
   *
   * Failure to call either method will result in the iteration terminating with a failed state
   */
  protected abstract suspend fun computeNext()

  /** Sets the next value in the iteration, called from the [computeNext] function */
  protected fun setNext(value: T) {
    nextValue = value
    state = State.READY
  }

  /** Sets the state to done so that the iteration terminates. */
  protected fun done() {
    state = State.DONE
  }
}
