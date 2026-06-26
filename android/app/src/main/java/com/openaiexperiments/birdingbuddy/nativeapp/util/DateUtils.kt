package com.openaiexperiments.birdingbuddy.nativeapp.util

import java.time.LocalDate
import java.time.temporal.WeekFields

fun currentIsoWeek(): Int {
    val weekFields = WeekFields.ISO
    return LocalDate.now().get(weekFields.weekOfWeekBasedYear())
}
