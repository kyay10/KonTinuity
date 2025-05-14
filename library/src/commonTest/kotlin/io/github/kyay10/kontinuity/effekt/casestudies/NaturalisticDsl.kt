package io.github.kyay10.kontinuity.effekt.casestudies

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.given
import io.github.kyay10.kontinuity.effekt.handleStateful
import io.github.kyay10.kontinuity.effekt.use
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

fun interface Speaker {
  suspend fun MultishotScope.speaker(): Person
}

context(s: Speaker)
suspend fun MultishotScope.me() = with(s) { speaker() }

inline infix fun Person.said(s: Speaker.() -> Sentence): Sentence = Say(this, Speaker { given<Person>() }.s())
infix fun Person.said(s: Sentence): Sentence = Say(this, s)

suspend fun MultishotScope.s1a() = John said { Mary loves me() }

// John said Mary loves me
context(s: Speaker)
suspend fun MultishotScope.s1b() = John said (Mary loves me())

suspend fun MultishotScope.s1c() = Peter said { s1b() }

fun interface Quantification {
  suspend fun MultishotScope.quantify(who: Predicate): Person
}

context(q: Quantification)
suspend fun MultishotScope.every(who: Predicate) = with(q) { quantify(who) }

suspend fun MultishotScope.s2() = scoped { John said { every(Woman) loves me() } }

suspend fun MultishotScope.scoped(s: suspend context(Quantification) MultishotScope.() -> Sentence): Sentence {
  data class Data(var i: Int)
  return handleStateful(Data(0), Data::copy) {
    s({ who ->
      use { resume ->
        val x = Person("x${get().i++}")
        ForAll(x, Implies(Is(x, who), resume(x)))
      }
    }, this)
  }
}