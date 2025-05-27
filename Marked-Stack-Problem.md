# Marked Stack Problem
## Problem Statement
### Types:
- A `Stack` of some elements of type `A`
- Markers are of type `M`. Markers are unique, and a marker can only exist on the stack once. Operations that use a marker are, by contract, only defined if the marker exists on the stack; if the marker isn't on the stack, the behaviour is arbitrary and undefined

### Operations:
- `push(Stack, A)` is a normal stack push. Should be O(1)
- `pop(Stack): A` is a normal stack pop, but skips the top-markers. Should be O(m), where m is the number of markers at the top (alternatively, conceptualise this as returning the top value, even if it's a marker)
- `mark(Stack): M` adds a unique marker onto the top of the stack, and returns that marker for use in other operations. Should be O(1)
- `discard(Stack, M)` pops the stack up to and including the marker. Thus, the element right before the marker should now be the top of the stack.
- `splitOnce(Stack, M): SingleUseSegment` like `discard`, but provides a Segment that can later be appended back on the stack, but only a singular time.
- `split(Stack, M): Segment` like `splitOnce`, but provides a reusable segment
- `pushAll(Stack, SingleUseSegmnent)` adds back the segment onto the stack
- `pushAll(Stack, Segment)` adds back the segment onto the stack

We'll also say that each marker is only used for `splitOnce` or `split` operations, never both (i.e. markers that are used for reusable segments can never make single use segments, and vice versa). This is a contract and not enforced by types either (although it could be)

## Solution: Linked list
Has constant time for all ops except `split` and `pushAll(Stack, Segment)`, i.e. the multi-use operations. For those, it has O(n) cost, where n is the size of the relevant segment. This can easily be optimised to O(m) cost, where m is the number of markers in the segment.
```kotlin
class A

sealed interface Node {
  val next: Node
} 

object EmptyNode : Node {
  override val next: Node
    get() = error("EmptyNode has no next node")
}

class ANode(val value: A, override val next: Node) : Node
class M(override var next: Node) : Node
class Stack(var current: Node)

fun push(s: Stack, a: A) {
  s.current = ANode(a, s.current)
}

fun pop(s: Stack): A {
  var n = s.current
  // this throws if the stack is empty, which is fine.
  while (n !is ANode) n = n.next // skip markers 
  s.current = n.next
  return n.value
}

fun mark(s: Stack): M {
  val m = M(s.current)
  s.current = m
  return m
}

fun discard(s: Stack, m: M) {
  s.current = m.next
}

class SingleUseSegment(val marker: M, val start: Node)

fun splitOnce(s: Stack, m: M): SingleUseSegment {
  val segment = SingleUseSegment(m, s.current)
  s.current = m.next
  return segment
}

class Segment(val start: Node, val marker: M, val values: List<Node>)

fun split(s: Stack, m: M): Segment {
  val list = mutableListOf<Node>()
  collectValues(s.current, m, list)
  val segment = Segment(s.current, m, list)
  s.current = m.next
  return segment
}

tailrec fun collectValues(n: Node, m: M, vs: MutableList<Node>) {
  if (n == m) return
  if (n is M) vs.add(n.next)
  collectValues(n.next, m, vs)
}

fun pushAll(s: Stack, seg: SingleUseSegment) {
  seg.marker.next = s.current
  s.current = seg.start
}

fun pushAll(s: Stack, seg: Segment) {
  repushValues(seg.start, seg.marker, seg.values)
  seg.marker.next = s.current
  s.current = seg.start
}

tailrec fun repushValues(n: Node, m: M, vs: List<Node>) {
  if (n == m) return
  if (n is M) {
    val h = vs.first()
    n.next = h
    repushValues(h, m, vs.drop(1))
  } else repushValues(n.next, m, vs)
}
```