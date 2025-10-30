package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.Frames.Copied.Companion.resumeWithImpl
import io.github.kyay10.kontinuity.Segmentable.Segment
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

private const val RUN_INVALIDATIONS = false
private const val SMALL_DATA_BUFFER_SIZE = 6

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>?
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>, context: CoroutineContext): Continuation<T>
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

public sealed class SplitSeq<in Start> : Continuation<Start>, CoroutineStackFrame

public sealed class SplitCont<in Start>(@JvmField @PublishedApi internal val realContext: CoroutineContext) :
  CoroutineContext, SplitSeq<Start>() {
  final override val context: CoroutineContext get() = this

  protected abstract fun MutableList<in Any?>.invalidateSingle()

  // returns new index
  protected abstract fun Array<out Any?>.revalidateSingle(isFinal: Boolean, index: Int): Int

  final override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
    realContext.fold(initial, operation)

  final override fun plus(context: CoroutineContext): CoroutineContext = realContext.plus(context)

  final override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
    realContext.minusKey(key)

  final override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
    realContext[key]

  internal companion object {
    tailrec fun MutableList<in Any?>.invalidate(cont: SplitCont<*>): Unit =
      with(cont.asSegmentableOrError()) {
        val restRest = this.restRest
        invalidateSingle()
        if (restRest !is Segment<*, *>) return invalidate(restRest)
      }

    fun SplitCont<*>.invalidate2(index: Int = 0): Array<Any?> = when(this) {
      is Prompt<*> -> {
        val restRest = this.restRest
        val arr = restRest.invalidate2(index + 2)
        arr[index] = rest.frames
        arr[index + 1] = restRest
        _rest = null
        _restRest = null
        arr
      }
      is ReaderT<*, *> -> {
        val restRest = this.restRest
        val arr = restRest.invalidate2(index + 3)
        arr[index] = rest.frames
        arr[index + 1] = restRest
        arr[index + 2] = state
        _rest = null
        _restRest = null
        arr
      }
      is Segment<*, *> -> arrayOfNulls(index)
      else -> error("Unknown Segmentable type: $this")
    }


    fun SplitCont<*>.invalidate3(): Array<Any?> {
      val buffer = arrayOfNulls<Any?>(SMALL_DATA_BUFFER_SIZE)
      var current: SplitCont<*> = this
      var size = 0
      while (size <= SMALL_DATA_BUFFER_SIZE - 3) {
        when (current) {
          is Prompt<*> -> {
            val restRest = current.restRest
            buffer[size++] = current.rest.frames
            buffer[size++] = restRest
            current._rest = null
            current._restRest = null
            current = restRest
          }
          is ReaderT<*, *> -> {
            val restRest = current.restRest
            buffer[size++] = current.rest.frames
            buffer[size++] = restRest
            buffer[size++] = current.state
            current._rest = null
            current._restRest = null
            current = restRest
          }
          else -> TODO()
        }
        if (current is Segment<*, *>) return buffer
      }
      val startAt = current
      val startAtSize = size
      do {
        current = when (current) {
          is Prompt<*> -> {
            size += 2
            current.restRest
          }
          is ReaderT<*, *> -> {
            size += 3
            current.restRest
          }
          else -> TODO()
        }
      } while (current !is Segment<*, *>)
      val arr = arrayOfNulls<Any?>(size)
      buffer.copyInto(arr, 0, 0, startAtSize)
      var index = startAtSize
      current = startAt
      while (index < size) {
        when (current) {
          is Prompt<*> -> {
            val restRest = current.restRest
            arr[index++] = current.rest.frames
            arr[index++] = restRest
            current._rest = null
            current._restRest = null
            current = restRest
          }
          is ReaderT<*, *> -> {
            val restRest = current.restRest
            arr[index++] = current.rest.frames
            arr[index++] = restRest
            arr[index++] = current.state
            current._rest = null
            current._restRest = null
            current = restRest
          }
          else -> TODO()
        }
      }
      return arr
    }

    internal tailrec fun Segmentable<*, *>.invalidateAndCollectValues() {
      val restRest = _restRest ?: return
      return if (restRest is Segmentable.Segment<*, *>) {
        restRest.collectValues()
      } else restRest.asSegmentableOrError().invalidateAndCollectValues()
    }
  }
}

@PublishedApi
internal inline fun <Start> SplitCont<Start>.asSegmentableOrError(): Segmentable<Start, *> =
  this as Segmentable<Start, *> // eventually add a message here that this means a reentrant resumption happened

@PublishedApi
internal inline fun <Start, P, R> Frames<Start>.splitAt(
  p: Prompt<P>,
  thisRest: SplitCont<*>,
  block: (Frames<P>, SplitCont<*>, Segmentable.Segment<Start, P>) -> R
): R {
  return block(p.rest, p.restRest, Segmentable.Segment(p, this, thisRest.asSegmentableOrError()))
}

internal data class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>) :
  SplitCont<Start>(underlying.context) {
  override fun Array<out Any?>.revalidateSingle(isFinal: Boolean, index: Int) = error("EmptyCont found in segment")

  override fun MutableList<in Any?>.invalidateSingle() = error("Reentrant resumptions are not supported")

  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
  override fun resumeWith(result: Result<Start>) = underlying.resumeWith(result)
}

