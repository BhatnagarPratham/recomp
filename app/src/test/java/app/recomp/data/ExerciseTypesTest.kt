package app.recomp.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import app.recomp.data.health.ExerciseTypes
import app.recomp.domain.model.SessionKind
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table that turns Health Connect's exercise types into session kinds (AR-04, TRD §10 M13). */
class ExerciseTypesTest {
    /** Every `EXERCISE_TYPE_*` constant the library has, by name. */
    private val all: Map<String, Int> = ExerciseSessionRecord::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("EXERCISE_TYPE_") && it.type == Int::class.javaPrimitiveType }
        .associate { it.apply { isAccessible = true }.name to it.getInt(null) }

    @Test
    fun `walking, running and cycling are cardio, yoga and pilates are yoga, stretching is mobility, sports are sport`() {
        assertEquals(SessionKind.CARDIO, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals(SessionKind.CARDIO, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals(SessionKind.CARDIO, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals(SessionKind.CARDIO, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL))
        assertEquals(SessionKind.YOGA, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_YOGA))
        assertEquals(SessionKind.YOGA, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_PILATES))
        assertEquals(SessionKind.MOBILITY, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING))
        assertEquals(SessionKind.SPORT, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON))
        assertEquals(SessionKind.SPORT, ExerciseTypes.kind(ExerciseSessionRecord.EXERCISE_TYPE_CRICKET))
        assertEquals("Walk", ExerciseTypes.label(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
    }

    @Test
    fun `no type ever maps to strength, and the strength types map to nothing`() {
        assertTrue(all.size > 50)
        all.forEach { (name, type) -> assertTrue(name, ExerciseTypes.kind(type) != SessionKind.STRENGTH) }
        listOf("EXERCISE_TYPE_STRENGTH_TRAINING", "EXERCISE_TYPE_WEIGHTLIFTING", "EXERCISE_TYPE_CALISTHENICS").forEach { name ->
            assertNull(name, ExerciseTypes.kind(all.getValue(name)))
            assertTrue(name, all.getValue(name) in ExerciseTypes.STRENGTH)
        }
    }

    @Test
    fun `every type the library knows is either mapped or left out on purpose`() {
        val leftOut = ExerciseTypes.STRENGTH + setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
            ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING,
        )
        all.forEach { (name, type) ->
            if (type in leftOut) assertNull(name, ExerciseTypes.kind(type)) else assertNotNull("$name has no session kind", ExerciseTypes.kind(type))
        }
        // A type added by a later Health Connect is left out until this table says what it is.
        assertNull(ExerciseTypes.kind(9_999))
    }
}
