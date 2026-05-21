package com.ultiq.app.data.remote

import com.ultiq.app.data.remote.dto.AlarmDto
import com.ultiq.app.data.remote.dto.AlarmEventDto
import com.ultiq.app.data.remote.dto.AuthResponse
import com.ultiq.app.data.remote.dto.CalendarEventDto
import com.ultiq.app.data.remote.dto.CreateAlarmDto
import com.ultiq.app.data.remote.dto.CreateAlarmEventDto
import com.ultiq.app.data.remote.dto.ChangePasswordRequest
import com.ultiq.app.data.remote.dto.ForgotPasswordRequest
import com.ultiq.app.data.remote.dto.ResetPasswordRequest
import com.ultiq.app.data.remote.dto.ChecklistItemDto
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.CreateChecklistItemDto
import com.ultiq.app.data.remote.dto.CreateSessionDto
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.LoginRequest
import com.ultiq.app.data.remote.dto.RegisterRequest
import com.ultiq.app.data.remote.dto.SessionDto
import com.ultiq.app.data.remote.dto.SessionStatsDto
import com.ultiq.app.data.remote.dto.SleepRecordDto
import com.ultiq.app.data.remote.dto.SleepStatsDto
import com.ultiq.app.data.remote.dto.UpdateChecklistItemDto
import com.ultiq.app.data.remote.dto.UpdateProfileRequest
import com.ultiq.app.data.remote.dto.UpdateSessionDto
import com.ultiq.app.data.remote.dto.SessionDebriefRequestDto
import com.ultiq.app.data.remote.dto.SessionDebriefResponseDto
import com.ultiq.app.data.remote.dto.UserResponse
import com.ultiq.app.data.remote.dto.WeeklyInsightDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @GET("auth/me")
    suspend fun getMe(): UserResponse

    @PATCH("auth/me")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): UserResponse

    @DELETE("auth/me")
    suspend fun deleteAccount()

    @POST("auth/reset")
    suspend fun resetAccount()

    @POST("auth/password")
    suspend fun changePassword(@Body request: ChangePasswordRequest)

    @POST("auth/password/forgot")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest)

    @POST("auth/password/reset")
    suspend fun resetPassword(@Body request: ResetPasswordRequest)

    @POST("sleep")
    suspend fun createSleepRecord(@Body record: CreateSleepRecordDto): SleepRecordDto

    @GET("sleep")
    suspend fun getSleepRecords(
        @Query("start") start: String?,
        @Query("end") end: String?
    ): List<SleepRecordDto>

    @GET("sleep/{id}")
    suspend fun getSleepRecord(@Path("id") id: String): SleepRecordDto

    @PUT("sleep/{id}")
    suspend fun updateSleepRecord(
        @Path("id") id: String,
        @Body record: CreateSleepRecordDto
    ): SleepRecordDto

    @DELETE("sleep/{id}")
    suspend fun deleteSleepRecord(@Path("id") id: String)

    @GET("sleep/stats")
    suspend fun getSleepStats(
        @Query("range") range: String,
        @Query("start") start: String?,
        @Query("end") end: String?
    ): SleepStatsDto

    @POST("sessions")
    suspend fun createSession(@Body request: CreateSessionDto): SessionDto

    @GET("sessions")
    suspend fun getSessions(
        @Query("start") start: String?,
        @Query("end") end: String?,
        @Query("tag") tag: String?
    ): List<SessionDto>

    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionDto

    @PUT("sessions/{id}")
    suspend fun updateSession(
        @Path("id") id: String,
        @Body request: UpdateSessionDto
    ): SessionDto

    @DELETE("sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String)

    @GET("sessions/stats")
    suspend fun getSessionStats(@Query("range") range: String): SessionStatsDto

    @POST("calendar")
    suspend fun createCalendarEvent(@Body event: CreateCalendarEventDto): CalendarEventDto

    @GET("calendar")
    suspend fun getCalendarEvents(
        @Query("start") start: String?,
        @Query("end") end: String?,
        @Query("category") category: String?,
        @Query("priority") priority: String?
    ): List<CalendarEventDto>

    @GET("calendar/{id}")
    suspend fun getCalendarEvent(@Path("id") id: String): CalendarEventDto

    @PUT("calendar/{id}")
    suspend fun updateCalendarEvent(
        @Path("id") id: String,
        @Body event: CreateCalendarEventDto
    ): CalendarEventDto

    @DELETE("calendar/{id}")
    suspend fun deleteCalendarEvent(@Path("id") id: String)

    @POST("checklist")
    suspend fun createChecklistItem(@Body item: CreateChecklistItemDto): ChecklistItemDto

    @GET("checklist")
    suspend fun getChecklistItems(
        @Query("start") start: String?,
        @Query("end") end: String?,
        @Query("completed") completed: Boolean?
    ): List<ChecklistItemDto>

    @GET("checklist/today")
    suspend fun getChecklistToday(): List<ChecklistItemDto>

    @GET("checklist/{id}")
    suspend fun getChecklistItem(@Path("id") id: String): ChecklistItemDto

    @PUT("checklist/{id}")
    suspend fun updateChecklistItem(
        @Path("id") id: String,
        @Body item: UpdateChecklistItemDto
    ): ChecklistItemDto

    @POST("checklist/{id}/complete")
    suspend fun completeChecklistItem(@Path("id") id: String): ChecklistItemDto

    // §recurring-uncomplete-fix — symmetric inverse of /complete. Clears
    // `completed`, `completed_at`, and `last_completed_epoch_day` in one
    // shot. Generic PUT couldn't distinguish JSON null from "omit", which
    // left recurring un-ticks reverting on the next sync.
    @POST("checklist/{id}/uncomplete")
    suspend fun uncompleteChecklistItem(@Path("id") id: String): ChecklistItemDto

    @DELETE("checklist/{id}")
    suspend fun deleteChecklistItem(@Path("id") id: String)

    @POST("checklist/bulk")
    suspend fun bulkCreateChecklistItems(
        @Body items: List<CreateChecklistItemDto>
    ): List<ChecklistItemDto>

    @POST("alarms")
    suspend fun createAlarm(@Body alarm: CreateAlarmDto): AlarmDto

    @GET("alarms")
    suspend fun getAlarms(): List<AlarmDto>

    @GET("alarms/{id}")
    suspend fun getAlarm(@Path("id") id: String): AlarmDto

    @PUT("alarms/{id}")
    suspend fun updateAlarm(
        @Path("id") id: String,
        @Body alarm: CreateAlarmDto
    ): AlarmDto

    @DELETE("alarms/{id}")
    suspend fun deleteAlarm(@Path("id") id: String)

    @POST("alarms/{id}/events")
    suspend fun logAlarmEvent(
        @Path("id") id: String,
        @Body event: CreateAlarmEventDto
    ): AlarmEventDto

    // §9.4 — AI weekly insight. Server-side 24h cache; calling repeatedly
    // within that window returns the cached row without touching Bedrock.
    @POST("ai/weekly-insight")
    suspend fun getWeeklyInsight(): WeeklyInsightDto

    // §9.7 — Submit a 1-line debrief for a completed focus session. Server
    // classifies via Haiku and returns the assigned tag.
    @POST("ai/session-debrief/{id}")
    suspend fun submitSessionDebrief(
        @Path("id") id: String,
        @Body body: SessionDebriefRequestDto,
    ): SessionDebriefResponseDto
}