@PublishedApi
@JvmInline
internal value class Frames<in Start>(val frames: Continuation<Start>) {
  fun copy(rest: Segmentable<*, *>) = Frames(Copied(frames, rest))
  fun resumeWith(result: Result<Start>) = resumeWithImpl(result)


  @PublishedApi
  internal class Under<RealStart, Start>(
    private val captured: Segmentable.Segment<RealStart, Start>,
    private val frames: Frames<Start>,
    private val rest: SplitCont<*>,
  ) : SplitSeq<RealStart>() {
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = frames.frames as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null
    override fun resumeWith(result: Result<RealStart>) {
      if (result.exceptionOrNull() !== SuspendedException) underflow().resumeWith(result)
    }

    fun underflow() = captured.prependTo(false, frames, rest)

    fun underflowCopied(newRest: SplitCont<*>) =
      captured.prependTo(true, frames, newRest)
  }

  private class Copied<in Start, Last>(
    cont: Continuation<Start>,
    @JvmField val rest: Segmentable<Last, *>,
  ) : SplitSeq<Start>() {
    @Suppress("UNCHECKED_CAST")
    private var cont: Continuation<Any?> = if (cont is Copied<Start, *>) cont.cont else cont as Continuation<Any?>
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null
    override fun resumeWith(result: Result<Start>) {
      if (result.exceptionOrNull() !== SuspendedException) Frames(this).resumeWithImpl(result)
    }

    companion object {
      private inline fun <T> Continuation<T>.invokeSuspend(result: Result<T>, onSuspend: () -> Nothing): Result<Any?> =
        runCatching({ invokeSuspend(result) }, onSuspend)

      @Suppress("UNCHECKED_CAST")
      tailrec fun <Start> Frames<Start>.resumeWithImpl(result: Result<Start>) {
        val frames = frames
        // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
        var current = frames as Continuation<Any?>
        var param: Result<Any?> = result
        if (frames is Copied<Any?, *>) {
          val framesRest = frames.rest as Segmentable<Any?, *>
          current = frames.cont
          while (true) {
            if (current is Under<Any?, *>)
              return current.underflowCopied(framesRest).resumeWithImpl(param)
            var completion =
              current.completion as? Continuation<Any?> ?: error("Not a compiler generated continuation $current")
            when (completion) {
              is Copied<Any?, *> -> completion = completion.cont
              is EmptyCont -> error("EmptyCont found as parent for copied frames")
              is Segmentable<Any?, *> -> {
                // top-level completion reached -- invoke and return
                val outcome = current.copy(framesRest, framesRest).invokeSuspend(param) { return }
                return framesRest.underflow().resumeWithImpl(outcome)
              }

              else -> {}
            }
            // Optimized by only setting it upon suspension.
            // This is safe only if no one accesses cont in between
            // That seems to be the case due to trampolining.
            // Note to self: if any weird behavior happens, uncomment this line
            //frames.cont = completion
            val outcome = current.copy(frames, framesRest).invokeSuspend(param) {
              frames.cont = completion
              return
            }
            //releaseIntercepted() // this state machine instance is terminating
            // unrolling recursion via loop
            current = completion
            param = outcome
          }
        } else while (true) {
          if (current is Under<Any?, *>)
            return current.underflow().resumeWithImpl(param)
          // Use normal resumption when faced with a non-compiler-generated continuation
          val completion = current.completion as? Continuation<Any?> ?: return current.resumeWith(param)
          val outcome = current.invokeSuspend(param) { return }
          //releaseIntercepted() // this state machine instance is terminating
          when (completion) {
            is EmptyCont -> return completion.resumeWith(outcome)
            is Copied<Any?, *> -> return Frames(completion).resumeWithImpl(outcome)
            is Segmentable<Any?, *> -> return completion.underflow().resumeWithImpl(outcome)
            else -> {}
          }
          // unrolling recursion via loop
          current = completion
          param = outcome
        }
      }
    }
  }
}

public class Prompt<Start> @PublishedApi internal constructor(
  context: CoroutineContext,
  rest: Continuation<Start>,
  restRest: SplitCont<*>
) : Segmentable<Start, Start>(context, rest, restRest) {
  override fun MutableList<in Any?>.invalidateSingle() {
    add(rest.frames)
    add(restRest)
    _rest = null
    _restRest = null
  }

  @Suppress("UNCHECKED_CAST")
  override fun Array<out Any?>.revalidateSingle(isFinal: Boolean, index: Int): Int {
    val frames = Frames(this[index] as Continuation<Start>)
    val rest = this[index + 1] as SplitCont<*>
    if (RUN_INVALIDATIONS) {
      val currentRest = _restRest
      if (currentRest !== rest) {
        if (currentRest is Segmentable.Segment<*, *>) {
          currentRest.collectValues()
        } else currentRest?.asSegmentableOrError()?.invalidateAndCollectValues()
        _restRest = rest
      }
    } else {
      _restRest = rest
    }
    _rest = (if (isFinal || rest is Segment<*, *>) frames else frames.copy(rest.asSegmentableOrError())).frames
    return index + 2
  }

  @PublishedApi
  override fun underflow(): Frames<Start> = rest.also {
    _rest = null
    _restRest = null
  }
}

