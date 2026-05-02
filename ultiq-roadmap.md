# Ultiq — Build Plan

*Your daily productivity companion.*

## Stack

| Layer            | Tech                     |
| ---------------- | ------------------------ |
| Android UI       | Kotlin + Jetpack Compose |
| Backend API      | Rust + Axum              |
| Database (cloud) | AWS RDS PostgreSQL       |
| Database (local) | SQLite (Room)            |
| ORM (Rust)       | SQLx                     |
| Auth             | JWT tokens               |
| HTTP Client      | Retrofit                 |
| Container        | Docker + AWS ECR         |
| Compute          | AWS ECS Fargate          |
| Load Balancer    | AWS ALB + ACM (HTTPS)    |
| Secrets          | AWS Secrets Manager      |
| Monitoring       | AWS CloudWatch           |
| DNS              | AWS Route 53             |
| CI/CD            | GitHub Actions → AWS     |

---

## Phase 0: Project Scaffold & Auth (Weeks 1–2)

Everything needed before we can build features. Two projects, one database, working auth.

### 0.1 — Backend Project Setup

**Create the Rust project:**

```
backend/
├── Cargo.toml
├── .env                          # DATABASE_URL, JWT_SECRET
├── src/
│   ├── main.rs                   # Axum server entry point, router setup
│   ├── config.rs                 # Load env vars, app config struct
│   ├── error.rs                  # AppError type, IntoResponse impl for consistent JSON errors
│   ├── db.rs                     # PgPool setup, connection helper
│   ├── middleware/
│   │   └── auth.rs               # JWT extraction + validation middleware
│   ├── models/
│   │   ├── mod.rs
│   │   └── user.rs               # User struct, CreateUser, LoginUser
│   ├── routes/
│   │   ├── mod.rs                # Merge all routers
│   │   └── auth.rs               # /auth/* handlers
│   └── migrations/
│       └── 001_create_users.sql
```

**Cargo.toml dependencies:**
- `axum` — web framework
- `tokio` (full features) — async runtime
- `sqlx` (postgres, runtime-tokio, tls-rustls, migrate) — database
- `serde` + `serde_json` — serialization
- `jsonwebtoken` — JWT encode/decode
- `argon2` — password hashing
- `dotenvy` — load .env file
- `uuid` (v4, serde) — primary keys
- `chrono` (serde) — timestamps
- `tower-http` (cors) — CORS middleware
- `tracing` + `tracing-subscriber` — logging

**`main.rs` must:**
1. Load `.env` with `dotenvy::dotenv()`
2. Init tracing subscriber
3. Create `PgPool` from `DATABASE_URL`
4. Run SQLx migrations on startup
5. Build the Axum router with all route groups merged
6. Add CORS layer (allow all origins in dev)
7. Bind to `0.0.0.0:8080` and serve

### 0.2 — Docker Compose for Local Dev Database

> **Local dev only.** Production uses AWS RDS PostgreSQL — see Phase 6.2. This setup keeps your laptop self-contained so you don't burn cloud spend during day-to-day coding.

**`docker-compose.dev.yml`:**

```yaml
services:
  db:
    image: postgres:16
    restart: unless-stopped
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: devpass
      POSTGRES_DB: productivity
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

**`.env` for backend:**
```
DATABASE_URL=postgres://dev:devpass@localhost:5432/productivity
JWT_SECRET=dev-secret-change-in-prod
```

### 0.3 — Users Table & Auth Endpoints

**Migration `001_create_users.sql`:**

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email       VARCHAR(255) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Models (`models/user.rs`):**

```rust
// Database row
struct User {
    id: Uuid,
    email: String,
    password_hash: String,
    created_at: DateTime<Utc>,
}

// Request: register
struct CreateUser {
    email: String,
    password: String,
}

// Request: login
struct LoginUser {
    email: String,
    password: String,
}

// Response: return to client (no password hash)
struct UserResponse {
    id: Uuid,
    email: String,
    created_at: DateTime<Utc>,
}

// JWT claims
struct Claims {
    sub: String,    // user id
    exp: usize,     // expiry timestamp
}

// Response: after login/register
struct AuthResponse {
    token: String,
    user: UserResponse,
}
```

**Endpoints (`routes/auth.rs`):**

| Method | Path             | Body                        | Response          | Auth? |
|--------|------------------|-----------------------------|-------------------|-------|
| POST   | `/auth/register` | `{ email, password }`       | `AuthResponse`    | No    |
| POST   | `/auth/login`    | `{ email, password }`       | `AuthResponse`    | No    |
| GET    | `/auth/me`       | —                           | `UserResponse`    | Yes   |

**`POST /auth/register` logic:**
1. Validate email format (contains `@`, non-empty)
2. Validate password length (min 8 characters)
3. Check if email already exists → 409 Conflict if so
4. Hash password with `argon2::hash_encoded()`
5. Insert into `users` table
6. Generate JWT token (24h expiry) containing user ID in `sub` claim
7. Return `AuthResponse { token, user }`

**`POST /auth/login` logic:**
1. Query user by email → 401 if not found
2. Verify password with `argon2::verify_encoded()` → 401 if wrong
3. Generate JWT token (24h expiry)
4. Return `AuthResponse { token, user }`

**`GET /auth/me` logic:**
1. Auth middleware extracts and validates JWT from `Authorization: Bearer <token>` header
2. Decode token, extract `sub` (user ID)
3. Query user by ID → 401 if not found
4. Return `UserResponse`

**Auth middleware (`middleware/auth.rs`):**
- Axum extractor that reads `Authorization` header
- Strips `Bearer ` prefix
- Decodes JWT using `JWT_SECRET`
- Validates expiry
- Returns `Claims` to the handler (or 401 response)

### 0.4 — Android Project Setup

**Create the Android project in Android Studio:**
- Template: Empty Compose Activity
- Package name: `com.ultiq.app`
- Min SDK: API 26 (Android 8.0)
- Target SDK: API 34

**Project structure:**

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ultiq/app/
│       │   ├── MainActivity.kt              # Single activity, hosts Compose
│       │   ├── UltiqApp.kt           # Application class
│       │   ├── navigation/
│       │   │   └── AppNavigation.kt         # NavHost + route definitions
│       │   ├── ui/
│       │   │   ├── theme/
│       │   │   │   ├── Theme.kt             # Material3 theme
│       │   │   │   ├── Color.kt             # Color palette
│       │   │   │   └── Type.kt              # Typography
│       │   │   ├── auth/
│       │   │   │   ├── LoginScreen.kt
│       │   │   │   ├── RegisterScreen.kt
│       │   │   │   └── AuthViewModel.kt
│       │   │   ├── dashboard/
│       │   │   ├── sleep/
│       │   │   ├── sessions/
│       │   │   └── calendar/
│       │   ├── data/
│       │   │   ├── local/
│       │   │   │   ├── AppDatabase.kt       # Room database
│       │   │   │   ├── dao/                  # Room DAOs (one per entity)
│       │   │   │   └── entity/              # Room entities (one per table)
│       │   │   ├── remote/
│       │   │   │   ├── ApiService.kt        # Retrofit interface
│       │   │   │   ├── RetrofitClient.kt    # Retrofit singleton + OkHttp setup
│       │   │   │   ├── AuthInterceptor.kt   # Attaches JWT to requests
│       │   │   │   └── dto/                 # API request/response data classes
│       │   │   └── repository/              # Repository classes (local + remote)
│       │   └── util/
│       │       ├── TokenManager.kt          # DataStore for JWT storage
│       │       └── DateUtils.kt             # Date formatting helpers
│       └── res/
│           ├── values/
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── drawable/
```

**Gradle dependencies (`app/build.gradle.kts`):**
- Jetpack Compose BOM (latest stable)
- `androidx.navigation:navigation-compose` — navigation
- `androidx.room:room-runtime` + `room-ktx` + `room-compiler` (ksp) — local DB
- `com.squareup.retrofit2:retrofit` + `converter-gson` — HTTP client
- `com.squareup.okhttp3:okhttp` + `logging-interceptor` — HTTP logging
- `androidx.datastore:datastore-preferences` — store JWT token
- `androidx.lifecycle:lifecycle-viewmodel-compose` — ViewModels
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` — coroutines

### 0.5 — Android Auth Flow

**`TokenManager.kt`:**
- Uses Jetpack DataStore (Preferences)
- `saveToken(token: String)` — stores JWT
- `getToken(): Flow<String?>` — reads JWT
- `clearToken()` — logout

**`RetrofitClient.kt`:**
- Base URL: `http://10.0.2.2:8080` (emulator → host localhost) or your machine's local IP for physical device
- `AuthInterceptor`: reads token from `TokenManager`, attaches `Authorization: Bearer <token>` header to every request
- `OkHttpClient` with `HttpLoggingInterceptor` (for debug builds) + `AuthInterceptor`
- Single `Retrofit` instance with `GsonConverterFactory`

**`ApiService.kt`:**

```kotlin
interface ApiService {
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @GET("auth/me")
    suspend fun getMe(): UserResponse
}
```

**`AuthViewModel.kt`:**
- State: `isLoading`, `error`, `isLoggedIn`
- `login(email, password)` → call API → save token → navigate to dashboard
- `register(email, password)` → call API → save token → navigate to dashboard
- `checkAuth()` → read token from DataStore → call `/auth/me` → if valid, go to dashboard; if not, go to login
- `logout()` → clear token → navigate to login

**`LoginScreen.kt`:**
- Email text field
- Password text field (with visibility toggle)
- "Login" button
- "Don't have an account? Register" link → navigates to RegisterScreen
- Show error messages from ViewModel
- Show loading spinner when `isLoading`

**`RegisterScreen.kt`:**
- Email text field
- Password text field
- Confirm password text field (validate match client-side)
- "Register" button
- "Already have an account? Login" link
- Same error/loading handling

**`AppNavigation.kt`:**
- Routes: `login`, `register`, `dashboard`, `sleep`, `sessions`, `calendar`
- Start destination: check if token exists → `dashboard` or `login`
- Bottom navigation bar with 4 tabs: Dashboard, Sleep, Sessions, Calendar
- Bottom bar only visible on main screens (not auth screens)

### 0.6 — Verify Everything Works

At this point, confirm:
- [ ] `docker compose -f docker-compose.dev.yml up -d` starts Postgres
- [ ] `cargo run` starts the backend on port 8080
- [ ] `POST /auth/register` creates a user and returns a token
- [ ] `POST /auth/login` returns a token for valid credentials
- [ ] `GET /auth/me` with the token returns the user
- [ ] Android app launches with login screen
- [ ] Register from the app → lands on empty dashboard
- [ ] Login from the app → lands on empty dashboard
- [ ] Kill app and reopen → stays logged in (token persisted)

---

## Phase 1: Sleep Tracking — End to End (Weeks 3–5)    Done 4/14 (1.1–1.6, 1.8 code complete; 1.7 reworking to session-based UX)

### 1.1 — Sleep Backend: Database Tables

**Migration `002_create_sleep_tables.sql`:**

```sql
CREATE TABLE sleep_records (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_bedtime  TIME NOT NULL,
    target_wake_time TIME NOT NULL,
    actual_bedtime  TIMESTAMPTZ NOT NULL,
    actual_wake_time TIMESTAMPTZ NOT NULL,
    quality_rating  SMALLINT NOT NULL CHECK (quality_rating BETWEEN 1 AND 5),
    phone_pickups   INT NOT NULL DEFAULT 0,
    total_phone_minutes INT,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sleep_records_user_id ON sleep_records(user_id);
CREATE INDEX idx_sleep_records_actual_bedtime ON sleep_records(user_id, actual_bedtime);

CREATE TABLE phone_pickups (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sleep_record_id     UUID REFERENCES sleep_records(id) ON DELETE SET NULL,
    session_id          UUID,  -- FK added later when sessions table exists
    picked_up_at        TIMESTAMPTZ NOT NULL,
    duration_seconds    INT NOT NULL DEFAULT 0,
    app_category        VARCHAR(50),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_phone_pickups_sleep ON phone_pickups(sleep_record_id);
```

### 1.2 — Sleep Backend: Models

**`models/sleep.rs`:**

```rust
// Database row
struct SleepRecord {
    id: Uuid,
    user_id: Uuid,
    target_bedtime: NaiveTime,
    target_wake_time: NaiveTime,
    actual_bedtime: DateTime<Utc>,
    actual_wake_time: DateTime<Utc>,
    quality_rating: i16,
    phone_pickups: i32,
    total_phone_minutes: Option<i32>,
    notes: Option<String>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

// Request: create/update
struct CreateSleepRecord {
    target_bedtime: NaiveTime,
    target_wake_time: NaiveTime,
    actual_bedtime: DateTime<Utc>,
    actual_wake_time: DateTime<Utc>,
    quality_rating: i16,          // 1-5
    phone_pickups: i32,
    total_phone_minutes: Option<i32>,
    notes: Option<String>,
}

// Response: sleep stats
struct SleepStats {
    avg_duration_minutes: f64,
    avg_quality: f64,
    total_records: i64,
    sleep_debt_minutes: f64,      // target - actual over period
    avg_phone_pickups: f64,
    best_quality_day: Option<String>,
    worst_quality_day: Option<String>,
}
```

**`models/phone_pickup.rs`:**

```rust
struct PhonePickup {
    id: Uuid,
    user_id: Uuid,
    sleep_record_id: Option<Uuid>,
    session_id: Option<Uuid>,
    picked_up_at: DateTime<Utc>,
    duration_seconds: i32,
    app_category: Option<String>,
    created_at: DateTime<Utc>,
}

struct CreatePhonePickup {
    sleep_record_id: Option<Uuid>,
    session_id: Option<Uuid>,
    picked_up_at: DateTime<Utc>,
    duration_seconds: i32,
    app_category: Option<String>,
}
```

### 1.3 — Sleep Backend: API Endpoints

**Add `routes/sleep.rs`:**

| Method | Path                         | Body / Params                           | Response                | Auth? |
|--------|------------------------------|-----------------------------------------|-------------------------|-------|
| POST   | `/sleep`                     | `CreateSleepRecord`                     | `SleepRecord`           | Yes   |
| GET    | `/sleep`                     | Query: `?start=DATE&end=DATE`           | `Vec<SleepRecord>`      | Yes   |
| GET    | `/sleep/:id`                 | —                                       | `SleepRecord`           | Yes   |
| PUT    | `/sleep/:id`                 | `CreateSleepRecord`                     | `SleepRecord`           | Yes   |
| DELETE | `/sleep/:id`                 | —                                       | 204 No Content          | Yes   |
| GET    | `/sleep/stats`               | Query: `?range=week\|month\|custom&start=DATE&end=DATE` | `SleepStats` | Yes   |

