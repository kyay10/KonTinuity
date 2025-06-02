package io.github.kyay10.kontinuity.effekt.casestudies

import io.github.kyay10.kontinuity.DelegatingMultishotScope
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.MultishotToken
import io.github.kyay10.kontinuity.ResetDsl
import io.github.kyay10.kontinuity.effekt.*
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.reflect.KProperty
import kotlin.test.Test

class NaturalisticDslTest {
  @Test
  fun example() = runTestCC {
    s1 shouldBe Say(John, Is(Mary, InLoveWith(John)))
    s1a() shouldBe s1
    s1c() shouldBe Say(Peter, Say(John, Is(Mary, InLoveWith(Peter))))
    s2() shouldBe ForAll(Person("x0"), Implies(Is(Person("x0"), Woman), Say(John, Is(Person("x0"), InLoveWith(John)))))
  }
}

data class Person(val name: String) {
  companion object {
    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): Person = Person(prop.name)
  }
}

operator fun Person.getValue(thisRef: Any?, prop: KProperty<*>) = this

val John by Person
val Peter by Person
val Mary by Person

sealed interface Sentence
data class Say(val person: Person, val sentence: Sentence) : Sentence
data class Is(val person: Person, val predicate: Predicate) : Sentence

// used later:
data class ForAll(val individual: Person, val sentence: Sentence) : Sentence
data class Implies(val antecedent: Sentence, val consequent: Sentence) : Sentence

sealed interface Predicate
data class InLoveWith(val p: Person) : Predicate
data object Woman : Predicate

infix fun Person.loves(loved: Person) = Is(this, InLoveWith(loved))

// John said "Mary loves me"
val s1 = Say(John, Mary loves John)

fun interface Speaker<in R> {
  suspend fun MultishotScope<R>.speaker(): Person
}

context(s: Speaker<R>)
suspend fun <R> MultishotScope<R>.me() = with(s) { speaker() }

inline infix fun <R> Person.said(s: Speaker<R>.() -> Sentence): Sentence = Say(this, Speaker<R> { given<Person>() }.s())
infix fun Person.said(s: Sentence): Sentence = Say(this, s)

suspend fun <R> MultishotScope<R>.s1a() = John said { Mary loves me() }

// John said Mary loves me
context(s: Speaker<R>)
suspend fun <R> MultishotScope<R>.s1b() = John said (Mary loves me())

suspend fun <R> MultishotScope<R>.s1c() = Peter said { s1b() }

@QuantDsl
fun interface Quantification<in R> {
  suspend fun MultishotScope<R>.quantify(who: Predicate): Person
}

context(q: Quantification<R>)
suspend fun <R> MultishotScope<R>.every(who: Predicate) = with(q) { quantify(who) }

private suspend fun <R> QuantScope<R>.s2Scoped() = John said { every(Woman) loves me() }
suspend fun <R> MultishotScope<R>.s2() = scoped { s2Scoped() }

@DslMarker annotation class QuantDsl
@QuantDsl
class QuantScope<R>(quantification: Quantification<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token),
  Quantification<R> by quantification

suspend fun <R> MultishotScope<R>.scoped(s: suspend QuantScope<out R>.() -> Sentence): Sentence {
  data class Data(var i: Int)

  suspend fun <IR : R> StatefulPrompt<Sentence, Data, IR, R>.function() = s(QuantScope({ who ->
    use { resume ->
      val x = Person("x${get().i++}")
      ForAll(x, Implies(Is(x, who), resume(x)))
    }
  }, token))
  return handleStateful(Data(0), Data::copy) { function() }
}