public typealias Reader<S> = ReaderT<*, S>

public class ReaderT<Start, S> @PublishedApi internal constructor(
  context: CoroutineContext,
  private val fork: S.() -> S,
  @JvmField internal var state: S,
  rest: Continuation<Start>,
  restRest: SplitCont<*>,
) : Segmentable<Start, Start>(context, rest, restRest) {
  private class ForkOnFirstRead(state: Any?) {
    @JvmField
    val state: Any? = if (state is ForkOnFirstRead) state.state else state
  }

  public fun ask(): S {
    var state = state
    if (state is ForkOnFirstRead) {
      state = fork(state.state as S)
      this.state = state
    }
    return state
  }

  override fun MutableList<in Any?>.invalidateSingle() {
    add(rest.frames)
    add(restRest)
    add(state)
    _rest = null
    _restRest = null
  }

  @Suppress("UNCHECKED_CAST")
  override fun Array<out Any?>.revalidateSingle(isFinal: Boolean, index: Int): Int {
    val frames = Frames(this[index] as Continuation<Start>)
    val rest = this[index + 1] as SplitCont<*>
    if (RUN_INVALIDATIONS) {
      val currentRest = _restRest
      if (currentRest !== rest) {
        currentRest?.asSegmentableOrError()?.invalidateAndCollectValues()
        _restRest = rest
      }
    } else {
      _restRest = rest
    }
    _rest = (if (isFinal) frames else frames.copy(rest.asSegmentableOrError())).frames
    val state = this[index + 2] as S
    this@ReaderT.state = if (isFinal) state else ForkOnFirstRead(state) as S
    return index + 3
  }

  override fun underflow() = rest.also {
    _rest = null
    _restRest = null
  }
}

public sealed class Segmentable<in Start, Rest>(
  context: CoroutineContext,
  @PublishedApi @JvmField internal var _rest: Continuation<Rest>?,
  @PublishedApi @JvmField internal var _restRest: SplitCont<*>?,
) : SplitCont<Start>(context) {
  @PublishedApi
  internal inline val rest: Frames<Rest>
    get() = Frames(_rest ?: error("Segmentable used before being linked into Frames"))

  internal inline fun setRest(rest: Frames<Rest>, restRest: SplitCont<*>) {
    _rest = rest.frames
    _restRest = restRest
  }

  @PublishedApi
  internal inline val restRest: SplitCont<*> get() = _restRest!!

  final override val callerFrame: CoroutineStackFrame? get() = rest.frames as? CoroutineStackFrame
  final override fun getStackTraceElement(): StackTraceElement? = null
  internal abstract fun underflow(): Frames<Start>

  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) underflow().resumeWith(result)
  }

  @PublishedApi
  internal class Segment<Start, End>(
    private val delimiter: Prompt<End>,
    private val start: Frames<Start>,
    private val startRest: Segmentable<*, *>,
    private var values: Array<Any?>? = null,
  ) : SplitCont<Start>(startRest.realContext) {
    init {
      @Suppress("UNCHECKED_CAST")
      this.delimiter as Prompt<Start>
      this.delimiter.setRest(start, this)
    }

    override fun MutableList<in Any?>.invalidateSingle() {
      TODO("Not yet implemented")
    }

    override fun Array<out Any?>.revalidateSingle(
      isFinal: Boolean,
      index: Int
    ): Int {
      TODO("Not yet implemented")
    }

    override fun resumeWith(result: Result<Start>) {
      TODO("Not yet implemented")
    }

    override val callerFrame: CoroutineStackFrame?
      get() = TODO("Not yet implemented")

    override fun getStackTraceElement(): StackTraceElement? {
      TODO("Not yet implemented")
    }

    @Suppress("UNCHECKED_CAST")
    fun prependTo(shouldCopy: Boolean, stack: Frames<End>, stackRest: SplitCont<*>) =
      (if (shouldCopy) start.copy(startRest.asSegmentableOrError()) else start).also {
        if (values == null && shouldCopy) collectValues()
        values?.revalidate(!shouldCopy, startRest)
        delimiter.setRest(stack, stackRest)
      }

    private tailrec fun Array<Any?>.revalidate(isFinal: Boolean, cont: Segmentable<*, *>, index: Int = 0): Unit =
      with(cont) {
        val newIndex = revalidateSingle(isFinal, index)
        val restRest = this.restRest
        if (restRest !is Segment<*, *>) return revalidate(isFinal, restRest.asSegmentableOrError(), newIndex)
      }

    fun collectValues() {
      if (values != null) return
      /*values = ArrayList<Any?>(10).apply {
        invalidate(startRest)
      }.toTypedArray()*/
      values = startRest.invalidate3()
    }
  }
}

@PublishedApi
internal inline fun <T, R> collectStack(continuation: Continuation<T>, block: (Frames<T>, SplitCont<*>) -> R): R =
  block(Frames(continuation), continuation.context as SplitCont<*>)