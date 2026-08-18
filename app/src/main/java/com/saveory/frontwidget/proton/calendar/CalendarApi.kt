package com.saveory.frontwidget.proton.calendar

import kotlinx.serialization.json.JsonObject
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Minimal Proton Calendar API surface needed to render upcoming events.
 *
 * Responses are intentionally kept as raw [JsonObject] rather than typed DTOs: the calendar
 * endpoints are not part of proton-core (we are porting them), so returning the raw tree lets
 * us log the exact server shape and parse defensively instead of guessing @SerialName mappings.
 * Proton's Retrofit stack uses kotlinx.serialization, which converts JsonObject natively.
 */
interface CalendarApi : BaseRetrofitApi {

    // Listing calendars is the bare version root: GET calendar/v1 returns { Calendars: [...] }.
    // Appending "/calendars" makes the backend parse it as a calendar ID -> 400 "Invalid ID" (2061).
    @GET("calendar/v1")
    suspend fun getCalendars(): JsonObject

    // Bootstrap (keys + member passphrase + members) lives on the v2 surface.
    @GET("calendar/v2/{calendarId}/bootstrap")
    suspend fun getBootstrap(@Path("calendarId") calendarId: String): JsonObject

    /**
     * Events within a time window. Start/End are Unix seconds. Proton REQUIRES a [type]
     * discriminator (CalendarEventsQueryType); without it the backend ignores the window and
     * returns every event oldest-first. Values:
     *   0 = part-day events starting inside the window
     *   1 = part-day events that started before the window (still ongoing)
     *   2 = full-day events inside the window
     *   3 = full-day events that started before the window
     * PageSize is capped by the backend (~100).
     */
    @GET("calendar/v1/{calendarId}/events")
    suspend fun getEvents(
        @Path("calendarId") calendarId: String,
        @Query("Start") start: Long,
        @Query("End") end: Long,
        @Query("Timezone") timezone: String,
        @Query("Type") type: Int,
        @Query("Page") page: Int = 0,
        @Query("PageSize") pageSize: Int = 100
    ): JsonObject

    /**
     * Every event in the calendar, oldest-first (no Start/End/Type -> the backend ignores the window
     * and returns the full set, paginated). This is how we discover recurring event masters whose
     * first occurrence predates the display window (a windowed query never returns those). PageSize
     * is capped by the backend (~100).
     */
    @GET("calendar/v1/{calendarId}/events")
    suspend fun getAllEvents(
        @Path("calendarId") calendarId: String,
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int = 100
    ): JsonObject
}