**`POST /sleep` logic:**
1. Extract user ID from auth middleware
2. Validate: `actual_bedtime < actual_wake_time`
3. Validate: `quality_rating` between 1 and 5
4. Validate: `phone_pickups >= 0`
5. Insert into `sleep_records`
6. Return the created record with 201

**`GET /sleep` logic:**
1. Extract user ID from auth middleware
2. Parse optional `start` and `end` date query params
3. Default: last 30 days if no params
4. Query `sleep_records WHERE user_id = $1 AND actual_bedtime BETWEEN $2 AND $3 ORDER BY actual_bedtime DESC`
5. Return array

**`GET /sleep/stats` logic:**
1. Parse `range` param: `week` = last 7 days, `month` = last 30 days, `custom` = use `start`/`end`
2. Query all records in range
3. Calculate:
   - `avg_duration_minutes`: AVG of (actual_wake_time - actual_bedtime) in minutes
   - `avg_quality`: AVG of quality_rating
   - `total_records`: COUNT
   - `sleep_debt_minutes`: SUM of ((target_wake_time - target_bedtime) - (actual_wake_time - actual_bedtime))
   - `avg_phone_pickups`: AVG of phone_pickups
   - `best_quality_day` / `worst_quality_day`: date with MAX/MIN quality_rating

**Add `routes/phone_pickups.rs`:**

| Method | Path                          | Body / Params                     | Response              | Auth? |
|--------|-------------------------------|-----------------------------------|-----------------------|-------|
| POST   | `/phone-pickups`              | `CreatePhonePickup`               | `PhonePickup`         | Yes   |
| GET    | `/phone-pickups`              | Query: `?sleep_id=UUID` or `?session_id=UUID` | `Vec<PhonePickup>` | Yes |

### 1.4 — Sleep Android: Local Database

**Room entity (`data/local/entity/SleepRecordEntity.kt`):**

```kotlin
@Entity(tableName = "sleep_records")
data class SleepRecordEntity(
    @PrimaryKey val id: String,          // UUID as string
    val userId: String,
    val targetBedtime: String,           // "HH:mm" format
    val targetWakeTime: String,
    val actualBedtime: Long,             // epoch millis
    val actualWakeTime: Long,
    val qualityRating: Int,
    val phonePickups: Int,
    val totalPhoneMinutes: Int?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false        // track sync state
)
```

**Room DAO (`data/local/dao/SleepDao.kt`):**

```kotlin
@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_records ORDER BY actualBedtime DESC")
    fun getAllRecords(): Flow<List<SleepRecordEntity>>

    @Query("SELECT * FROM sleep_records WHERE actualBedtime BETWEEN :start AND :end ORDER BY actualBedtime DESC")
    fun getRecordsBetween(start: Long, end: Long): Flow<List<SleepRecordEntity>>

    @Query("SELECT * FROM sleep_records WHERE id = :id")
    suspend fun getById(id: String): SleepRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SleepRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<SleepRecordEntity>)

    @Delete
    suspend fun delete(record: SleepRecordEntity)

    @Query("SELECT * FROM sleep_records WHERE isSynced = 0")
    suspend fun getUnsyncedRecords(): List<SleepRecordEntity>

    @Query("UPDATE sleep_records SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT AVG(actualWakeTime - actualBedtime) FROM sleep_records WHERE actualBedtime BETWEEN :start AND :end")
    suspend fun avgDurationBetween(start: Long, end: Long): Double?

    @Query("SELECT AVG(qualityRating) FROM sleep_records WHERE actualBedtime BETWEEN :start AND :end")
    suspend fun avgQualityBetween(start: Long, end: Long): Double?

    @Query("SELECT AVG(phonePickups) FROM sleep_records WHERE actualBedtime BETWEEN :start AND :end")
    suspend fun avgPhonePickupsBetween(start: Long, end: Long): Double?
}
```

**Add to `AppDatabase.kt`:**
- Add `SleepRecordEntity` to `@Database(entities = [...])`
- Add abstract `fun sleepDao(): SleepDao`

### 1.5 — Sleep Android: API Service

**Add to `ApiService.kt`:**

```kotlin
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
```

**DTOs (`data/remote/dto/SleepDtos.kt`):**
- `CreateSleepRecordDto` — matches the backend's `CreateSleepRecord`
- `SleepRecordDto` — matches the backend's `SleepRecord` response
- `SleepStatsDto` — matches the backend's `SleepStats`
- Extension functions: `SleepRecordDto.toEntity()` and `SleepRecordEntity.toDto()`

### 1.6 — Sleep Android: Repository

**`data/repository/SleepRepository.kt`:**

```kotlin
class SleepRepository(
    private val sleepDao: SleepDao,
    private val apiService: ApiService
) {
    // Returns local data as Flow (UI always reads from local)
    fun getSleepRecords(): Flow<List<SleepRecordEntity>>

    // Returns records in date range
    fun getSleepRecordsBetween(start: Long, end: Long): Flow<List<SleepRecordEntity>>

    // Create: save locally first (offline-first), then push to server
    suspend fun createSleepRecord(record: CreateSleepRecordDto): Result<SleepRecordEntity>
        // 1. Generate UUID client-side
        // 2. Save to Room with isSynced = false
        // 3. Try to POST to API
        // 4. If success: mark isSynced = true
        // 5. If fail: leave as unsynced (will retry later)

    // Update: update locally, then push
    suspend fun updateSleepRecord(id: String, record: CreateSleepRecordDto): Result<SleepRecordEntity>

    // Delete: delete locally, then push
    suspend fun deleteSleepRecord(id: String): Result<Unit>

    // Sync: pull from server, merge into local DB
    suspend fun sync()
        // 1. Push all unsynced local records to server
        // 2. Pull all records from server
        // 3. Upsert into Room (server data wins on conflict)

    // Stats from local DB
    suspend fun getLocalStats(start: Long, end: Long): SleepStats
}
```

### 1.7 — Sleep Android: Session-Based Tracking

Two ways to log sleep:

**A. Live session (primary flow):**
1. Tap **"Start Sleep"** at bedtime → records `actual_bedtime`, starts foreground service
2. Service auto-tracks phone pickups via `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` events
3. Tap **"End Sleep"** on waking → records `actual_wake_time`, stops service
4. Quick dialog: rate quality (1–5 stars) + optional notes → saves the record

**B. Manual log (fallback):**
- "Log Past Sleep" option for when the user forgot to start a session
- Full form: target times, actual times, quality, phone pickups, notes

**Future:** Smartwatch integration for automatic sleep/wake detection, heart rate, movement data.

---

#### `service/SleepTrackingService.kt` — Foreground Service

- Starts when user taps "Start Sleep"
- Shows persistent notification ("Sleep tracking active — tap to open")
- Registers `BroadcastReceiver` for `ACTION_SCREEN_ON` and `ACTION_SCREEN_OFF`
- On each `SCREEN_ON`: increment pickup count, record `picked_up_at` timestamp
- On each `SCREEN_OFF`: calculate duration since last `SCREEN_ON`, add to `total_phone_minutes`
- Stores pickup count + total minutes in-memory (survives via service lifecycle)
- Stops when user taps "End Sleep"
- Returns tracked data (pickups, phone minutes, bedtime) to ViewModel

**AndroidManifest.xml additions:**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".service.SleepTrackingService"
    android:foregroundServiceType="health" />
```

#### `ui/sleep/SleepViewModel.kt`

State:
```kotlin
data class SleepUiState(
    val records: List<SleepRecordEntity> = emptyList(),
    val stats: SleepStats? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.WEEK,
    // Session tracking
    val isSessionActive: Boolean = false,
    val sessionStartTime: Long? = null,       // epoch millis
    val phonePickups: Int = 0,
    val totalPhoneMinutes: Int = 0,
    // Dialogs
    val showEndSleepDialog: Boolean = false,   // quality + notes prompt
    val showManualLogDialog: Boolean = false,   // full manual form
)
```

Functions:
- `startSleepSession()` — start foreground service, update state
- `endSleepSession()` — stop service, show end-sleep dialog
- `saveSessionRecord(quality, notes)` — create record from session data
- `addManualRecord(record)` — create record from manual form
- `loadRecords()` / `loadStats(range)` / `deleteRecord(id)` / `sync()` — unchanged

#### `ui/sleep/SleepScreen.kt`

Layout (top to bottom):
1. **Session control** (replaces FAB):
   - **Not tracking**: Large "Start Sleep" button (moon icon) + small "Log Past Sleep" text link
   - **Tracking**: "Sleeping..." card with elapsed time, pickup count, and "End Sleep" button
2. **Stats cards row** (horizontal scroll): Avg Duration, Avg Quality, Sleep Debt, Avg Phone Pickups
3. **Bar chart**: last 7 days (colored by quality, target line)
4. **Tab row**: Week | Month toggle
5. **Sleep history list**: date, duration, quality stars, pickups — tap to expand, swipe to delete

#### `ui/sleep/EndSleepDialog.kt`

Bottom sheet shown after tapping "End Sleep":
1. **Summary**: "You slept 7h 23m — 3 phone pickups (12 min)"
2. **Quality rating** — row of 5 tappable stars
3. **Notes** — optional multiline text field
4. **Save** / **Cancel** buttons

#### `ui/sleep/AddSleepDialog.kt` (manual fallback)

Full form for logging past sleep — same as before:
1. Target bedtime / wake time (time pickers)
2. Actual bedtime / wake time (date + time pickers)
3. Quality rating (stars)
4. Phone pickups (stepper)
5. Total phone minutes (number input)
6. Notes
7. Save / Cancel

### 1.8 — Sleep: Charting    Done 4/14

**Vico charting library.**

Gradle dependency: `com.patrykandpatrick.vico:compose-m3:2.0.0-beta.3`

**Bar chart composable (`ui/sleep/SleepChart.kt`):**
- X-axis: day labels (Mon, Tue, etc.)
- Y-axis: hours (0–12)
- Each bar = one night's sleep duration
- Bar color based on quality: green (4-5), yellow (3), red (1-2)
- Horizontal line = average target duration
- 3 stacked series (green/yellow/red) with zero-fill for correct per-bar coloring

---

## Phase 2: Pomodoro Sessions — End to End (Weeks 6–8)

### 2.1 — Sessions Backend: Database Table

**Migration `003_create_sessions.sql`:**

```sql
CREATE TABLE productivity_sessions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tag             VARCHAR(100) NOT NULL,
    duration_minutes INT NOT NULL,
    work_duration   INT NOT NULL,          -- pomodoro work length in minutes
    break_duration  INT NOT NULL,          -- pomodoro break length in minutes
    phone_pickups   INT NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    completed       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_user_id ON productivity_sessions(user_id);
CREATE INDEX idx_sessions_started_at ON productivity_sessions(user_id, started_at);
```

**Also add the FK to phone_pickups:**

```sql
ALTER TABLE phone_pickups
ADD CONSTRAINT fk_phone_pickups_session
FOREIGN KEY (session_id) REFERENCES productivity_sessions(id) ON DELETE SET NULL;

