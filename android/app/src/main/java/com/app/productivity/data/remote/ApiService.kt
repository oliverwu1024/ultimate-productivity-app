package com.app.productivity.data.remote

import com.app.productivity.data.remote.dto.AuthResponse
import com.app.productivity.data.remote.dto.CreateSessionDto
import com.app.productivity.data.remote.dto.CreateSleepRecordDto
import com.app.productivity.data.remote.dto.LoginRequest
import com.app.productivity.data.remote.dto.RegisterRequest
import com.app.productivity.data.remote.dto.SessionDto
import com.app.productivity.data.remote.dto.SessionStatsDto
import com.app.productivity.data.remote.dto.SleepRecordDto
import com.app.productivity.data.remote.dto.SleepStatsDto
import com.app.productivity.data.remote.dto.UpdateSessionDto
import com.app.productivity.data.remote.dto.UserResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
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
}
