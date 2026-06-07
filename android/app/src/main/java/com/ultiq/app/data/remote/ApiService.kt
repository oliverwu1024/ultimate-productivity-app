package com.ultiq.app.data.remote

import com.ultiq.app.data.remote.dto.AlarmDto
import com.ultiq.app.data.remote.dto.AlarmEventDto
import com.ultiq.app.data.remote.dto.AuthResponse
import com.ultiq.app.data.remote.dto.CalendarEventDto
import com.ultiq.app.data.remote.dto.CreateAlarmDto
import com.ultiq.app.data.remote.dto.CreateAlarmEventDto
import com.ultiq.app.data.remote.dto.ChangePasswordRequest
import com.ultiq.app.data.remote.dto.ChatMessageDto
import com.ultiq.app.data.remote.dto.ChatResetResponseDto
import com.ultiq.app.data.remote.dto.ChatSendRequestDto
import com.ultiq.app.data.remote.dto.ChatSendResponseDto
import com.ultiq.app.data.remote.dto.ForgotPasswordRequest
import com.ultiq.app.data.remote.dto.ResetPasswordRequest
import com.ultiq.app.data.remote.dto.ChecklistItemDto
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.CreateChecklistItemDto
import com.ultiq.app.data.remote.dto.CreateSessionDto
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.DeviceTokenResponse
import com.ultiq.app.data.remote.dto.LatestAnomalyDto
import com.ultiq.app.data.remote.dto.LoginRequest
import com.ultiq.app.data.remote.dto.RegisterDeviceTokenRequest
import com.ultiq.app.data.remote.dto.RegisterRequest
import com.ultiq.app.data.remote.dto.SessionDto
import com.ultiq.app.data.remote.dto.SessionStatsDto
import com.ultiq.app.data.remote.dto.AttachClipRequestDto
import com.ultiq.app.data.remote.dto.BatchCreatePhonePickupsDto
import com.ultiq.app.data.remote.dto.BatchCreateSleepAudioEventsDto
import com.ultiq.app.data.remote.dto.ClipPlaybackUrlResponseDto
import com.ultiq.app.data.remote.dto.ClipUploadUrlRequestDto
import com.ultiq.app.data.remote.dto.ClipUploadUrlResponseDto
import com.ultiq.app.data.remote.dto.PhonePickupDto
import com.ultiq.app.data.remote.dto.SleepAudioEventDto
import com.ultiq.app.data.remote.dto.SleepRecordDto
import com.ultiq.app.data.remote.dto.SleepStatsDto
import com.ultiq.app.data.remote.dto.UpdateChecklistItemDto
import com.ultiq.app.data.remote.dto.UpdateProfileRequest
import com.ultiq.app.data.remote.dto.UpdateSessionDto
import com.ultiq.app.data.remote.dto.ParseEventRequestDto
import com.ultiq.app.data.remote.dto.ParseEventResponseDto
import com.ultiq.app.data.remote.dto.SessionDebriefRequestDto
import com.ultiq.app.data.remote.dto.SessionDebriefResponseDto
import com.ultiq.app.data.remote.dto.SleepRatingRequestDto
import com.ultiq.app.data.remote.dto.SleepRatingResponseDto
import com.ultiq.app.data.remote.dto.UserResponse
import com.ultiq.app.data.remote.dto.VerifyEmailRequest
import com.ultiq.app.data.remote.dto.WeeklyInsightDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
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

    @POST("auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequest)

    @POST("auth/verify-email/resend")
    suspend fun resendVerificationEmail()

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

    // §10 — On-device YAMNet detects snore/cough events during a sleep session;
    // the client batches them and uploads at session-end. Raw audio never
    // leaves the phone — only labels + timestamps + confidence are sent.
    @POST("sleep-audio-events/batch")
    suspend fun batchCreateSleepAudioEvents(
        @Body body: BatchCreateSleepAudioEventsDto
    ): List<SleepAudioEventDto>

    @GET("sleep-audio-events")
    suspend fun getSleepAudioEvents(
        @Query("sleep_record_id") sleepRecordId: String
    ): List<SleepAudioEventDto>

    // §10.x — Pro-tier audio clip flow. Backend issues short-lived presigned
    // URLs so the phone uploads AAC bytes directly to S3 (the API server
    // never sees the audio); the attach call then binds the s3_key to the
    // event row + records the duration for the playback UI.
    @POST("sleep-audio-events/clip-upload-url")
    suspend fun requestSleepAudioClipUploadUrl(
        @Body body: ClipUploadUrlRequestDto
    ): ClipUploadUrlResponseDto

    @POST("sleep-audio-events/{id}/clip")
    suspend fun attachSleepAudioClip(
        @Path("id") eventId: String,
        @Body body: AttachClipRequestDto
    ): SleepAudioEventDto

    @GET("sleep-audio-events/{id}/clip-url")
    suspend fun getSleepAudioClipPlaybackUrl(
        @Path("id") eventId: String
    ): ClipPlaybackUrlResponseDto

    @DELETE("sleep-audio-events/{id}/clip")
    suspend fun deleteSleepAudioClip(@Path("id") eventId: String)

    // §10 — Phone-pickup batch upload + lookup. Same pattern as sleep audio
    // events: client buffers individual events during the session, uploads
    // at save time, fetches them back when expanding a past record to show
    // the full per-pickup timeline.
    @POST("phone-pickups/batch")
    suspend fun batchCreatePhonePickups(
        @Body body: BatchCreatePhonePickupsDto
    ): List<PhonePickupDto>

    @GET("phone-pickups")
    suspend fun getPhonePickupsForSleep(
        @Query("sleep_id") sleepRecordId: String
    ): List<PhonePickupDto>

    /// §v2.16.18 — Mirror of getPhonePickupsForSleep for focus sessions.
    /// Backend GET /phone-pickups?session_id=X returns the per-pickup
    /// timeline a SessionRow expansion can render.
    @GET("phone-pickups")
    suspend fun getPhonePickupsForSession(
        @Query("session_id") sessionId: String
    ): List<PhonePickupDto>

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
        @Query("priority") priority: String?,
        /// §v2.17.2-sync-collision — Pass "false" from sync paths so the
        /// backend returns raw master rows. Without it, recurring events
        /// (especially DAILY in a ±year window) returned hundreds of
        /// expanded rows all sharing the master id, collapsed by Room's
        /// REPLACE strategy to the furthest-future instance — making the
        /// event invisible in every Room-backed read. Older builds + the
        /// web dashboard omit it; the backend defaults expansion on so
        /// their behaviour is unchanged.
        @Query("expand") expand: String? = null,
    ): List<CalendarEventDto>

    @GET("calendar/{id}")
    suspend fun getCalendarEvent(@Path("id") id: String): CalendarEventDto

    @PUT("calendar/{id}")
    suspend fun updateCalendarEvent(
        @Path("id") id: String,
        @Body event: CreateCalendarEventDto
    ): CalendarEventDto

    /// v2.16.0 — Per-occurrence toggle. Backend only honours `is_done`
    /// from the body when occurrence_date is present; every other field
    /// is ignored, so it's safe to send a freshly-built DTO with only
    /// `is_done` set. The occurrence date is the user-local date of the
    /// instance the user tapped.
    @PUT("calendar/{id}")
    suspend fun updateCalendarOccurrence(
        @Path("id") id: String,
        @Query("occurrence_date") occurrenceDate: String,
        @Body event: CreateCalendarEventDto
    ): CalendarEventDto

    @DELETE("calendar/{id}")
    suspend fun deleteCalendarEvent(@Path("id") id: String)

    /// v2.16.0 — Per-occurrence delete. Appends the date to the master
    /// row's excluded_dates; the master row itself stays so every
    /// other occurrence in the series continues to render.
    @DELETE("calendar/{id}")
    suspend fun deleteCalendarOccurrence(
        @Path("id") id: String,
        @Query("occurrence_date") occurrenceDate: String,
    )

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

    // §024 — Per-day completion routes for recurring items. The plain
    // /complete + /uncomplete pair only flips the boolean, which is
    // wrong for recurring rows because the single `lastCompletedEpochDay`
    // column couldn't remember more than one tick. These endpoints
    // operate on checklist_completions (one row per item, day).
    @POST("checklist/{id}/complete-on/{epochDay}")
    suspend fun completeChecklistItemOn(
        @Path("id") id: String,
        @Path("epochDay") epochDay: Long,
    ): ChecklistItemDto

    @POST("checklist/{id}/uncomplete-on/{epochDay}")
    suspend fun uncompleteChecklistItemOn(
        @Path("id") id: String,
        @Path("epochDay") epochDay: Long,
    ): ChecklistItemDto

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

    // §9.5 — Parse a free-form sentence into a calendar event or checklist
    // item via Sonnet tool-calling. `hint` pins the surface the user is on so
    // the server forces the matching tool. The client pre-fills the existing
    // create dialog with the response — the user still confirms before save.
    @POST("ai/parse-event")
    suspend fun parseEvent(@Body body: ParseEventRequestDto): ParseEventResponseDto

    // §9.8 — Register this device's FCM token so the backend can target push
    // notifications. Called after login + every time Firebase rotates the
    // token via onNewToken. Backend upserts on the token column.
    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceTokenRequest): DeviceTokenResponse

    @HTTP(method = "DELETE", path = "devices/register", hasBody = true)
    suspend fun unregisterDevice(@Body body: RegisterDeviceTokenRequest)

    // §9.8 — Read-only fetch of the latest anomaly alert (last 24h). Pure
    // DB read, never triggers Bedrock. The Dashboard polls this on load
    // to render the alert card.
    @GET("ai/anomaly")
    suspend fun getLatestAnomaly(): LatestAnomalyDto

    // §10 — One-shot AI rating for a sleep session. Server hands the stats
    // to Haiku and returns a 1-5 rating + one-line justification. The End
    // Sleep dialog uses this as a suggestion alongside self-rate stars.
    @POST("ai/sleep-rating")
    suspend fun aiSleepRating(@Body body: SleepRatingRequestDto): SleepRatingResponseDto

    // §9.6 — Coach chat. One active conversation per user; the messages
    // list endpoint returns empty for a fresh user (no row to fetch).
    @GET("ai/chat/messages")
    suspend fun listChatMessages(@Query("limit") limit: Int? = null): List<ChatMessageDto>

    @POST("ai/chat/messages")
    suspend fun sendChatMessage(@Body body: ChatSendRequestDto): ChatSendResponseDto

    @POST("ai/chat/reset")
    suspend fun resetChat(): ChatResetResponseDto
}
