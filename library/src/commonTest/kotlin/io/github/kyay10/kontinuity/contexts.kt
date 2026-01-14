@file:OptIn(ExperimentalContracts::class)

package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import arrow.core.raise.RaiseDSL
import arrow.core.raise.context.ensure
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@RaiseDSL
context(_: Raise<Unit>)
fun ensure(condition: Boolean) {
  contract { returns() implies condition }
  ensure(condition) { }
}