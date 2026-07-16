# Kontinuity Stacks

This branch showcases a (mechanical) translation (an encoding, if you will) of [lifetimes](https://github.com/Kotlin/KEEP/blob/main/notes/0007-local-lifetimes.md) and [stacks](https://github.com/Kotlin/KEEP/blob/main/notes/0008-stacks.md) into current-day Kotlin. It uses the well-known technique of [monadic regions](https://okmij.org/ftp/Haskell/regions.html) to ensure lifetime safety. You can see prior art for using such a technique in [Scala-Effekt](https://www.cambridge.org/core/journals/journal-of-functional-programming/article/effekt-capabilitypassing-style-for-type-and-effectsafe-extensible-effect-handlers-in-scala/A19680B18FB74AD95F8D83BC4B097D4F), a precursor to the research language [Effekt](https://effekt-lang.org/).

To be very clear, this showcases (what I believe to be a faithful encoding of) the underlying safety system behind lifetimes. It has way worse syntax, and is likely buggy in some places (because it uses a compiler plugin to not have to deal with even worse syntax).

Lifetimes, even in their unrefined state as just design notes, have way better syntax and offer backwards compatibility.
Still, I hope this serves as a useful playground for the curious Kotliner who wants to try what safety guarantees can be acheived today, and how much better it'll be in the future!

For a gentler introduction, I have a [safe file IO](https://github.com/kyay10/regional/blob/master/compiler-plugin/testData/diagnostics/file.kt) example and a [safe local mutable state](https://github.com/kyay10/regional/blob/master/compiler-plugin/testData/box/st.kt) (like Haskell's [ST](https://hackage-content.haskell.org/package/base-4.22.0.0/docs/Control-Monad-ST.html)) example that's independent of this branch, but uses the same techniques.

The "novelty" here is implementing this in Kotlin. `@RestrictsSuspension` offers very good safety restrictions, but it doensn't let you have proper "lifetimes" at the type level.
This is where type parameters come into play. We represent every lifetime parameter (including the default `this` lifetime) as an explicit type parameter.
Importantly, the default `this` lifetime is contravariant (`in`).

We represent sub-lifetimes (i.e. some lifetime l being included within a bigger lifetime l') with subtyping, so `l: l'` represents that `l` is accessible whenever you're in the lifetime of `l'`.

(Convention: I tend to use ````this```` for a class's lifetime parameter, and `local` for a function's lifetime parameter, matching the lifetimes KEEP)

Type parameters are good and all, but how are escapes guaranteed not to occur? Well, `@RestrictsSuspension` comes into play again. We use a `Locality<l>` context on a lambda to denote that its lifetime is `l`. 

Functions that want to use their parameters locally are declared like:
```kotlin
context(_: Locality<local>)
suspend fun <local> foo(bar: Bar<local>) { ... }
```
Note the `suspend`: it's vital to trigger the safety provided by `@RestrictsSuspension`. It ensures that you can't escape `Locality` and pass it where it doesn't belong (wellllll, there are [some unsoundness bugs](https://youtrack.jetbrains.com/issue/KT-66623/RestrictsSuspension-API-and-feature-is-under-designed-and-unsound) with `@RestrictsSuspension`, but let's pretend those don't exist, or as the professors say, "we operate in a sound subset of the language", so no unsafe casts either).

But wait, we keep talking about lambdas that have some `Locality` context, but how do we get there in the first place?
There's a `suspend fun <R> runCC(block: suspend Locality<*>.() -> R): R` that takes you from a normal `suspend` context into a `Locality<Any?>` (i.e. the global lifetime). You can also go back to normal `suspend` using the provided `bridge { }` block, but note that you can't use any local objects inside (that'd defeat the whole purpose of lifetime safety!)

Before I leave you to explore, there's a majorly tricky part I need to tell you about: lambdas in this branch are lying to you...

Let's consider a simple example of setting up a safe `FileHandle<l>` class (now's a great time to test your understanding of `Locality<l>`):
```kotlin
class FileHandle<in `this`> internal constructor constructor(private val reader: java.io.Reader) {
  context(_: Locality<`this`>) // can only be called if `this` is active
  suspend fun read(): Char = reader.read().toChar()
}
```
This is a [technique](https://okmij.org/ftp/Computation/lightweight-static-guarantees.html) sometimes called a "trusted kernel", where you wrap some unsafe API into a type (and lifetime) safe wrapper.
Great, we have this API, but how do we let anyone use it?
What we want is a function like this:
```kotlin
context(_: Locality<local>)
suspend fun <local> Path.openHandle(block: suspend context(Locality<local>) (FileHandle<local>) -> Unit) = reader().use { block(it) }
```
but, but, but, this is unsafe!
A user can simply do:
```kotlin
runCC {
  lateinit var handle: FileHandle<Any?>
  Path(...).openHandle { handle = it }
  handle.read() // oops, Reader was closed already
}
```
What the what?? I thought there was supposed to be safety guarantees here!

The problem is that we're giving the user a `FileHandle<local>`, which means they can use it for the rest of their lifetime.
Sometimes, that's legitimately what you want! But not here.

What we wish we could write is something like:
```kotlin
context(_: Locality<local>)
suspend fun <local> Path.openHandle(block: suspend context(Locality<local2>) <local2: local> (FileHandle<local2>) -> Unit) = reader().use { block(it) }
```
I.e. make `block` generic over the new lifetime it's operating in (but guarantee it that it's a sub-lifetime of `local`), and give it `FileHandle<local2>`, so that it's only usable within `block`. Alas, that's not real Kotlin

But, we can go back to our Java roots (pre Java 8, even), and just use a SAM interface:
```kotlin
// I'm breaking my own convention here for clarity. Ideally, you'd rename local and local2 to `this` and local, respectively
interface HandleBlock<in local> {
  context(_: Locality<local2>)
  suspend operator fun <local2: local> invoke(h: FileHandle<local2>): Unit
}

context(_: Locality<local>)
suspend fun <local> Path.openHandle(block: HandleBlock<local>) = reader().use { block(it) }
```
It works, and guarantees type safety, but with awful call-site syntax:
```kotlin
runCC {
  lateinit var handle: FileHandle<Any?>
  Path(...).openHandle(object: HandleBlock<Any?> {
    context(_: Locality<local2>)
    suspend operator fun <local2: Any?> invoke(h: FileHandle<local2>) {
      handle = h // Now an error, because `FileHandle<local2>` is not a subtype of `FileHandle<Any?>` (contravariance, remember?)
    }
  }
  handle.read()
}
```
You can see [this branch](https://github.com/kyay10/KonTinuity/tree/safe-stacks), which fully embraces this boilerplate; yuck!

So, I dealt with this the way I deal with my other problems, deny that they exist, then spend copious amounts of time into working around them.

In other words, I made a [compiler plugin](https://github.com/kyay10/regional/tree/master)...

I won't go too into the details of how it works (vaguely, it creates local classes at the call sites of these generic "regional" lambdas, and adds smart casts to that new local class type).

To use it, all we have to do is just add `@Regional` and turn the `interface` into a `fun` one!
```kotlin
// I'm breaking my own convention here for clarity. Ideally, you'd rename local and local2 to `this` and local, respectively
@Regional fun interface HandleBlock<in local> {
  context(_: Locality<local2>)
  suspend operator fun <local2: local> invoke(h: FileHandle<local2>): Unit = _impl(h)
}
```
Well, and do that weird default implementation into a plugin-generated method. Don't worry about it!

The call site becomes beautiful again, while maintaining the safety guarantees:
```kotlin
runCC {
  lateinit var handle: FileHandle<Any?>
  Path(...).openHandle { handle = it } // type error again, this type because `it` is `FileHandle<openHandle_local2>`, where `openHandle_local2` is the local class generated by the plugin
  handle.read()
}
```

This is pretty much everything you need to play around with this branch!

Well, there's also "higher-kinded types".

Basically, as the lifetime KEEP states, Kotlin types are now "higher-kinded ... with a contravariant locality parameter!".
This is only ever used when you see a type parameter with a lifetime applied to it (e.g. `E_{environment}` from some of the stacks primitives)

To support this, I use a classic [encoding of higher-kinded types using normal generics](https://www.cl.cam.ac.uk/~jdy22/papers/lightweight-higher-kinded-polymorphism.pdf). A type like `E_{environment}` should be `E<environment>` in our system, but that's not valid Kotlin, so instead it's `Local<E, environment>`. 

Types that want to be passed as such environments need to extend `Local` with a first type argument that uniquely identiifes them (usually the class with all its type parameters except `this`), and a second type parameter that's the lifetime (`this`).

To get from a `Local<Foo<T, *>, this>` to a `Foo<T, this>`, you need to do a cast. Usually, you want to abstract that away in a function called `fix`.

The prior art here is some historical version of Arrow that I can't find right now, but trust me, you don't want to find it either.

I do happen to have an HKT (highKT, haha get it?) [plugin](https://github.com/kyay10/highKT), but I ran into some issues with it, and it's very overkill for this use case (It's basically lambda calculus at the type-level!).