CREATE INDEX idx_phone_pickups_session ON phone_pickups(session_id);
```

### 2.2 — Sessions Backend: Models

**`models/session.rs`:**

```rust
struct ProductivitySession {
    id: Uuid,
    user_id: Uuid,
    tag: String,
    duration_minutes: i32,
    work_duration: i32,
    break_duration: i32,
    phone_pickups: i32,
    started_at: DateTime<Utc>,
    ended_at: Option<DateTime<Utc>>,
    completed: bool,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

struct CreateSession {
    tag: String,
    work_duration: i32,        // e.g. 25
    break_duration: i32,       // e.g. 5
}

struct UpdateSession {
    ended_at: Option<DateTime<Utc>>,
    completed: Option<bool>,
    phone_pickups: Option<i32>,
    duration_minutes: Option<i32>,
}

struct SessionStats {
    total_focus_minutes_today: i64,
    total_focus_minutes_week: i64,
    sessions_completed_today: i64,
    sessions_completed_week: i64,
    current_streak_days: i64,        // consecutive days with at least 1 completed session
    longest_streak_days: i64,
    avg_phone_pickups_per_session: f64,
    total_phone_pickups_today: i64,
    top_tags: Vec<TagStat>,          // tag + total minutes, sorted by minutes desc
}

struct TagStat {
    tag: String,
    total_minutes: i64,
    session_count: i64,
}
```

### 2.3 — Sessions Backend: API Endpoints

**`routes/sessions.rs`:**

| Method | Path                      | Body / Params                     | Response                    | Auth? |
|--------|---------------------------|-----------------------------------|-----------------------------|-------|
| POST   | `/sessions`               | `CreateSession`                   | `ProductivitySession`       | Yes   |
| GET    | `/sessions`               | Query: `?start=DATE&end=DATE&tag=STRING` | `Vec<ProductivitySession>` | Yes |
| GET    | `/sessions/:id`           | —                                 | `ProductivitySession`       | Yes   |
| PUT    | `/sessions/:id`           | `UpdateSession`                   | `ProductivitySession`       | Yes   |
| DELETE | `/sessions/:id`           | —                                 | 204 No Content              | Yes   |
| GET    | `/sessions/stats`         | Query: `?range=week\|month`       | `SessionStats`              | Yes   |

**`POST /sessions` logic:**
1. Extract user ID
2. Set `started_at = now()`, `completed = false`, `duration_minutes = 0`
3. Insert and return

**`PUT /sessions/:id` logic:**
1. Verify session belongs to user
2. Update only the fields provided (partial update)
3. Set `updated_at = now()`
4. If `completed = true` and `ended_at` is not set, set `ended_at = now()`
5. Calculate `duration_minutes` from `ended_at - started_at`

**`GET /sessions/stats` streak logic:**
1. Query all distinct dates (from `started_at`) where user has at least 1 completed session, ordered DESC
2. Walk backwards from today: count consecutive days with no gaps → `current_streak_days`
3. Walk the full list: find longest consecutive run → `longest_streak_days`

### 2.4 — Sessions Android: Local Database

**Room entity (`data/local/entity/SessionEntity.kt`):**

```kotlin
@Entity(tableName = "productivity_sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val tag: String,
    val durationMinutes: Int,
    val workDuration: Int,
    val breakDuration: Int,
    val phonePickups: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val completed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false
)
```

**Room DAO (`data/local/dao/SessionDao.kt`):**
- `getAllSessions(): Flow<List<SessionEntity>>`
- `getSessionsBetween(start, end): Flow<List<SessionEntity>>`
- `getById(id): SessionEntity?`
- `insert(session)` / `insertAll(sessions)`
- `update(session)`
- `delete(session)`
- `getUnsyncedSessions(): List<SessionEntity>`
- `markSynced(id)`
- `getCompletedSessionDates(start, end): List<Long>` — for streak calculation
- `getTotalFocusMinutes(start, end): Int?`

### 2.5 — Sessions Android: API & Repository

**Add session endpoints to `ApiService.kt`** — mirrors the backend routes.

**`data/repository/SessionRepository.kt`:**
- Same offline-first pattern as `SleepRepository`
- `createSession(tag, workDuration, breakDuration)` — creates locally, syncs to server
- `completeSession(id, durationMinutes, phonePickups)` — updates with final data
- `getActiveSessions()` — sessions where completed = false
- `sync()`

### 2.6 — Sessions Android: UI — Timer Screen

**`ui/sessions/SessionsViewModel.kt`:**

State:
```kotlin
data class SessionsUiState(
    val timerState: TimerState = TimerState.IDLE,  // IDLE, RUNNING, PAUSED, BREAK, FINISHED
    val currentPhase: Phase = Phase.WORK,          // WORK or BREAK
    val timeRemainingSeconds: Int = 0,
    val totalTimeSeconds: Int = 0,
    val workDuration: Int = 25,                    // minutes, user-configurable
    val breakDuration: Int = 5,                    // minutes, user-configurable
    val tag: String = "",
    val completedPomodoros: Int = 0,               // count for current session
    val phonePickups: Int = 0,
    val todayStats: TodayStats? = null,
    val recentSessions: List<SessionEntity> = emptyList(),
    val error: String? = null,
)

data class TodayStats(
    val totalFocusMinutes: Int,
    val sessionsCompleted: Int,
    val currentStreak: Int,
    val phonePickupsToday: Int,
)
```

Functions:
- `startSession(tag)` — create session record, start countdown timer
- `pauseTimer()` / `resumeTimer()`
- `skipBreak()` — jump to next work phase
- `cancelSession()` — discard current session
- `completeSession()` — mark as completed, save to DB
- Timer logic: use `kotlinx.coroutines.delay(1000)` in a loop, decrement `timeRemainingSeconds`
- When work timer hits 0: auto-switch to break, increment `completedPomodoros`
- When break timer hits 0: auto-switch to work

**`ui/sessions/SessionsScreen.kt`:**

Layout (top to bottom):
1. **Today's stats bar** (horizontal row of mini stats):
   - Focus time today: "2h 15m"
   - Sessions: "4"
   - Streak: "5 days"
   - Distractions: "3 pickups"
2. **Timer circle** (center of screen, dominant visual):
   - Large circular progress indicator (Compose `Canvas` — draw arc)
   - Time remaining in center: "18:42"
   - Below circle: current phase label "WORK" or "BREAK"
   - Below that: tag name (e.g. "LeetCode")
3. **Timer controls** (row of buttons):
   - If IDLE: tag text field + work/break duration pickers + "Start" button
   - If RUNNING: "Pause" button + "Cancel" button
   - If PAUSED: "Resume" button + "Cancel" button
   - If BREAK: "Skip Break" button
4. **Recent sessions list** (bottom section, `LazyColumn`):
   - Each item: tag, duration, date, completed/cancelled, phone pickups
   - Show last 10 sessions

**`ui/sessions/TimerCircle.kt`:**
- Compose `Canvas` custom drawing
- Background circle (gray)
- Foreground arc (colored) representing progress: `sweepAngle = 360 * (timeRemaining / totalTime)`
- Color: work phase = blue/green, break phase = orange
- Animated smoothly using `animateFloatAsState`

### 2.7 — Sessions Android: Phone Pickup Detection

**Uses Android `UsageStatsManager`:**

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

**`util/PhoneUsageTracker.kt`:**
- Check if usage stats permission is granted: `AppOpsManager.checkOpNoThrow()`
- If not granted: show a prompt to open Settings → Apps → Special Access → Usage Access
- During an active session, poll `UsageStatsManager.queryEvents()` every 30 seconds
- Detect `MOVE_TO_FOREGROUND` events for non-system apps
- Each detected app open = one phone pickup
- Log pickups to local DB and increment counter in ViewModel

---

## Phase 3: Calendar — End to End (Weeks 9–11)

### 3.1 — Calendar Backend: Database Table

**Migration `004_create_calendar.sql`:**

```sql
CREATE TYPE event_category AS ENUM ('study', 'project', 'exercise', 'personal', 'other');
CREATE TYPE event_priority AS ENUM ('high', 'medium', 'low');

CREATE TABLE calendar_events (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ NOT NULL,
    category        event_category NOT NULL DEFAULT 'other',
    priority        event_priority NOT NULL DEFAULT 'medium',
    is_recurring    BOOLEAN NOT NULL DEFAULT FALSE,
    recurrence_rule TEXT,            -- e.g. "WEEKLY:MON,WED,FRI"
    color           VARCHAR(7) NOT NULL DEFAULT '#4A90D9',  -- hex color
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calendar_user_id ON calendar_events(user_id);
CREATE INDEX idx_calendar_start_time ON calendar_events(user_id, start_time);
```

### 3.2 — Calendar Backend: Models

**`models/calendar.rs`:**

```rust
struct CalendarEvent {
    id: Uuid,
    user_id: Uuid,
    title: String,
    description: Option<String>,
    start_time: DateTime<Utc>,
    end_time: DateTime<Utc>,
    category: EventCategory,       // enum: Study, Project, Exercise, Personal, Other
    priority: EventPriority,       // enum: High, Medium, Low
    is_recurring: bool,
    recurrence_rule: Option<String>,
    color: String,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

struct CreateCalendarEvent {
    title: String,
    description: Option<String>,
    start_time: DateTime<Utc>,
    end_time: DateTime<Utc>,
    category: EventCategory,
    priority: EventPriority,
    is_recurring: bool,
    recurrence_rule: Option<String>,
    color: Option<String>,         // defaults to category color if not provided
}
```

### 3.3 — Calendar Backend: API Endpoints

**`routes/calendar.rs`:**

| Method | Path                | Body / Params                                         | Response                | Auth? |
|--------|---------------------|-------------------------------------------------------|-------------------------|-------|
| POST   | `/calendar`         | `CreateCalendarEvent`                                 | `CalendarEvent`         | Yes   |
| GET    | `/calendar`         | Query: `?start=DATE&end=DATE&category=STR&priority=STR` | `Vec<CalendarEvent>`  | Yes   |
| GET    | `/calendar/:id`     | —                                                     | `CalendarEvent`         | Yes   |
| PUT    | `/calendar/:id`     | `CreateCalendarEvent`                                 | `CalendarEvent`         | Yes   |
| DELETE | `/calendar/:id`     | —                                                     | 204 No Content          | Yes   |

**`GET /calendar` with recurrence expansion:**
1. Query all events in date range (including recurring events whose `start_time` is before the range)
2. For each recurring event, parse `recurrence_rule`
3. Generate virtual event instances within the requested range
4. Merge one-time events + expanded recurring instances
5. Sort by `start_time` and return

**Recurrence rule format:** `"WEEKLY:MON,WED,FRI"` or `"DAILY"` or `"MONTHLY:15"` (day of month)

**Expansion logic (`expand_recurrence`):**
- Parse rule string
- Starting from event's `start_time`, step forward by rule interval
- For each step that falls within the query range, create a clone of the event with adjusted `start_time`/`end_time`
- Preserve the event duration (end - start stays the same)

### 3.4 — Calendar Android: Local Database

**Room entity (`data/local/entity/CalendarEventEntity.kt`):**

```kotlin
@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val category: String,      // "study", "project", etc.
    val priority: String,      // "high", "medium", "low"
    val isRecurring: Boolean,
    val recurrenceRule: String?,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false
)
```

**Room DAO:** same pattern — CRUD + date range queries + unsynced queries.

### 3.5 — Calendar Android: Repository & API

Same offline-first pattern. Add calendar endpoints to `ApiService.kt`.

**`CalendarRepository`:**
- `getEventsForMonth(year, month): Flow<List<CalendarEventEntity>>`
- `getEventsForDay(date): Flow<List<CalendarEventEntity>>`
- `createEvent(event)` / `updateEvent(id, event)` / `deleteEvent(id)`
- `sync()`
- Client-side recurrence expansion for local data (mirror the backend logic)

### 3.6 — Calendar Android: UI

**`ui/calendar/CalendarViewModel.kt`:**

State:
```kotlin
data class CalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val currentMonth: YearMonth = YearMonth.now(),
    val monthEvents: Map<LocalDate, List<CalendarEventEntity>> = emptyMap(),
    val selectedDayEvents: List<CalendarEventEntity> = emptyList(),
    val viewMode: ViewMode = ViewMode.MONTH,  // MONTH or DAY
    val showAddDialog: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)
```

**`ui/calendar/CalendarScreen.kt`:**

Layout:
1. **Month header**: "< April 2026 >" with left/right arrows to change month
2. **Day-of-week header row**: Mon Tue Wed Thu Fri Sat Sun
3. **Month grid** (6 rows × 7 columns):
   - Each cell = one day
   - Current day highlighted with circle
   - Selected day highlighted with filled circle
   - Days with events show colored dots below the number (up to 3 dots, one per event category)
   - Tap a day → select it, load that day's events below
4. **Selected day section** (below grid):
   - Date heading: "Monday, April 12"
   - Event list for that day, sorted by start time:
     - Each event: colored left border (event color) + time range + title + category chip + priority indicator
     - Tap event → edit dialog
   - If no events: "No events" message
5. **FAB**: "+" to add new event

**`ui/calendar/AddEventDialog.kt`:**

Bottom sheet with:
1. **Title** — text field (required)
2. **Description** — multiline text field (optional)
3. **Start date + time** — date picker + time picker
4. **End date + time** — date picker + time picker
5. **Category** — dropdown/chip selector: Study, Project, Exercise, Personal, Other
6. **Priority** — segmented button: High, Medium, Low
7. **Color** — row of color circles to pick from
8. **Recurring toggle** — switch
   - If on: recurrence rule selector — frequency (Daily/Weekly/Monthly) + day picker for weekly
9. **Save button** + **Cancel button**

Validation:
- Title must not be empty
- Start time must be before end time
- If recurring, recurrence rule must be set

**Use Kizitonwose Calendar library** for the month grid — handles month scrolling, day selection, and custom day cell rendering.

Gradle dependency: `com.kizitonwose.calendar:compose` (latest)

---

## Phase 4: Dashboard & Sync (Weeks 12–13)

### 4.1 — Dashboard Screen

**`ui/dashboard/DashboardViewModel.kt`:**

State:
```kotlin
data class DashboardUiState(
    val lastNightSleep: SleepSummary? = null,
    val todayFocus: FocusSummary? = null,
    val upcomingEvents: List<CalendarEventEntity> = emptyList(),
    val weeklyHighlights: WeeklyHighlights? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
)

data class SleepSummary(
    val duration: String,         // "7h 23m"
    val quality: Int,             // 1-5
    val vsTarget: String,         // "+30m" or "-1h 15m"
    val phonePickups: Int,
)

data class FocusSummary(
    val totalMinutesToday: Int,
    val sessionsToday: Int,
    val currentStreak: Int,
    val phonePickupsToday: Int,
)

data class WeeklyHighlights(
    val avgSleepDuration: String,
    val avgSleepQuality: Double,
    val totalFocusHours: Double,
    val eventsCompleted: Int,
    val eventsTotal: Int,
)
```

**`ui/dashboard/DashboardScreen.kt`:**

Layout (scrollable column):
1. **Greeting header**: "Good morning, Oliver" (based on time of day)
2. **Sync indicator**: small "Last synced: 2 min ago" text + manual refresh button

3. **Last Night's Sleep card**:
   - Big number: duration (e.g. "7h 23m")
   - Quality stars
   - vs target: green "+30m" or red "-1h 15m"
   - Phone pickups: icon + count
   - Tap → navigates to Sleep tab

4. **Today's Focus card**:
   - Focus time: "2h 15m"
   - Sessions: "4 completed"
   - Streak: "5 days" with flame icon
   - Phone distractions: count
   - Tap → navigates to Sessions tab

5. **Upcoming Events section**:
   - Next 5 events from calendar, each showing: time + title + category color
   - Tap event → navigates to Calendar tab with that day selected
   - "View all" link → Calendar tab

6. **Quick Actions row** (3 buttons):
   - "Log Sleep" → opens add sleep dialog
   - "Start Focus" → navigates to Sessions with timer ready
   - "Add Event" → opens add event dialog

7. **Weekly Highlights card** (summary of the week):
   - Avg sleep: duration + quality
   - Total focus: hours
   - Events: completed/total

### 4.2 — Sync System

**`data/repository/SyncManager.kt`:**

Coordinates sync across all repositories:

```kotlin
class SyncManager(
    private val sleepRepo: SleepRepository,
    private val sessionRepo: SessionRepository,
    private val calendarRepo: CalendarRepository,
) {
    // Full sync: push unsynced local data, pull remote data
    suspend fun syncAll(): Result<Unit> {
        return runCatching {
            // Push all unsynced records first
            sleepRepo.pushUnsynced()
            sessionRepo.pushUnsynced()
            calendarRepo.pushUnsynced()

            // Then pull latest from server
            sleepRepo.pullFromServer()
            sessionRepo.pullFromServer()
            calendarRepo.pullFromServer()
        }
    }

    // Triggered on:
    // 1. App launch (after auth check passes)
    // 2. Manual pull-to-refresh on dashboard
    // 3. Periodic background sync via WorkManager (every 30 min when app is in background)
}
```

**Background sync with WorkManager:**

```kotlin
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Get SyncManager instance
        // Call syncAll()
        // Return Result.success() or Result.retry()
    }
}

