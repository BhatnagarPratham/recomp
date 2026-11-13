package app.recomp.ui.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.recomp.data.repo.WeekReview
import app.recomp.domain.review.CalendarDay
import app.recomp.ui.components.SectionTitle
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The weekly review's sections (HM-03, HM-04, PRD §12.4), after the check-in's headline, verdict and proposal: Food,
 * Training, Body, the finish line and the consistency calendar. Steps and sleep appear only once there are some.
 */
@Composable
fun ReviewSections(r: WeekReview) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]

    SectionTitle("Food")
    Text(CheckinText.averages(r.food.average), style = MaterialTheme.typography.bodyLarge)
    ReviewText.proteinDays(r.food)?.let { Text(it) }
    r.food.origins?.let { Text(ReviewText.origins(it), color = muted) }

    SectionTitle("Training")
    Text(ReviewText.plan(r.training), style = MaterialTheme.typography.bodyLarge)
    if (r.training.strengthTarget == null) Text(ReviewText.PAST_PLAN, style = MaterialTheme.typography.bodySmall, color = muted)
    val records = ReviewText.records(r.training.records)
    if (records.isEmpty()) Text(ReviewText.NO_RECORDS, color = muted)
    records.take(ReviewText.RECORDS_SHOWN).forEach { Text(it) }
    if (records.size > ReviewText.RECORDS_SHOWN) Text(ReviewText.moreRecords(records.size - ReviewText.RECORDS_SHOWN), color = muted)
    if (r.training.stalled.isNotEmpty()) Text(ReviewText.stalled(r.training.stalled), color = muted)

    SectionTitle("Body")
    Text(ReviewText.trend(r.body), style = MaterialTheme.typography.bodyLarge)
    ReviewText.earlierPlan(r.plan)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
    ReviewText.waist(r.body)?.let { Text(it) }
    r.body.scan?.let { Text(ReviewText.scan(it)) }
    r.activity?.let { a ->
        ReviewText.steps(a)?.let { Text(it) }
        ReviewText.sleep(a)?.let { Text(it) }
    }

    r.outlook?.let {
        SectionTitle("Finish line")
        Text(ReviewText.outlook(it), style = MaterialTheme.typography.bodyLarge)
    }

    SectionTitle(r.month.month.getDisplayName(TextStyle.FULL, locale) + " ${r.month.year}")
    ConsistencyCalendar(r.calendar, locale)
    Text(ReviewText.CALENDAR_KEY, style = MaterialTheme.typography.bodySmall, color = muted)
}

/**
 * HM-04's month grid, Monday first: a filled dot for a strength workout, a ring for an extra session, a tint when food
 * was logged. Nothing counts streaks, and an empty day is just a number.
 */
@Composable
private fun ConsistencyCalendar(rows: List<List<CalendarDay?>>, locale: Locale) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        rows.forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (day != null) DayCell(day)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: CalendarDay) {
    val colors = MaterialTheme.colorScheme
    val described = buildList {
        add(day.date.dayOfMonth.toString())
        if (day.strength) add("strength workout")
        if (day.extra) add("extra session")
        if (day.food) add("food logged")
    }.joinToString(", ")
    Box(
        Modifier
            .fillMaxSize()
            .padding(1.dp)
            .background(if (day.food) colors.secondaryContainer else colors.surface, RoundedCornerShape(8.dp))
            .semantics { contentDescription = described }
            .padding(2.dp),
    ) {
        Text(
            day.date.dayOfMonth.toString(),
            Modifier.align(Alignment.TopCenter),
            style = MaterialTheme.typography.labelSmall,
            color = if (day.food) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (day.strength) Box(Modifier.size(8.dp).background(colors.primary, CircleShape))
            if (day.extra) Box(Modifier.size(8.dp).border(1.5.dp, colors.primary, CircleShape))
        }
    }
}
