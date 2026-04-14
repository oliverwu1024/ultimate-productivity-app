package com.app.productivity.data.remote

import com.app.productivity.data.remote.dto.AuthResponse
import com.app.productivity.data.remote.dto.CreateSleepRecordDto
import com.app.productivity.data.remote.dto.LoginRequest
import com.app.productivity.data.remote.dto.RegisterRequest
import com.app.productivity.data.remote.dto.SleepRecordDto
import com.app.productivity.data.remote.dto.SleepStatsDto
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
}