// Schedule in Application.onCreate():
// PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
```

### 4.3 — Pull-to-Refresh on Dashboard

- Wrap dashboard content in `SwipeRefresh` (from Accompanist or Material3 pull-to-refresh)
- On refresh: call `SyncManager.syncAll()`, then reload all dashboard data
- Show `isSyncing` indicator during sync

---

## Phase 5: Polish & Advanced Features (Weeks 14–19)

### 5.1 — Notifications & Reminders

**`util/NotificationHelper.kt`:**
- Create notification channel: "Reminders" (ID: `reminders`)
- Helper function to show a notification with title, body, and tap action (deep link to specific screen)

**Bedtime reminder:**
- User sets their target bedtime (stored in DataStore preferences)
- Schedule with `AlarmManager.setExactAndAllowWhileIdle()` for 30 minutes before target bedtime
- Notification: "Time to wind down — bedtime in 30 minutes"
- `BroadcastReceiver` to fire the notification

**Session reminder:**
- User can set a daily "focus time" reminder (e.g. "2:00 PM")
- Same AlarmManager pattern
- Notification: "Time to focus! Start a session?"
- Tap → opens app on Sessions tab

**Calendar event reminders:**
- When creating/updating an event, schedule a reminder 15 min before `start_time`
- Use `AlarmManager` with the event ID as extra
- Notification: "In 15 min: {event title}"
- Tap → opens app on Calendar tab with that day

**Morning summary notification:**
- Schedule daily at 8:00 AM (configurable)
- Content: "You slept {duration}. You have {count} events today."
- Query last night's sleep record + today's calendar events

### 5.2 — UI Theming & Polish

**Theme system:**
- `settings/SettingsScreen.kt` with theme toggle: Light / Dark / System
- Store preference in DataStore
- Apply in `Theme.kt` using `isSystemInDarkTheme()` + override

**Material 3 theme:**
- Define color schemes for light and dark in `Color.kt`
- Primary: a calm blue-purple
- Category colors: Study = blue, Project = green, Exercise = orange, Personal = purple, Other = gray
- Priority colors: High = red, Medium = yellow, Low = green

**Animations:**
- Screen transitions: slide in/out using Compose Navigation animations
- Timer: smooth arc animation via `animateFloatAsState`
- Stats cards: fade-in on load
- List items: slide-in animation using `AnimatedVisibility`

**Onboarding flow (`ui/onboarding/OnboardingScreen.kt`):**
- Screen 1: "Welcome" — app description + continue
- Screen 2: "Set Your Sleep Target" — bedtime + wake time pickers
- Screen 3: "Focus Preferences" — default work/break duration
- Screen 4: "All Set!" — done button
- Store `onboarding_completed = true` in DataStore
- Show only on first launch (before login/register)

**Empty states:**
- Sleep: illustration + "No sleep records yet. Log your first night!"
- Sessions: illustration + "Ready to focus? Start your first session!"
- Calendar: illustration + "No events today. Tap + to create one."
- Dashboard: show all sections with placeholder/empty content

### 5.3 — Weekly Reports & Streaks

**`ui/reports/WeeklyReportScreen.kt`:**

Accessed from dashboard or a "Reports" button in settings/nav.

Layout (scrollable):
1. **Week selector**: "< Apr 6 – Apr 12 >" with arrows
2. **Sleep section**:
   - Bar chart: 7 bars for daily sleep duration
   - Avg duration, avg quality, best/worst night
   - Phone usage during sleep: avg pickups/night, trend arrow (up/down vs last week)
3. **Focus section**:
   - Bar chart: 7 bars for daily focus hours
   - Total hours, sessions completed, top 3 tags with time breakdown
   - Phone distractions: total pickups, avg per session, best distraction-free streak
4. **Calendar section**:
   - Events completed vs total
   - Busiest day
   - Category breakdown (pie chart or horizontal bars)
5. **Streaks & Achievements section**:
   - Current sleep target streak: "5 nights" (hitting target bedtime/duration)
   - Current focus streak: "8 days" (at least 1 completed session/day)
   - Distraction-free sessions streak: "3 sessions" (0 phone pickups)
   - Milestones list: show earned achievements with icons

**Achievement definitions (hardcoded list):**

| Achievement | Condition |
|-------------|-----------|
| "Early Bird" | 7 consecutive days hitting target wake time |
| "Night Owl No More" | 7 consecutive days hitting target bedtime |
| "Focus Master" | 50 total focus hours |
| "Century" | 100 completed sessions |
| "Zen Mode" | 5 sessions with 0 phone pickups |
| "Iron Streak" | 30-day focus streak |
| "Sleep Champion" | 14-day sleep target streak |
| "Marathon" | 4+ hours focus in a single day |

Store earned achievements in Room table: `achievements(id, name, earned_at)`.
Check conditions after each sleep record save and session complete.

### 5.4 — Settings Screen

**`ui/settings/SettingsScreen.kt`:**

Options:
- **Sleep targets**: target bedtime, target wake time, target duration
- **Focus defaults**: work duration (default 25), break duration (default 5)
- **Notifications**: toggle each reminder type on/off, set times
- **Theme**: Light / Dark / System
- **Account**: email display, logout button
- **About**: app version

All preferences stored in DataStore.

### 5.5 — Phone Pickup Confirmation Gate

When a focus or sleep session is active, every screen-unlock pops a fullscreen "Are you sure?" prompt before the user can use the phone. Each bypass auto-increments the session's pickup counter — closing the loop with the existing distraction stats. This is **friction, not lockdown** — calls, alarms, notifications, and quick-replies all work normally.

**Why this design over usage-stats / accessibility-based blocking:**
- No `PACKAGE_USAGE_STATS`, no `SYSTEM_ALERT_WINDOW`, no accessibility service → much safer for Play Store review
- Same Android pattern that alarm-clock apps use (`USE_FULL_SCREEN_INTENT`) — well-established, accepted
- User always has the escape hatch (Confirm to proceed) → no permanent lockout
- Pickups become a deliberate, friction-laden choice → behavioral nudge without being adversarial

#### Detection: `ACTION_USER_PRESENT` BroadcastReceiver

Already-running foreground services from Phase 1.7 (sleep) and Phase 2 (focus sessions) register a receiver while the session is active:

| Action | When it fires | Used for |
|--------|---------------|----------|
| `ACTION_SCREEN_ON` | Screen turned on (still locked) | Pickup count (existing) |
| `ACTION_USER_PRESENT` | User has unlocked the device | Trigger lockout activity (new) |
| `ACTION_SCREEN_OFF` | Screen off | Calculate phone-on duration (existing) |

On `ACTION_USER_PRESENT` during an active session: launch `LockoutActivity` via a high-priority full-screen intent.

#### `ui/lockout/LockoutActivity.kt`

A single fullscreen activity, no nav, no system bars:

Layout:
- **Headline**: "Focus session active" or "Sleep session active"
- **Subtitle**: "You're {18 minutes} into your {25 min} focus session" (live elapsed/remaining)
- **Pickup count so far**: "Phone pickups this session: 3"
- Two prominent buttons:
  - **Cancel** (primary) — `finishAndRemoveTask()`, screen returns to lock screen state
  - **Yes, I need my phone** (secondary, intentionally less prominent) — increments pickup counter, logs to `phone_pickups` table with `session_id` set, `finishAndRemoveTask()` returns to home
- Tertiary text link: "End session early" — opens session-end flow (quality dialog for sleep, completion for focus)

Activity flags (in `onCreate()`):
```kotlin
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
setShowWhenLocked(true)
setTurnScreenOn(true)
```

#### `AndroidManifest.xml` additions

```xml
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<activity
    android:name=".ui.lockout.LockoutActivity"
    android:excludeFromRecents="true"
    android:launchMode="singleInstance"
    android:showOnLockScreen="true"
    android:turnScreenOn="true"
    android:exported="false"
    android:theme="@style/Theme.Lockout" />
