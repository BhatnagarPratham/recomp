package app.recomp.ui.food

import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodLogEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Macros
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Portions
import app.recomp.ui.onboarding.GoalText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** User-facing text for food logging (PRD §7.7). */
object FoodText {
    fun origin(o: Origin) = when (o) {
        Origin.HOME -> "Home"
        Origin.ORDERED -> "Ordered"
        Origin.PACKAGED -> "Packaged"
    }

    fun diet(d: FoodDiet) = when (d) {
        FoodDiet.VEGAN -> "Vegan"
        FoodDiet.VEGETARIAN -> "Vegetarian"
        FoodDiet.EGG -> "Has egg"
        FoodDiet.FISH -> "Has fish"
        FoodDiet.MEAT -> "Has meat"
    }

    fun caveat(c: FoodCaveat) = when (c) {
        FoodCaveat.FRYING_OIL -> "INDB counts all the frying oil, so this likely reads high. Quick add or a custom food may be closer."
    }

    const val ORDERED_NOTE = "Restaurant food usually has more oil than these numbers, which are for a home recipe."

    /** Grams of a macro: whole numbers, with one decimal below 10 g so small amounts don't read as 0. */
    fun g(value: Double): String = if (value < 10 && value % 1.0 != 0.0) "%.1f g".format(value) else "${value.roundToInt()} g"

    /** "140 kcal · 9 g protein" */
    fun kcalProtein(m: Macros) = "${GoalText.kcal(m.kcal)} · ${g(m.proteinG)} protein"

    /** "140 kcal · 9 g protein · 1 g carbs · 11 g fat", leaving out what's unknown. */
    fun macros(m: Macros): String = buildList {
        add(kcalProtein(m))
        m.carbsG?.let { add("${g(it)} carbs") }
        m.fatG?.let { add("${g(it)} fat") }
    }.joinToString(" · ")

    /** "2 piece · 50 g", "150 g", or nothing for a quick add. */
    fun amount(e: FoodLogEntity): String = when {
        e.grams == null -> ""
        e.unitLabel == Portions.GRAMS -> "${e.grams.roundToInt()} g"
        else -> "${Portions.format(e.quantity)} ${e.unitLabel} · ${e.grams.roundToInt()} g"
    }

    /** A search row's subtitle: per serving when the food has one, else per 100 g. */
    fun serving(food: FoodEntity, unit: FoodUnitEntity?): String {
        val grams = unit?.grams ?: 100.0
        val label = unit?.let { "1 ${it.label} (${it.grams.roundToInt()} g)" } ?: "100 g"
        return "$label · ${kcalProtein(food.per100g.forGrams(grams))}"
    }

    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    /** "Today · Sat, 17 Oct 2026": short enough for one line beside the day arrows. */
    fun dayLong(date: LocalDate, today: LocalDate): String {
        val short = date.format(DateTimeFormatter.ofPattern("EEE")) + ", " + date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        return when (date) {
            today -> "Today · $short"
            today.minusDays(1) -> "Yesterday · $short"
            else -> short
        }
    }
}