```

`Theme.Lockout`: `Theme.Material3.NoActionBar` parent, opaque background, no system bars.

#### Wiring it into existing services

**`SleepTrackingService.kt`** (Phase 1.7) — already handles `ACTION_SCREEN_ON` for pickup counting. Add:
```kotlin
private val userPresentReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (settings.lockoutForSleep && isSessionActive) {
            val launch = Intent(context, LockoutActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("mode", "SLEEP")
                putExtra("session_started_at", sessionStartTime)
            }
            context.startActivity(launch)
        }
    }
}
// register in onStartCommand() with IntentFilter(Intent.ACTION_USER_PRESENT)
```

**`SessionTrackingService.kt`** (Phase 2) — needs to be promoted to a foreground service if it isn't already (so it can register the receiver and survive backgrounding). Same pattern with `mode = "FOCUS"`.

#### Settings (extends Phase 5.4)

Add a "**Focus Mode**" section to `SettingsScreen.kt`:
- **Lockout for focus sessions**: toggle (default **ON**)
- **Lockout for sleep sessions**: toggle (default **OFF** — sleep is for sleeping, friction adds nothing)
- **Show pickup count on lockout**: toggle (default ON — visible accountability is a feature)
- **Allow ending session from lockout**: toggle (default ON)

User can disable the whole feature globally if they decide it's annoying.

#### Edge cases (all handled by Android, no extra work)

| Scenario | Behavior |
|----------|----------|
| Incoming call | Telephony full-screen intent outranks ours → call UI shows normally |
| Alarm fires | Alarm full-screen intent outranks ours → alarm UI shows normally |
| Notification (heads-up) | Appears briefly over our activity, normal behavior |
| Lock-screen quick-reply | `ACTION_USER_PRESENT` does *not* fire on lock-screen actions → no trigger (good — important comms slip through) |
| Power button to check time | Only `ACTION_SCREEN_ON` fires, not `ACTION_USER_PRESENT` → no lockout trigger (good — glance at clock without penalty) |
| Boot during active session | `BOOT_COMPLETED` receiver re-launches the foreground service if a session row is unfinished |
| User force-stops app | Session ends as if cancelled; lockout disappears |

#### Play Store policy

`USE_FULL_SCREEN_INTENT` on Android 14+ requires apps to declare a use case in the manifest. **"Digital wellbeing / focus"** is an accepted category — be ready to justify in the Play Console submission alongside the privacy policy. Risk is much lower than for `PACKAGE_USAGE_STATS` or accessibility-service approaches.

#### Backend changes

None — the existing `phone_pickups` table (Phase 1.1) already supports `session_id`, and the `POST /phone-pickups` endpoint (Phase 1.3) already accepts the new rows. The lockout dismissal just calls the existing repository method with the active session ID.

### 5.6 — Daily Checklist + Focus Integration

A lightweight todo list separate from the calendar, with a dropdown on the Sessions screen so you don't have to retype tags. Distinction:

| Calendar event | Checklist item |
|----------------|----------------|
| "Study from 2–4pm" | "Review chapter 5" |
| Has start + end time | Has a due date, no time |
| Blocking your day | Open todo for the day |

#### 5.6.1 — Backend: schema + endpoints

**Migration `005_create_checklist.sql`:**

```sql
CREATE TABLE checklist_items (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    due_date        DATE NOT NULL,
    estimated_minutes INT,
    priority        SMALLINT NOT NULL DEFAULT 1 CHECK (priority IN (0, 1, 2)),
    completed       BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_checklist_user_due ON checklist_items(user_id, due_date);

ALTER TABLE productivity_sessions
ADD COLUMN checklist_item_id UUID REFERENCES checklist_items(id) ON DELETE SET NULL;
```

**Endpoints:**

| Method | Path                             | Body / Params                       | Response                | Auth? |
|--------|----------------------------------|-------------------------------------|-------------------------|-------|
| POST   | `/checklist`                     | `CreateChecklistItem`               | `ChecklistItem`         | Yes   |
| GET    | `/checklist`                     | `?start=DATE&end=DATE&completed=BOOL` | `Vec<ChecklistItem>`  | Yes   |
| GET    | `/checklist/today`               | —                                   | `Vec<ChecklistItem>`    | Yes   |
| PUT    | `/checklist/:id`                 | `UpdateChecklistItem`               | `ChecklistItem`         | Yes   |
| POST   | `/checklist/:id/complete`        | —                                   | `ChecklistItem`         | Yes   |
| DELETE | `/checklist/:id`                 | —                                   | 204 No Content          | Yes   |
| POST   | `/checklist/bulk`                | `Vec<CreateChecklistItem>`          | `Vec<ChecklistItem>`    | Yes   |

`POST /sessions` accepts an optional `checklist_item_id`; the FK is stored on the row so the session→item link is queryable later.

#### 5.6.2 — Android: data layer

`ChecklistEntity`, `ChecklistDao`, `ChecklistRepository` — same offline-first pattern as the other features. DAO highlights:
- `getByDate(epochDay): Flow<List<ChecklistEntity>>`
- `getOpenForDate(epochDay): Flow<List<ChecklistEntity>>` — sorted by priority desc
- `markCompleted(id, completedAt)`

`SessionEntity` gains a nullable `checklistItemId` field. Room migration `MIGRATION_4_5` creates `checklist_items` and adds the column to `productivity_sessions`.

#### 5.6.3 — Checklist screen + 5th nav tab

New top-level destination "**Checklist**" — bottom-nav tab inserted right after **Dashboard** (so order is Dashboard, Checklist, Sleep, Sessions, Calendar).

**Layout:**
1. **Date selector** (top): "← Today (Mon, Apr 27) →"
2. **Progress bar**: "3 of 7 done"
3. **Open items list** — checkbox + title + priority chip + estimated minutes
4. **Completed items** (collapsed by default) — strikethrough
5. **FAB** "+" to add a new item

**Add/edit dialog:** title, due date, optional estimated minutes, priority (Low / Med / High), optional description.

#### 5.6.4 — Weekly planning prompt

On app launch, if today is **Sunday or Monday** AND no checklist items exist for the upcoming week, show a one-time-per-week dialog:

> **Plan your week?**
> Sketch what you want to get done each day. Skip and fill in as you go.
> [ Plan now ] [ Skip ]

"Plan now" → opens **WeeklyPlannerScreen** — 7-day vertical list, each day a column with an editable list. Saves all items via `POST /checklist/bulk`. Storage: `last_planning_dismissed_week` in DataStore (ISO week number).

#### 5.6.5 — Focus session integration

The closing-the-loop part.

**`SessionsScreen.kt` — dropdown above the tag field** when there are open items for today:

- `ExposedDropdownMenuBox` listing today's open items (title + priority dot)
- Selecting one fills the tag with the item's title and stores `checklistItemId` in `SessionsUiState`
- Manual typing still works (clears `checklistItemId`)

**On session start** (`SessionsViewModel.startSession`):
- Pass `checklistItemId` to `repository.createSession(...)` → backend stores it on the session row

**On session complete:** always prompt:
> "Mark 'Review chapter 5' as done?"
> [ Yes ] [ No ]
> (Cancel / dismiss = No)

"Yes" → `POST /checklist/:id/complete`.

#### 5.6.6 — Dashboard hook

Add a "**Today's checklist**" card to the Dashboard between sleep and focus:
- "3 of 7 done" + tiny progress bar
- Top 3 open items as a preview list
- Tap → navigates to Checklist tab

---

## Phase 6: AWS Deployment + Marketing Site (Weeks 20–24)

The full deployment runs on AWS. The architecture below is the standard production pattern: container behind ALB, database in private subnets, secrets out of source code, logs/metrics in CloudWatch, deploys via GitHub Actions.

```
                       Internet
                          │
                    Route 53 (DNS)
                          │
              ALB (public subnets, HTTPS via ACM)
                          │
              ECS Fargate task (private subnet)
                Rust/Axum container, port 8080
                /                    \
        Secrets Manager           RDS Postgres
        (DB URL, JWT secret)      (private subnet, single AZ)

         CloudWatch Logs + Metrics + Alarms (everywhere)

   GitHub push → GH Actions → ECR → ECS service rolling update
```

### 6.1 — AWS Foundation

**One-time account setup:**
1. Create AWS account, enable MFA on the root user, lock root credentials away
2. Create IAM admin user (`oliver-admin`) with `AdministratorAccess` + MFA — use this for daily work, never the root user
3. Install AWS CLI v2 and configure: `aws configure --profile ultiq`
4. Pick a region (e.g. `us-east-1` or whichever is closest) and stick to it everywhere — cross-region data transfer costs add up fast
5. Set a billing alarm: Billing → Budgets → $20/month threshold with email alert

**Networking (VPC):**

| Resource | CIDR | Purpose |
|----------|------|---------|
| VPC | `10.0.0.0/16` | Top-level network |
| Public subnet A | `10.0.1.0/24` (AZ a) | ALB |
| Public subnet B | `10.0.2.0/24` (AZ b) | ALB (HA needs 2 AZs) |
| Private subnet A | `10.0.11.0/24` (AZ a) | ECS tasks + RDS primary |
| Private subnet B | `10.0.12.0/24` (AZ b) | RDS subnet group requires 2 AZs |

Plus:
- **Internet Gateway** attached to the VPC
- **NAT Gateway** in public subnet A (so private-subnet ECS tasks can reach ECR / Secrets Manager / CloudWatch). *Cost-saving alternative below.*
- **Route tables**: public → IGW; private → NAT
- **Security groups** (least-privilege chain):
  - `alb-sg`: 80, 443 inbound from `0.0.0.0/0`
  - `ecs-sg`: 8080 inbound from `alb-sg` only
  - `rds-sg`: 5432 inbound from `ecs-sg` only

**Cost-saving alternative to NAT Gateway** (~$32/month):
Replace it with **VPC Endpoints** for ECR (api + dkr), Secrets Manager, CloudWatch Logs, and S3 (gateway). Recommended for this project — same security posture at ~$0.01/hour per endpoint instead of $0.045/hour for NAT.

### 6.2 — Database (RDS + Secrets Manager)

**Create the RDS instance:**
- Engine: Postgres 16
- Instance class: `db.t4g.micro` (Graviton, ~$13/month — cheapest reasonable option)
- Storage: 20 GB gp3, encrypted at rest (KMS-managed)
- Multi-AZ: **off** (cost savings; turn on later if you need HA)
- VPC: the one from 6.1
- Subnet group: private subnets A + B
- Security group: `rds-sg`
- Public access: **no**
- Backup retention: 7 days
- Master username: `ultiq_admin`
- Master password: AWS-generated, immediately copied into Secrets Manager (next step)

**First-time migrations** (the database needs schema before the app can boot):
- **Recommended:** rely on `sqlx::migrate!()` running on app startup — already wired in Phase 0.1's `main.rs`. The first ECS task will apply migrations on boot.
- *Fallback if startup migrations are too slow:* run a one-off ECS task with `command: ["sqlx", "migrate", "run"]` before deploying the app service.

**Create two secrets in Secrets Manager:**

| Secret name | JSON value |
|-------------|------------|
| `ultiq/prod/database` | `{ "DATABASE_URL": "postgres://ultiq_admin:PASSWORD@<rds-endpoint>:5432/productivity" }` |
| `ultiq/prod/jwt` | `{ "JWT_SECRET": "<openssl rand -base64 32>" }` |

These get injected into the ECS task as env vars — no plaintext secrets in task definitions or git.

### 6.3 — Backend Container & ECR

**Add a `/health` endpoint** to `routes/mod.rs` (ALB needs one for target group health checks):
```rust
async fn health() -> &'static str { "ok" }
// add `.route("/health", get(health))` to the public router
```

**`backend/Dockerfile`** — multi-stage, slim production image:
```dockerfile
FROM rust:1.77-slim AS builder
WORKDIR /app
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY migrations ./migrations
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/backend /usr/local/bin/backend
EXPOSE 8080
CMD ["backend"]
```

Verify locally:
```bash
docker build -t ultiq-backend ./backend
docker run --rm -p 8080:8080 \
  -e DATABASE_URL=postgres://dev:devpass@host.docker.internal:5432/productivity \
  -e JWT_SECRET=test ultiq-backend
curl http://localhost:8080/health   # → "ok"
```

**Create ECR repo and push the first image:**
```bash
aws ecr create-repository \
  --repository-name ultiq/backend \
  --image-scanning-configuration scanOnPush=true

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI=$ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/ultiq/backend

aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin $ECR_URI

docker tag ultiq-backend:latest $ECR_URI:latest
docker push $ECR_URI:latest
```

### 6.4 — ECS Fargate Cluster & Service

**Cluster:** `ultiq` (Fargate launch type — no EC2 to manage).

**Task definition (`ultiq-backend`):**
- Launch type: Fargate
- CPU / memory: 0.25 vCPU / 0.5 GB (smallest size, ~$8/month at 24/7)
- Network mode: awsvpc
- Container `backend`:
  - Image: `<ECR-URI>:latest` (pinned to a SHA tag in CI)
  - Port mapping: 8080 (TCP)
  - Secrets (injected as env vars):
    - `DATABASE_URL` ← `arn:aws:secretsmanager:...:ultiq/prod/database:DATABASE_URL::`
    - `JWT_SECRET` ← `arn:aws:secretsmanager:...:ultiq/prod/jwt:JWT_SECRET::`
  - Log driver: `awslogs` → group `/ecs/ultiq-backend`, stream prefix `ecs`
- **Task role:** custom role with `secretsmanager:GetSecretValue` on the two secret ARNs
- **Execution role:** AWS-managed `AmazonECSTaskExecutionRolePolicy` plus `secretsmanager:GetSecretValue` for the same secrets

**Service (`backend-service`):**
- Cluster: `ultiq`
- Task definition: latest revision
- Desired count: 1 (cost savings; 2 for HA later)
- Capacity provider: `FARGATE`
- Network: private subnets, security group `ecs-sg`, no public IP
- Load balancer: target group `backend-tg` on container port 8080
- Deployment: rolling, max 200% / min 100% (zero-downtime)
- Health check grace period: 60s (gives migrations time to run on first boot)

### 6.5 — ALB + DNS + HTTPS

**Application Load Balancer (`ultiq-alb`):**
- Internet-facing, in public subnets A + B
- Security group: `alb-sg`
- Listeners:
  - `:80` → redirect to `:443` (HTTP → HTTPS)
  - `:443` → forward to target group `backend-tg`

**Target group (`backend-tg`):**
- Type: IP (Fargate uses IP targets, not instance)
- Protocol: HTTP, port 8080
- Health check path: `/health`, healthy threshold 2, interval 30s
- Deregistration delay: 30s (faster deploys)

**ACM certificate:**
- Region: same as ALB (must match)
- Domain: `api.your-domain.com` (and optionally `*.your-domain.com`)
- Validation: DNS — automatic if your domain is in Route 53

**Route 53:**
- If domain is registered elsewhere: create a hosted zone, then update nameservers at your registrar
- Add alias record: `api.your-domain.com → ALB`

> **No domain yet?** You can test against the ALB's auto-generated DNS, but ACM won't issue a certificate for AWS-owned DNS names — so HTTPS requires a real domain. Cheapest option: register `.click` or `.link` via Route 53 for ~$3/year.

### 6.6 — CloudWatch Monitoring

**Logs:** ECS auto-ships container stdout/stderr to log group `/ecs/ultiq-backend`. Set retention to 14 days (Logs → log group → Actions → Edit retention) — default is "never expire" which gets expensive.

**Built-in metrics** (no setup needed):
- ECS: CPU utilization, memory utilization, running task count
- ALB: request count, target response time, 4xx/5xx rates
- RDS: CPU, DB connections, free storage space

**Alarms** (CloudWatch → Alarms → Create):

| Alarm | Threshold | Action |
|-------|-----------|--------|
| ALB `HTTPCode_Target_5XX_Count` | > 5 in 5 min | SNS email |
| ECS `RunningTaskCount` | < 1 for 2 min | SNS email |
| RDS `CPUUtilization` | > 80% for 10 min | SNS email |
| RDS `FreeStorageSpace` | < 5 GB | SNS email |
| Estimated charges (Billing) | > $50/month | SNS email |

**SNS topic** `ultiq-alerts` with your email subscribed (confirm via the email link AWS sends).

**Dashboard** (`Ultiq Backend`): one widget each for ALB request count, ECS CPU, RDS CPU, ALB 5xx rate. Bookmark it.

### 6.7 — GitHub Actions CI/CD

**Why OIDC, not access keys:** GitHub authenticates to AWS via OpenID Connect — no long-lived AWS credentials stored as GitHub secrets, just an IAM role that trusts GitHub's OIDC issuer.

**One-time AWS setup:**
1. IAM → Identity providers → Add provider:
   - URL: `https://token.actions.githubusercontent.com`
   - Audience: `sts.amazonaws.com`
2. Create IAM role `github-actions-deploy`:
   - Trust policy: trust the GitHub OIDC provider, restricted to your repo via `sub` condition (`repo:olivermsi/ultimate_productivity_app:ref:refs/heads/main`)
   - Permissions: `ecr:*` on the backend repo; `ecs:UpdateService`, `ecs:RegisterTaskDefinition`, `ecs:DescribeServices`, `ecs:DescribeTaskDefinition`; `iam:PassRole` for the task execution + task roles

**`.github/workflows/deploy-backend.yml`:**
```yaml
name: Deploy backend
on:
  push:
    branches: [main]
    paths: ['backend/**']

permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::${{ secrets.AWS_ACCOUNT_ID }}:role/github-actions-deploy
          aws-region: us-east-1

      - uses: aws-actions/amazon-ecr-login@v2
        id: ecr

      - name: Build and push image
        run: |
          IMAGE=${{ steps.ecr.outputs.registry }}/ultiq/backend:${{ github.sha }}
          docker build -t $IMAGE ./backend
          docker push $IMAGE
          echo "IMAGE=$IMAGE" >> $GITHUB_ENV

      - name: Update ECS service
        run: |
          aws ecs update-service \
            --cluster ultiq \
            --service backend-service \
            --force-new-deployment

      - name: Wait for stable deployment
        run: |
          aws ecs wait services-stable \
            --cluster ultiq \
            --services backend-service
```

(Refinement: register a new task definition revision pinned to `$IMAGE` instead of relying on `:latest` — gives you proper rollback via revision history.)

### 6.8 — Update Android App Config

Switch the API base URL by build type:

**`app/build.gradle.kts`:**
```kotlin
android {
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.your-domain.com/\"")
        }
    }
    buildFeatures { buildConfig = true }
}
```

**`RetrofitClient.kt`:**
```kotlin
.baseUrl(BuildConfig.API_BASE_URL)
```

Verify against the production URL with a release build before submitting to the Play Store.

### 6.9 — Cost Estimate & Alternatives

**Baseline (with NAT Gateway, single task, 24/7):**

| Resource | Monthly cost (USD) |
|----------|-------------------|
| ECS Fargate (0.25 vCPU, 0.5 GB) | ~$8 |
| ALB | ~$16 |
| RDS db.t4g.micro | ~$13 |
| NAT Gateway | ~$32 |
| ECR storage + CloudWatch logs + data transfer | ~$3–5 |
| **Total** | **~$72/month** |

**Cheaper variants (in order of savings):**
- **Skip NAT Gateway** with VPC Endpoints (ECR API/DKR, Secrets Manager, CloudWatch Logs, S3 Gateway) → save $32 → **~$40/month** ← *recommended for this project*
- **Replace ECS+ALB with App Runner** (managed container service, includes load balancer + HTTPS) → **~$30/month**, but less standard "AWS production" experience
- **Pause when idle**: `aws ecs update-service --desired-count 0` between coding sessions — Fargate cost drops to zero, ALB and RDS still bill

**AWS Free Tier:** new accounts get 12 months of free RDS db.t3.micro (750h/mo) and 750h/mo of ALB — first year can be ~$25/month cheaper.

### 6.10 — Marketing Landing Site

A public, no-auth landing page introducing Ultiq — what it does, mascot + screenshots, Play Store CTA, terms + support. Goes live alongside the backend so you have something to share the moment you've shipped. The Phase 7 analytics dashboard will later live at the `app.` subdomain on the same project domain; this section locks in the apex.

**Stack:**

| Layer | Tech |
|-------|------|
| Framework | Next.js 14+ (App Router, static export) |
| Styling | TailwindCSS |
| Components | shadcn/ui (Radix + Tailwind primitives) |
| Animations | framer-motion |
| Hosting | AWS S3 + CloudFront + Route 53 + ACM |
| CI/CD | GitHub Actions → S3 sync + CloudFront invalidation |

**Why Next.js (not Leptos like the dashboard):** Marketing-page polish lives or dies on the React ecosystem — shadcn/ui + framer-motion + Tailwind together produce the "modern fancy" landing aesthetic with reasonable effort. Leptos's component/animation libraries aren't there yet for content sites. Phase 7's analytics dashboard stays Rust/Leptos because dashboards reward Rust's strengths (logic, charts, data) and ecosystem gaps matter less.

**Why static export (not Vercel SSR):** No server-side data on this page; every section is content. `next build` with `output: 'export'` produces a `out/` directory of plain HTML/CSS/JS that drops into S3. Pure AWS deployment, no Vercel coupling.

**Repo location:** `web-landing/` at the repo root, alongside `android/` and `backend/`. The Phase 7 Leptos dashboard goes in `web-dashboard/`.

#### 6.10.1 — Page Structure

One scrolling page, top to bottom:

| Section | Content |
|---------|---------|
| Nav | Logo + name, "Get the app" button (anchors to Play Store CTA) |
| Hero | Headline ("Your daily productivity companion"), 2-line subhead, mascot illustration, primary CTA |
| What it does | 3–4 feature cards with icons (Sleep tracking, Focus sessions, Daily checklist, Insights) |
| Screenshots | 3–4 phone mockups: dashboard, sleep, focus, calendar — frames generated with [Mockuphone](https://mockuphone.com/) or similar |
| The mascot moment | One full-bleed section featuring the sleeping book with brand copy ("Sleep deeply, focus clearly, rest, repeat.") |
| Play Store CTA | "Get it on Google Play" badge linking to the Play listing — gracefully shows "Coming soon" before the listing is live (build-time env var) |
| Footer | Terms link, support email, copyright, GitHub link |

**Brand assets to reuse from the Android app:**
- Mascot vector — export `ic_launcher_foreground.xml` to SVG for the web
- Brand palette: indigo `#2A1B6E`, red `#D9474C`, yellow `#FFC83D`, cream `#FFF4E6`, light blue `#A8C5E8`
- Voice: warm, companion-style — mirror the tone in `WarmCopy.kt`

#### 6.10.2 — Project Setup

```
web-landing/
├── app/
│   ├── layout.tsx          # Tailwind globals, metadata
│   ├── page.tsx            # the single landing page
│   └── terms/page.tsx      # mirror the in-app T&C, served at /terms
├── components/
│   ├── Nav.tsx
│   ├── Hero.tsx
│   ├── Features.tsx
│   ├── Screenshots.tsx
│   ├── MascotSection.tsx
│   └── Footer.tsx
├── public/
│   ├── mascot.svg          # exported from the Android vector
│   ├── icon-512.png        # favicon / open graph image
│   └── screenshots/        # phone mockup PNGs
├── tailwind.config.ts
├── next.config.mjs         # output: 'export' for static
└── package.json
```

**Setup commands:**
```bash
npx create-next-app@latest web-landing --typescript --tailwind --eslint --app
cd web-landing
npx shadcn-ui@latest init
npm install framer-motion lucide-react
```

**`next.config.mjs`:**
```js
const nextConfig = {
  output: 'export',
  images: { unoptimized: true },  // S3 doesn't run the Next image server
};
export default nextConfig;
```

#### 6.10.3 — AWS Hosting

The apex domain (`your-domain.com`) hosts the landing site. The dashboard subdomain `app.your-domain.com` is reserved for Phase 7. The API subdomain `api.your-domain.com` is already pointed at the ALB from 6.5.

**S3 bucket:** `ultiq-landing-web`
- Region: same as the rest of the stack
- Block public access: ON (CloudFront fetches via OAC, not direct S3)
- Versioning: optional, useful for quick rollbacks

**CloudFront distribution:**
- Origin: the S3 bucket via Origin Access Control (OAC)
- Default root object: `index.html`
- Custom error responses: `403` and `404` → return `/index.html` with status `200` (matches the Phase 7 dashboard config so future shared edge logic stays consistent)
- Alternate domain: `your-domain.com` (and `www.your-domain.com` redirecting to apex)
- TLS cert: ACM in `us-east-1` (CloudFront requirement)
- Compression: gzip + brotli on
- Default cache TTL: 1 day; `index.html` cache 0 so deploys go live immediately

**Route 53:**
- A/ALIAS record `your-domain.com → CloudFront distribution`
- A/ALIAS record `www.your-domain.com → CloudFront distribution` (or 301 to apex via an S3 redirect bucket)

#### 6.10.4 — GitHub Actions Deploy

`.github/workflows/landing-deploy.yml` (path-filtered to `web-landing/**`):

```yaml
name: Deploy Landing

on:
  push:
    branches: [main]
    paths: ['web-landing/**']

jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: web-landing/package-lock.json
      - run: npm ci
        working-directory: web-landing
      - run: npm run build
        working-directory: web-landing
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::<ACCOUNT_ID>:role/github-actions-deploy
          aws-region: us-east-1
      - run: |
          aws s3 sync out/ s3://ultiq-landing-web/ \
            --delete \
            --cache-control "max-age=86400"
        working-directory: web-landing
      - run: |
          aws s3 cp out/index.html s3://ultiq-landing-web/index.html \
            --cache-control "max-age=0,no-cache" \
            --content-type "text/html"
        working-directory: web-landing
      - run: aws cloudfront create-invalidation --distribution-id <DIST_ID> --paths "/*"
```

The OIDC role from 6.7 needs `s3:PutObject` and `s3:DeleteObject` on `ultiq-landing-web/*`, plus `cloudfront:CreateInvalidation` on the new distribution.

#### 6.10.5 — Definition of Done

- `your-domain.com` resolves to the landing page over HTTPS
- Lighthouse score ≥ 90 on Performance, Accessibility, Best Practices
- Mascot + screenshots + brand voice match the in-app feel
- "Get it on Google Play" CTA points to the Play Store listing — or shows a "Coming soon" overlay until 6.11 is live
- `/terms` route serves a static version of the in-app T&C
- Push to `main` updates the live site within ~2 minutes

---

### 6.11 — Google Play Store Release (Production)

> **Order:** do this AFTER 6.14c (Internal Testing) — promote a validated build, don't push straight to Production. The Play Console account itself is created during 6.14c; this section just covers the Production-track submission.

**Promote AAB from Internal Testing → Production:**
1. In Play Console → app → **Production** → **Create new release**
2. Choose "Use library" → pick the AAB already uploaded to Internal Testing in 6.14c (no need to rebuild)
3. Or upload a fresh `bundleRelease` AAB if iterating

**Store listing** (one-time):
- App name: `Ultiq`
- Short description (80 chars)
- Full description
- Screenshots: at least 2 phone screenshots per screen (dashboard, sleep, sessions, calendar) — taken during 6.14c soak-testing
- App icon: 512×512 PNG (export from `web-landing/public/mascot.svg` or `app/icon.svg`)
- Feature graphic: 1024×500

**Required compliance:**
- Content rating questionnaire
- Privacy policy URL (required — point at `https://ultiqapp.com/terms` or a dedicated `/privacy` page on the landing site)
- Pricing: Free
- Data Safety form: declare what user data is collected (email + auth credentials + sleep / focus / calendar / checklist records — all server-stored, not shared)

**Submit for review:** Google Production-track review takes ~3–7 days. Approval triggers global Play Store availability.

### 6.12 — Optional: Deeper AWS Integration (Future)

Not part of the v1 build — but worth knowing where you'd grow into AWS later:

- **Cognito** — replace custom JWT with managed user pools (gets you OAuth/SSO/MFA for free). Substantial Phase 0 rewrite, only worth it if you outgrow custom JWT.
- **SNS + FCM** — server-driven push notifications (currently all reminders are local AlarmManager in Phase 5.1). Adds backend logic to trigger pushes from sync events.
- **S3** — user-generated file uploads (e.g. sleep notes attachments, exported reports), and as a backup target for `pg_dump` snapshots.
- **CloudFront** — CDN for static content (privacy policy page, marketing site, exported reports).
- **EventBridge + Lambda** — scheduled jobs (e.g. nightly streak recalculation, weekly report generation) without keeping a worker process running.
- **AWS WAF** on the ALB — block common attack patterns once the app is public.
- **AWS Backup** — automated cross-region snapshots of RDS + ECR for disaster recovery.

### 6.13 — Password Recovery (Forgot Password)

Deferred to this phase because it needs production-grade email that doesn't exist in local dev. **In-app change-password ships earlier** (Settings → Change password); this section is the email-driven *reset* flow for users who can't log in.

**Plan when AWS is in place:**

- **Email transport: AWS SES.** ~$0.10 per 1,000 emails, deliverability is solid. Verify a sending domain via Route 53 records (already used for the ALB DNS), then submit the SES production-access request to leave the sandbox so you can send to any address.
- **Schema migration:** new `password_reset_tokens` table with `id`, `user_id`, `token_hash` (sha256 of the raw token — never store the raw value), `expires_at` (1h after issue), `used_at`. Index on `(user_id, used_at)` so we can invalidate prior unused tokens when a new one is issued.
- **Backend endpoints:**
  - `POST /auth/password/forgot` — body `{ email }`. Always returns 200 even if the email isn't registered (avoids account enumeration). Generates a UUIDv4 token, stores its sha256, sends a templated email through SES with a deep link.
  - `POST /auth/password/reset` — body `{ token, new_password }`. Hashes the token, looks it up, checks `expires_at` and `used_at`, validates the new password against the same strength rules used for register/change, updates `users.password_hash` (Argon2), marks token used.
- **Secrets Manager additions:** `ultiq/prod/email` holding `{ FROM_ADDRESS, REPLY_TO }`. SES IAM permission attached to the ECS task role.
- **Mobile UI:**
  - Login screen → "Forgot password?" link → email-entry screen → confirmation toast ("If that email is registered, a reset link is on the way").
  - Deep link: register `ultiq://reset-password?token=...` in the manifest. Tapping the email link opens a new password screen with the same live strength feedback used in register.
- **Strength rules** are shared by `register`, `POST /auth/password` (in-app change), and `POST /auth/password/reset`. Encode once on the backend (a `validate_password_strength` helper) and once on the client (`PasswordStrength.kt`) so the user gets immediate feedback, but the server is the source of truth.

### 6.14 — Direct APK Distribution + Play Internal Testing

Two distribution channels alongside the Play Store production track in 6.11. Decided 2026-05-01 — we want both, not either/or:

- **Direct APK** unblocks friends/early testers immediately (no Google approval needed) and gives a fallback for users who avoid Google's ecosystem.
- **Play Internal Testing** auto-updates installed builds and reviews in hours instead of the 3–7 days that Production review takes — perfect for iterating on near-final builds.

#### 6.14.1 — Signing keystore (prerequisite for both, and for 6.11)

A single keystore is used for every release build forever. **Losing it means you cannot update the app on Play Store with the same package name** (you'd have to publish a new app from scratch, lose all existing users + reviews). Treat it like a credit card.

Generate it inside `android/` (gitignored):

```bash
cd android
keytool -genkey -v -keystore ultiq-release.jks -alias ultiq \
  -keyalg RSA -keysize 2048 -validity 10000
```

Two passwords (keystore + key alias) — save in a password manager. Plus a copy of the `.jks` file in cloud backup (encrypted upload to S3, or Google Drive with 2FA on). The keystore file lives in `android/ultiq-release.jks` (gitignored); never commit it.

#### 6.14.2 — Gradle release signing

`android/app/build.gradle.kts` loads a gitignored `android/keystore.properties` and wires it as the release signing config. Skipped silently when the file is absent (CI / fresh checkouts), so `assembleDebug` still works without it:

```kotlin
import java.util.Properties

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            keystoreProps.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else null
            // existing release config
        }
    }
}
```

`android/keystore.properties` (gitignored — copy from `keystore.properties.example`):

```
storeFile=ultiq-release.jks
storePassword=<your-keystore-password>
keyAlias=ultiq
keyPassword=<your-key-password>
```

Repo-root `.gitignore` excludes `android/ultiq-release.jks` and `android/keystore.properties`.

#### 6.14.3 — Direct APK on landing site

`./gradlew assembleRelease` produces `android/app/build/outputs/apk/release/app-release.apk`. Copy to `web-landing/public/ultiq-latest.apk`; Next.js's static-export pipeline picks it up automatically and CloudFront serves it at `https://ultiqapp.com/ultiq-latest.apk` over HTTPS.

Landing-page integration:
- A **Download APK** button next to (or replacing) the "Coming soon" Play CTA
- A small expandable "Sideload installation" instructions block — first-time installers get an Android prompt to allow installs from this source; explain why it's needed
- Optionally show the version-string from `versionName` so users can tell which build is live

For ongoing releases: bump `versionCode` + `versionName` in `app/build.gradle.kts`, run `./gradlew assembleRelease`, copy the new APK to `web-landing/public/ultiq-latest.apk`, commit + push. The existing `deploy-landing.yml` CI workflow already deploys.

#### 6.14.4 — Play Console signup + Internal Testing track

> **Order:** this section is the FIRST Play Console step. The $25 signup is done here; Production (6.11) reuses the same account afterward.

1. Sign up at https://play.google.com/console — $25 USD one-time fee
2. Create new app:
   - App name: `Ultiq`
   - Default language: English (Australia)
   - App or game: App
   - Free or paid: Free
3. Build a signed AAB: `./gradlew bundleRelease` (Play wants `.aab`, not `.apk`)
4. Play Console → app → **Testing** → **Internal testing** → **Create new release**
5. Upload the `.aab` from `android/app/build/outputs/bundle/release/app-release.aab`
6. Add release notes
7. Invite testers by email, or distribute a public opt-in URL (limit 100 testers)
8. Submit — Google reviews internal builds in hours, not days

Internal testing builds auto-update on testers' phones via the normal Play Store flow. Same keystore + same package name as Production; only the track differs. Validate here for a few days, fix any issues, then promote the same AAB to Production via 6.11.

#### 6.14.5 — Definition of done

- `./gradlew assembleRelease` produces a signed APK (verify: `apksigner verify --verbose path/to/apk`)
- `https://ultiqapp.com/ultiq.<version>.apk` returns 200 with `Content-Type: application/vnd.android.package-archive` (e.g. `ultiq.1.0.apk`)
- Landing page has a working **Download APK** button (versioned URL) alongside the Play CTA
- Play Console has an Internal Testing release in review, with at least one invited tester

#### 6.14.6 — Risks

- **Keystore loss is fatal.** Back it up immediately, in at least two places.
- **Channel divergence:** if direct APK and Play track end up at different `versionCode`s, users get confused. Bump both with every release.
- **"Install from unknown sources"** prompt scares some users. The landing copy should briefly say *"Android will ask you to allow installs from this source — that's a normal one-time prompt for any APK outside the Play Store."*

---

## Phase 7: Web Analytics Dashboard (Weeks 25–29)

A Rust/WASM web app for analytics and visualization, served at `app.your-domain.com` while the marketing landing site from Phase 6 keeps the apex `your-domain.com`. Two distinct apps, one project domain. Builds on the same Rust/Axum backend the Android app uses — purely a new frontend.

### Stack

| Layer             | Tech                                                |
| ----------------- | --------------------------------------------------- |
| Framework         | Leptos (Rust + WASM, fine-grained reactivity)       |
| Build tool        | Trunk                                               |
| Styling           | TailwindCSS via PostCSS                             |
| Charts (primary)  | leptos-chartistry (pure-Rust SVG)                   |
| Charts (advanced) | charming (Rust wrapper for Apache ECharts JS)       |
| HTTP              | gloo-net + Leptos `Resource`                        |
| State             | Leptos signals (built-in, no extra lib)             |
| Hosting           | AWS S3 + CloudFront + Route 53 + ACM                |
| CI/CD             | GitHub Actions → S3 sync + CloudFront invalidation  |

**Why Leptos over Yew/Dioxus:** fine-grained reactivity (no virtual DOM), best runtime performance of the Rust web frameworks, fastest-growing ecosystem, ergonomic signal-based API.

**Why CSR (client-side rendering), not SSR:** the dashboard is auth-gated, so SEO doesn't matter. A static SPA on S3+CloudFront is simpler and cheaper than running a Leptos SSR server (which would mean another ECS task).

**Where Rust ends:** TailwindCSS is JS tooling (PostCSS) and `charming` wraps ECharts JS. Everything else — components, state, API client, routing, charts where SVG suffices — is pure Rust.

### Architecture

```
Browser → CloudFront → S3 (static SPA: index.html, *.wasm, *.js, *.css)
                          │
                          └─→ JS shim loads .wasm → Leptos boots
                              │
                              └─→ fetch(api.your-domain.com) → ALB → ECS Fargate → RDS
```

### 7.1 — Project Scaffold

Add a `web-dashboard/` Cargo project to the workspace (sibling of `android/`, `backend/`, and the Phase 6 `web-landing/`):

```
web-dashboard/
├── Cargo.toml
├── Trunk.toml
├── index.html              # Trunk entry: <link data-trunk rel="rust" /> + tailwind
├── tailwind.config.js
├── package.json            # tailwindcss only
├── src/
│   ├── main.rs             # mount_to_body
│   ├── app.rs              # <App>: router + AuthProvider + layout
│   ├── api/
│   │   ├── mod.rs
│   │   ├── client.rs       # base URL, JWT header, ApiError
│   │   ├── auth.rs
│   │   ├── sleep.rs
│   │   ├── sessions.rs
│   │   └── calendar.rs
│   ├── auth.rs             # AuthContext (LocalStorage-backed JWT)
│   ├── routes.rs           # leptos_router route definitions
│   ├── components/         # Card, Button, Spinner, DateRangePicker, KpiTile, ...
│   ├── charts/             # LineChart, BarChart, Heatmap, Scatter, Donut, ...
│   ├── stats.rs            # client-side correlation / aggregation helpers
│   └── pages/
│       ├── login.rs
│       ├── overview.rs
│       ├── sleep.rs
│       ├── focus.rs
│       ├── calendar.rs
│       ├── correlations.rs
│       └── reports.rs
└── style/
    └── input.css           # @tailwind base/components/utilities
```

**Cargo dependencies:**
- `leptos` (with `csr` feature)
- `leptos_router`, `leptos_meta`
- `gloo-net` (http feature) — fetch wrapper
- `gloo-storage` — JWT in LocalStorage
- `serde` + `serde_json`
- `chrono` (`wasmbind` feature for browser time)
- `web-sys`, `wasm-bindgen`, `js-sys`
- `console_error_panic_hook` — readable panics in DevTools
- `leptos-chartistry` — pure-Rust SVG charts
- `charming` — only where ECharts beats SVG (heatmaps, sankey, complex polar)

### 7.2 — Auth & API Client

**`api/client.rs`:**
- Base URL from `env!("API_BASE_URL")` set at build time by Trunk + GitHub Actions
- One `request<T>(method, path, body)` helper:
  - Reads JWT from `gloo_storage::LocalStorage`
  - Attaches `Authorization: Bearer <token>`
  - JSON via `serde_json`
  - Returns `Result<T, ApiError>` — typed errors per endpoint
  - On 401 → clear token, trigger reactive redirect to `/login`

**`auth.rs`:**
- `AuthContext { user: RwSignal<Option<User>>, login(...), logout() }`
- Provided at app root via `provide_context`
- `init()` on boot: read token, call `/auth/me`, hydrate `user` (or stay logged out)

**Route guard:** `<RequireAuth>` HOC redirecting to `/login` when `user` is `None`.

### 7.3 — Layout, Routing & Theme

Routes (`leptos_router`):

| Path             | Page                |
|------------------|---------------------|
| `/login`         | `LoginPage`         |
| `/`              | `OverviewPage`      |
| `/sleep`         | `SleepAnalytics`    |
| `/focus`         | `FocusAnalytics`    |
| `/calendar`      | `CalendarAnalytics` |
| `/correlations`  | `CorrelationsPage`  |
| `/reports`       | `ReportsPage`       |

App shell:
- **Sidebar** (left): logo, nav links, user menu at bottom — collapsible on mobile
- **Topbar**: page title + global date range picker (drives all charts on the page)
- **Main**: page content

Dark mode by default; palette matches the Android app (calm blue-purple primary).

### 7.4 — Overview Dashboard

The "at a glance" landing page after login. Single scrollable column.

**KPI row** (4 cards):
- Avg sleep last 7d (with delta vs prior 7d, ↑/↓)
- Focus hours last 7d
- Current focus streak (flame icon)
- Avg phone pickups/day

**Sleep over time** (line + area, leptos-chartistry):
- X = last 30 days, Y = duration in hours, target line overlay, fill colored by quality

**Focus calendar heatmap** (charming/ECharts):
- GitHub-style year grid, cells colored by total focus minutes that day
- Click a cell → navigate to `/focus?date=...`

**Upcoming events** (next 5 from calendar — same data as Android dashboard).

**Quick stats** (text-only card): "Best sleep this month: 8h 12m on Apr 14", "Top focus tag this week: LeetCode (4h 30m)".

### 7.5 — Sleep Analytics

All charts driven by the global date range picker.

1. **Duration over time** — line + area, daily duration vs target
2. **Quality distribution** — histogram of nights by quality 1–5
3. **Bedtime drift** — scatter, X=date, Y=actual bedtime; reveals if you're trending earlier/later
4. **Wake time consistency** — same scatter pattern for wake time
5. **Sleep debt accumulation** — area, running total of debt vs target
6. **Phone pickups during sleep** — per-night bar chart
7. **Sleep timing heatmap** — hour-of-day × day-of-week, frequency-colored (charming)

Filters: date range, quality threshold.

**Insights panel** (computed client-side from raw records):
- "You hit your bedtime target X of Y nights this month"
- "Mondays average X hours — N% less than weekends"
- "Longest streak at quality ≥ 4: N nights"

### 7.6 — Focus Analytics

1. **Daily focus minutes** — bar chart, completed vs cancelled stacked
2. **Focus by tag** — horizontal stacked bar, sorted by total minutes
3. **Pomodoros by hour-of-day** — heatmap (charming) — when you're most productive
4. **Streak timeline** — line of streak length over time
5. **Distractions per session** — scatter / histogram of phone pickups per session
6. **Tag breakdown table** — sortable: `tag | total min | sessions | avg session length | avg pickups`
7. **Cumulative focus hours** — area chart

Filters: date range, tag, completed-only toggle.

### 7.7 — Calendar Analytics

1. **Time by category** — donut (Study / Project / Exercise / Personal / Other)
2. **Events per day** — bar chart
3. **Category trend over time** — stacked area
4. **Priority distribution** — donut
5. **Day-of-week pattern** — heatmap by category × day

### 7.8 — Cross-Feature Correlations

The view that earns the "analytics" label. All correlations computed client-side over fetched records (`stats.rs` helper).

1. **Previous night's sleep vs next day's focus** — scatter + linear regression line, Pearson `r` displayed
2. **Phone pickups vs focus minutes** — scatter (expected inverse trend)
3. **Sleep quality vs focus streak continuation** — overlaid line chart
4. **Calendar load vs focus minutes** — scatter
5. **Combined timeline** — multi-series line: sleep, focus, calendar load on shared time axis

Each chart shows a one-sentence interpretation: "r=0.62, p<0.05 — significant positive correlation" or "no significant correlation".

### 7.9 — Reports Page

- Period selector (week ending date / month)
- Click "Generate" → render printable HTML report:
  - Headline stats from sleep/focus/calendar
  - Inline SVG charts (smaller versions of the dashboard charts)
  - Achievement progress (from Phase 5.3)
- "Print" button → browser-native PDF export, no PDF library needed

Email delivery deferred (would need backend SES integration — see Phase 6.11).

### 7.10 — Backend Changes

Minimal — same JSON endpoints, two additions:

**1. CORS layer for the web origin** (`backend/src/main.rs`):
```rust
use tower_http::cors::CorsLayer;
use http::{Method, header::{AUTHORIZATION, CONTENT_TYPE}, HeaderValue};

let cors = CorsLayer::new()
    .allow_origin("https://app.your-domain.com".parse::<HeaderValue>().unwrap())
    .allow_methods([Method::GET, Method::POST, Method::PUT, Method::DELETE])
    .allow_headers([AUTHORIZATION, CONTENT_TYPE]);

let app = Router::new().merge(routes).layer(cors);
```

**2. (Optional) Aggregation endpoints** — only add if client-side aggregation gets slow:
- `GET /analytics/sleep-trends?days=N`
- `GET /analytics/focus-by-tag?range=...`
- `GET /analytics/correlations?range=...`

Default to client-side aggregation from raw `/sleep`, `/sessions`, `/calendar` responses. Add server endpoints only when the dataset grows enough to need it.

### 7.11 — AWS Hosting (S3 + CloudFront)

**S3 bucket `ultiq-web`:**
- Block all public access: ON (CloudFront uses Origin Access Control, not public URLs)
- Versioning: ON (cheap rollback to a prior deploy)

**CloudFront distribution:**
- Origin: the S3 bucket via OAC (Origin Access Control)
- Default root object: `index.html`
- **Custom error responses**: `403` and `404` both return `index.html` with status `200` — required so client-side routing handles direct URL hits like `app.your-domain.com/sleep`
- Cache behaviors:
  - `index.html`: `Cache-Control: public, max-age=300` (5 min — fast deploy visibility)
  - `*.wasm`, `*.js`, `*.css` (Trunk emits content-hashed filenames): `Cache-Control: public, max-age=31536000, immutable`
- Compression: gzip + brotli enabled
- HTTPS-only: redirect HTTP → HTTPS
- Alternate domain: `app.your-domain.com`
- ACM certificate: **must be in `us-east-1`** (CloudFront-wide requirement, regardless of bucket region)

**Route 53:**
- Alias record `app.your-domain.com → CloudFront distribution`

### 7.12 — GitHub Actions CI/CD

**`.github/workflows/deploy-web.yml`:**

```yaml
name: Deploy web
on:
  push:
    branches: [main]
    paths: ['web-dashboard/**']

permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: wasm32-unknown-unknown

      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Install Trunk
        run: cargo install --locked trunk

      - name: Install Tailwind
        working-directory: web
        run: npm install

      - name: Build (release)
        working-directory: web
        env:
          API_BASE_URL: https://api.your-domain.com
        run: trunk build --release

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::${{ secrets.AWS_ACCOUNT_ID }}:role/github-actions-deploy-web
          aws-region: us-east-1

      - name: Sync hashed assets (long cache)
        working-directory: web
        run: |
          aws s3 sync dist/ s3://ultiq-web/ \
            --delete \
            --cache-control "public, max-age=31536000, immutable" \
            --exclude "index.html"

      - name: Sync index.html (short cache)
        working-directory: web
        run: |
          aws s3 cp dist/index.html s3://ultiq-web/index.html \
            --cache-control "public, max-age=300"

      - name: Invalidate CloudFront
        run: |
          aws cloudfront create-invalidation \
            --distribution-id ${{ secrets.CLOUDFRONT_DISTRIBUTION_ID }} \
            --paths "/" "/index.html"
```

IAM role `github-actions-deploy-web` permissions: `s3:PutObject` / `DeleteObject` / `ListBucket` on the bucket; `cloudfront:CreateInvalidation` on the distribution. Trust policy restricted to your repo via the same OIDC pattern as Phase 6.7.

### 7.13 — Cost Estimate

Adds ~$1–2/month to Phase 6:

| Resource | Monthly cost (USD) |
|----------|-------------------|
| S3 (~50–100 MB site) | < $0.01 |
| CloudFront | ~$1 (1 TB free tier first 12 months, then $0.085/GB) |
| Route 53 hosted zone | already exists from Phase 6 |
| ACM cert | free |

**Phase 6 + 7 combined:** ~$42/month with VPC Endpoints, ~$74/month with NAT Gateway.

### 7.14 — Resume Value

This phase converts a phone-only side project into a full-stack Rust portfolio piece:
- **Backend**: Rust + Axum + SQLx + Postgres
- **Mobile**: Kotlin + Compose + Room + Retrofit
- **Web**: Rust + Leptos + WASM
- **Infra**: AWS (ECS Fargate, RDS, ALB, S3, CloudFront, Route 53, Secrets Manager, CloudWatch)
- **CI/CD**: GitHub Actions with OIDC

Two clients, one backend, three deployment targets — a strong demonstration of full-stack systems thinking.

---

## Phase 8: AI Integration (Weeks 30–32)

Turns the data this app already collects (sleep, focus, pickups, checklist completion, calendar) into insights, summaries, and natural-language interactions powered by Claude. Backend-mediated so API keys never leave the server.

### 8.1 — Stack

| Layer | Tech | Why |
|---|---|---|
| Model | Anthropic Claude (Sonnet for narratives, Haiku for cheap classification, Opus for deep reasoning) | Long-context handling for "summarize my month" workflows; same vendor as the dev tooling |
| Transport | Anthropic Messages API over `reqwest` (no mature Rust SDK yet) | Direct HTTP gives full control of cache breakpoints, streaming, tool definitions |
| Where calls live | Backend (Rust/Axum) — never the mobile app | Keeps API key server-side; pre-fetch user data from Postgres; central rate limiting and caching |
| Persistence | New `ai_insights` and `ai_conversations` tables | Cheaper to read cached output than regenerate; users can revisit history |
| Cost control | Prompt caching (5-min TTL) + per-user daily token budget | Daily insight ~$0.005 with caching vs ~$0.025 without |
| Secret | `ultiq/prod/ai` in Secrets Manager → `ANTHROPIC_API_KEY` env on ECS | Same pattern as DB and JWT secrets |

### 8.2 — Architecture

```
Mobile/Web → /ai/* endpoint → Rust handler
                                  │
                  ┌───────────────┴───────────────┐
                  ▼                               ▼
         Postgres (read user data)       Anthropic Messages API
                                          (system prompt cached)
                  │                               │
                  └───────────────┬───────────────┘
                                  ▼
                       ai_insights / ai_conversations
                                  ▼
                       Response to client (SSE stream for chat)
```

### 8.3 — AI Service Module (Foundation)

- New `src/services/ai.rs` typed wrapper: `messages(model, system, msgs, tools, cache_breakpoints) → Response`.
- Token-bucket rate limiter per user (default 30 requests/day, configurable per feature).
- Sampled logging (1-in-10) of prompts/responses for debugging — truncated, never full PII.
- Schema: `ai_quota(user_id, day, tokens_used)` and the persistence tables below.

```sql
CREATE TABLE ai_insights (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,                -- 'weekly' | 'anomaly' | 'session_debrief'
  content TEXT NOT NULL,
  source_data JSONB,                 -- aggregated stats sent to the model
  generated_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ              -- e.g. 24h after generation
);
CREATE INDEX ON ai_insights (user_id, kind, generated_at DESC);

CREATE TABLE ai_conversations (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  title TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE ai_messages (
  id UUID PRIMARY KEY,
  conversation_id UUID REFERENCES ai_conversations(id) ON DELETE CASCADE,
  role TEXT NOT NULL,                -- 'user' | 'assistant'
  content TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 8.4 — Weekly Insight (highest-value feature, build first)

- `POST /ai/weekly-insight` — cached 24h:
  1. Pull last 7 days of: sleep records, focus sessions, pickup counts, checklist completion rate, calendar density.
  2. Render a structured "data card" (Markdown table — Claude reads these well).
  3. System prompt: *"You are a productivity coach. Given this user's week, write a 3-paragraph summary: what went well, what to adjust, one small experiment for next week. Reference specific numbers from the data card; do not invent figures."*
  4. Validate any numerals in the response against source data programmatically before returning (anti-hallucination).
  5. Persist to `ai_insights`, return to client.
- Mobile UI: new "This week" card on the Dashboard showing the AI-generated text with a manual refresh (capped to 1/day per user).

### 8.5 — Natural-Language Entry (uses tool calling)

- `POST /ai/parse-event` body `{ text: "remind me to call mum tomorrow 6pm" }`:
  1. Define a tool: `create_calendar_event(title, start_time, end_time, category)`.
  2. Send text + tool spec to Claude.
  3. Claude returns a tool call; backend validates args, returns parsed event for user confirmation.
  4. On confirm, route through existing `/calendar` endpoint (no AI bypass of normal validation).
- Mobile UI: floating "+ AI" button on Calendar/Checklist screens. Tap → text input → preview card → confirm.
- Why it matters: typing "study for exam Tuesday 9–11" is faster than tapping through a form. Same pattern for checklist items.

### 8.6 — Coach Chat (conversational)

- `POST /ai/chat/start` → creates a conversation, returns ID.
- `POST /ai/chat/{id}/message` → streams Claude's reply via SSE (token-by-token).
- System prompt assembles: user profile snippet + last 30d aggregated stats + truncated message history. The profile + stats portion has a cache breakpoint so it costs near-zero on follow-up turns.
- Mobile UI: "Coach" entry in Settings → opens a chat thread screen with streaming markdown rendering.
- Use case: user asks *"Why has my focus dropped?"* and Claude has the data to answer specifically.

### 8.7 — Session Debriefs

- After completing a focus session, a 1-line optional prompt: "What did you work on?"
- Stored alongside the session; weekly insight uses these as additional context.
- Bonus: AI tags each debrief (e.g. "deep work" / "meetings" / "admin") via Haiku — passive category tracking without manual tagging.

### 8.8 — Anomaly Detection (background job)

- Daily scheduled task (EventBridge → Lambda invoking `/ai/anomaly-check`, or in-process tokio cron):
  1. For each active user, fetch last 14 days.
  2. Ask Claude (Haiku — cheap): *"Anything anomalous? Return JSON `{ alert: bool, reason: string }`."*
  3. If alert → push notification via FCM (already wired in Phase 5.1).
- Catches: 5+ nights of <6h sleep, focus minutes plummeting, 30+ pickups during a sleep session.

### 8.9 — Privacy & UX

- Master Settings switch: **"Enable AI features"** — defaults **off**. When toggled on, show a "Here's what gets sent" disclosure (last 30d aggregated stats; no raw notes unless separately enabled).
- Per-feature toggles (insights / chat / parsing / anomaly) so users opt into pieces.
- Debug "View last AI request" so curious users can audit.
- Token budget meter visible to user ("12 of 30 daily AI requests used").
- Server-side `validate_password_strength`-style validators for any data Claude writes back (no trust in raw model output — always parse + validate).

### 8.10 — Cost Projection

| Feature | tokens/call (cached) | $/call | $/user/month at 100 users |
|---|---|---|---|
| Weekly insight (1×/week) | 5K in + 500 out | $0.005 | $0.02 |
| NL parse (5×/day) | 1K in + 100 out | $0.001 | $0.15 |
| Chat (10 msg/week, 5K context) | 5K in + 1K out | $0.020 | $0.80 |
| Anomaly detection (Haiku, daily) | 3K in + 50 out | $0.001 | $0.03 |

Rough total: **~$1 per active user/month** with caching. Absorbable; or expose as a Pro-tier later.

### 8.11 — Risks

- **Hallucination on numbers** — Claude sometimes invents stats. Mitigation: instruct prompt to only cite numbers from the data card; programmatically validate numerals before returning.
- **Token cost runaway** — strict per-user daily caps + prompt caching are non-negotiable. Alarm at 10× expected daily spend in CloudWatch.
- **Cold start on chat** — first message of a session has no cache hit. Acceptable; subsequent messages are fast/cheap.
- **Privacy regulatory creep** — if you ever target EU, GDPR adds requirements (data residency, right-to-erasure of AI logs). Out of scope for v1, but flag it in the Settings disclosure.

---

## Phase 9: Sleep Monitoring & Wearables (Weeks 33–36)

Moves sleep tracking from "phone unlock counts + self-reported quality" to objective physiological data (heart rate, sleep stages, HRV) via wearables. **Additive, not replacement** — users without a wearable keep the existing manual flow exactly as it is today.

### 9.1 — Backwards Compatibility (Read This First)

The current sleep flow stays fully functional for users without a wearable:
- Manual **Start Sleep / End Sleep** session — unchanged
- Phone pickup tracking via `ACTION_USER_PRESENT` — unchanged
- Self-reported quality (1–5) — unchanged
- Manual log past sleep dialog — unchanged
- Per-session target wake time (Phase 5 work) — unchanged

What Phase 9 adds is **richer data on top** when a user connects a wearable. The same `sleep_records` row is unchanged; new linked rows (`sleep_stages`, `heart_rate_samples`, `hrv_samples`) are populated only when data exists. The UI conditionally renders the hypnogram and HR cards if those linked tables have rows for that record — otherwise it shows today's view unchanged.

### 9.2 — Provider Selection

| Provider | Auth | Data quality | Build effort | Phase priority |
|---|---|---|---|---|
| **Wear OS** (Pixel Watch, Galaxy Watch) | App-level (Health Services API) | Very good | Medium — companion app required | Build second |
| **Fitbit** | OAuth 2.0 (web) | Very good | Low — pure REST API | **Build first** (no extra app to write; many users already have one) |
| **Oura Ring** | OAuth 2.0 | Excellent | Low — REST API | Optional, after Wear OS |
| **Garmin** | OAuth 2.0 (Health API has approval gates) | Good | Medium | Lower priority |
| **Whoop** | OAuth 2.0 | Excellent (HRV-focused) | Low | Optional |
| **Phone-only** (accelerometer + mic) | None | Mediocre | High to do well | Skip — fallback today is the existing pickup-based UX |

Strategy: define your own internal sleep schema, write per-provider adapters. Trait-based abstraction so adding a new provider = adding a new file.

### 9.3 — Stack Additions

| Layer | Choice |
|---|---|
| Wear OS module | Compose for Wear, Health Services API, DataLayer (phone↔watch comm) |
| Wear→Phone sync | DataLayer Client API (built-in, no extra setup) |
| Web OAuth | New `/integrations/{provider}/connect` + `/callback` endpoints |
| Token storage | Per-user encrypted via `pgcrypto`; encryption key in Secrets Manager |
| HR sample storage | Vanilla Postgres with monthly partitions (start), revisit with TimescaleDB if volume grows |
| Background pulls | EventBridge → Lambda invokes `/integrations/{provider}/sync` daily per user |

### 9.4 — Schema Additions

```sql
-- One per connected device per user
CREATE TABLE wearable_connections (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,            -- 'wear_os' | 'fitbit' | 'oura' | 'whoop' | 'garmin'
  external_user_id TEXT,
  encrypted_tokens BYTEA,            -- pgcrypto-encrypted refresh tokens
  scopes TEXT[],
  last_synced_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Sleep stages within a sleep_records session
CREATE TABLE sleep_stages (
  id UUID PRIMARY KEY,
  sleep_record_id UUID REFERENCES sleep_records(id) ON DELETE CASCADE,
  stage TEXT NOT NULL,               -- 'awake' | 'light' | 'deep' | 'rem'
  started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ON sleep_stages (sleep_record_id, started_at);

-- High-volume HR samples — partition by day for performance
CREATE TABLE heart_rate_samples (
  id UUID DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recorded_at TIMESTAMPTZ NOT NULL,
  bpm SMALLINT NOT NULL,
  source TEXT NOT NULL               -- 'wear_os' | 'fitbit' | ...
) PARTITION BY RANGE (recorded_at);
-- Auto-create monthly partitions via pg_partman or a startup script

-- HRV (less frequent — once per night usually)
CREATE TABLE hrv_samples (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recorded_at TIMESTAMPTZ NOT NULL,
  rmssd_ms DOUBLE PRECISION,
  source TEXT NOT NULL
);
```

### 9.5 — Backend Wearable Foundation

- New `/integrations` route group.
- `wearable_connections` table + `pgcrypto` utilities for token encryption (key in Secrets Manager `ultiq/prod/wearable-tokens`).
- Single normalized API: `GET /sleep/{id}/details` returns stages + HR samples regardless of source.
- Adapter trait:
  ```rust
  #[async_trait]
  trait WearableAdapter {
      async fn fetch_sleep(&self, conn: &Connection, date: NaiveDate) -> Result<SleepImport>;
      async fn refresh_token(&self, conn: &Connection) -> Result<()>;
  }
  ```
- `SleepImport` is a normalized struct: `actual_bedtime`, `actual_wake_time`, `stages: Vec<Stage>`, `heart_rate_samples: Vec<HrSample>`, `hrv: Option<f64>`.

### 9.6 — Fitbit Integration (build first — lowest effort)

- OAuth 2.0 with PKCE. Redirect URI: `https://api.yourdomain.com/integrations/fitbit/callback`.
- Tokens encrypted in `wearable_connections`. Refresh token has 8h expiry — backend retries with refresh on every 401.
- Daily scheduled job (8am user-local time): fetch `/1.2/user/-/sleep/date/{date}.json` → normalize → upsert into `sleep_records` + `sleep_stages`.
- Map Fitbit's stages (`wake`/`light`/`deep`/`rem`) directly.
- Mobile UI: Settings → "Connect Fitbit" → opens browser OAuth → returns to app via deep link (`ultiq://oauth/fitbit?code=...`).

### 9.7 — Wear OS Companion App (the biggest sub-phase)

- New Gradle module: `wear/`. Targets `wearos`, depends on Health Services + DataLayer.
- `PassiveListenerService` subscribes to: `HEART_RATE_BPM`, `SLEEP_STAGE`, `RAW_ACCELEROMETER` (last is high-volume — only enable during the user's typical sleep window).
- DataLayer: watch sends batched JSON to phone every ~5 min during sleep, every 30 min during day.
- Phone-side: `WearableListenerService` receives, persists to Room, syncs to backend on next foreground/Wi-Fi.
- Optional watch UI: a tile showing tonight's data (last HR, sleep duration so far). Nice but not required.
- Manifest permissions on the watch: `BODY_SENSORS`, `ACTIVITY_RECOGNITION`.

### 9.8 — Auto Sleep Detection (replaces the manual Start Sleep, optional)

With HR + accel from the watch:
- Low HR (<60% of daytime average) + minimal movement for >15 min → sleep started.
- Reverse → wake.
- Detection runs on the watch so the phone doesn't have to be charging.
- Replaces "Start Sleep" tap with passive detection. **Manual override stays** — user can always force start/end.

### 9.9 — Sleep Stage Chart & Insights

- New "Tonight" view in Sleep tab when stages exist: hypnogram (timeline coloured by stage).
- Stat cards: % deep, % REM, awakenings, avg HR, lowest HR, HRV (rmssd).
- Compare to recommended targets (deep ≥18%, REM ≥20%).
- Hooks into Phase 8 AI: insight prompt now includes stage data → *"Your deep sleep is 12% — below the 18% target. Common causes: alcohol within 3h of bed, late caffeine, room temp >22°C."*

### 9.10 — Focus Session HR Tracking (bonus)

- During a focus session, watch streams HR to phone.
- After session: "Avg HR 72 bpm, peak 89 (at 0:34)".
- Over time, HRV trends become a recovery indicator — AI can correlate "high HRV days = better focus".

### 9.11 — Smart Alarms (nice-to-have)

- Wake user during light sleep within a 30-min window before their target.
- Watch knows the current stage → fires alarm at the right moment.
- Same idea as Sleep Cycle / Garmin / Withings. Easy once stages flow in.

### 9.12 — Cost & Storage

| Item | Cost |
|---|---|
| Wear OS dev (your time) | The big one |
| Storage growth | HR @ 1 sample/min during sleep ≈ 480 rows/night/user. 100 users × 365 nights ≈ 17M rows/year. `db.t4g.micro` handles fine with monthly partitions, ~$0/month extra |
| Fitbit / Oura / Whoop / Garmin APIs | Free for personal use |
| EventBridge + Lambda for nightly sync | < $1/month at this scale |

### 9.13 — Risks

- **Wear OS fragmentation** — Galaxy Watch (Tizen-derived older versions vs Wear OS 3+), Pixel Watch, generic Wear OS all have slightly different Health Services capabilities. Test on the watch you actually have first.
- **Watch battery** — continuous HR + accel is the #1 drain. Use Health Services' "passive" mode and only sample at the rate you need.
- **OAuth refresh expiry** — Fitbit refresh tokens last 8h; you must refresh on every fetch. Bake retry-on-401 into the adapter.
- **Storage scaling** — at 1000+ users, HR samples become real. Pre-empt with TimescaleDB or move HR to ClickHouse / Redshift once volume warrants. Not a v1 problem.

### 9.14 — Suggested Order of Attack

1. **9.5 + 9.6 (Backend foundation + Fitbit)** — single endpoint group, real sleep stage data immediately, no Wear OS code yet.
2. **9.9 (Hypnogram chart)** — visible payoff for the Fitbit work.
3. **9.7 (Wear OS companion)** — when you have the time. Biggest commitment.
4. **9.8 (Auto detection)** — built on top of Wear OS.
5. **9.10 + 9.11 (HR during focus + smart alarms)** — polish.

---

## Phase Summary

| Phase | Weeks | What You'll Have |
|-------|-------|------------------|
| Phase 0: Scaffold & Auth | 1–2 | Both projects running, user login/register working end to end |
| Phase 1: Sleep Tracking | 3–5 | Full sleep tracker: API + Android screen + charts + sync |
| Phase 2: Pomodoro Sessions | 6–8 | Full pomodoro timer: API + Android screen + streaks + phone detection |
| Phase 3: Calendar | 9–11 | Full calendar: API + Android screen + recurring events |
| Phase 4: Dashboard & Sync | 12–13 | Dashboard home screen, background sync across all features |
| Phase 5: Polish | 14–19 | Notifications, theming, weekly reports, achievements, settings, pickup confirmation gate, **daily checklist + focus dropdown** |
| Phase 6: AWS Deployment + Marketing Site | 20–24 | Production app on AWS (ECS Fargate + RDS + ALB + CI/CD), Next.js marketing site at `your-domain.com`, Google Play Store listing, **direct-APK download + Play Internal Testing track (6.14)** |
| Phase 7: Web Analytics Dashboard | 25–29 | Rust/WASM analytics site at `app.your-domain.com` (Leptos + S3 + CloudFront) — full-stack Rust portfolio piece |
| Phase 8: AI Integration | 30–32 | Backend-mediated Claude integration: weekly insights, NL event parsing, coach chat, anomaly detection |
| Phase 9: Sleep Monitoring & Wearables | 33–36 | Fitbit + Wear OS companion app, sleep stages + HR + HRV, hypnogram chart, optional auto sleep detection |
| **Total** | **~36 weeks** | **Production app on AWS + Google Play + marketing site + Rust web dashboard + AI features + wearable integrations** |

Assumes ~15–20 hours/week. Phase 7 is the "ambitious" extension — skip it if you'd rather ship the mobile app first and add the web frontend as a v2. Phases 8 and 9 are post-launch additions; Phase 9 stays fully optional for users (the existing manual sleep flow is preserved).
