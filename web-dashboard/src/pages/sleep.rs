use chrono::{Duration, Local, NaiveDate};
use leptos::either::Either;
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::sleep::{
    delete_audio_clip, fetch_clip_playback_url, fetch_stats, list_audio_events_for_record,
    list_records, SleepAudioEvent, SleepRecord, SleepStats,
};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::layout::AppShell;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Range {
    Week,
    Month,
    Quarter,
}

impl Range {
    fn label(&self) -> &'static str {
        match self {
            Self::Week => "Week",
            Self::Month => "Month",
            Self::Quarter => "90d",
        }
    }
    fn stats_param(&self) -> &'static str {
        match self {
            Self::Week => "week",
            Self::Month => "month",
            Self::Quarter => "month",
        }
    }
    fn days(&self) -> i64 {
        match self {
            Self::Week => 7,
            Self::Month => 30,
            Self::Quarter => 90,
        }
    }
}

#[component]
pub fn SleepPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let range = RwSignal::new(Range::Month);
    let records: RwSignal<Vec<SleepRecord>> = RwSignal::new(Vec::new());
    let stats: RwSignal<Option<SleepStats>> = RwSignal::new(None);
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    // §10 — Audio events for the most-recent sleep_record, populated after
    // records load. Drives the "Sleep sounds — Last night" card; stays empty
    // (and the card hides) when audio tracking was off / no events captured.
    let latest_audio_events: RwSignal<Vec<SleepAudioEvent>> = RwSignal::new(Vec::new());

    let refresh = move || {
        let r = range.get_untracked();
        let start = today - Duration::days(r.days() - 1);
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let recs = list_records(start, today).await;
            let st = fetch_stats(r.stats_param()).await;
            match (recs, st) {
                (Ok(rs), Ok(s)) => {
                    // §last-night — skip naps so the sounds card tracks the
                    // most-recent overnight sleep, not a daytime nap.
                    let latest_id = rs.iter().find(|r| !r.is_nap).map(|r| r.id.clone());
                    records.set(rs);
                    stats.set(Some(s));
                    // Fetch audio events for the most-recent record (if any).
                    if let Some(id) = latest_id {
                        wasm_bindgen_futures::spawn_local(async move {
                            if let Ok(events) = list_audio_events_for_record(&id).await {
                                latest_audio_events.set(events);
                            } else {
                                latest_audio_events.set(Vec::new());
                            }
                        });
                    } else {
                        latest_audio_events.set(Vec::new());
                    }
                }
                (Err(e), _) | (_, Err(e)) => error.set(Some(e.message)),
            }
            loading.set(false);
        });
    };

    Effect::new(move |_| {
        let _ = range.get();
        refresh();
    });

    let sse = use_sse();
    Effect::new(move |_| {
        if let Some(ev) = sse.last_event_debounced.get() {
            match ev {
                SyncEvent::SleepCreated(_)
                | SyncEvent::SleepUpdated(_)
                | SyncEvent::SleepDeleted(_) => refresh(),
                // §10.x-fix (v2.14.2) — Refetch just the audio events for
                // the affected record. Full refresh would also reload the
                // records list + stats, which is wasteful since clip
                // attaches don't change either. A snore-heavy night fires
                // this ~10-20 times in a few seconds, so the cheaper path
                // matters.
                SyncEvent::SleepAudioClipsChanged(payload) => {
                    let target_id = payload.sleep_record_id;
                    wasm_bindgen_futures::spawn_local(async move {
                        if let Ok(events) = list_audio_events_for_record(&target_id).await {
                            latest_audio_events.set(events);
                        }
                    });
                }
                _ => {}
            }
        }
    });

    view! {
        <Title text="Sleep — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-5xl mx-auto">
                <header class="flex items-center justify-between mb-6 flex-wrap gap-3">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Sleep"</h1>
                    <RangeToggle range=range />
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                {move || match (stats.get(), loading.get()) {
                    (Some(s), _) => Either::Left(view! {
                        <StatRow stats=s />
                    }),
                    (None, true) => Either::Right(view! {
                        <p class="text-ultiq-indigo/50 text-sm">"Loading…"</p>
                    }),
                    (None, false) => Either::Right(view! {
                        <p class="text-ultiq-indigo/50 text-sm">"No data yet."</p>
                    }),
                }}

                <LastNightSoundsCard
                    events=latest_audio_events
                    records=records
                />


                <section class="bg-white rounded-2xl shadow p-6 mt-6">
                    <header class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-ultiq-indigo">"Duration over time"</h2>
                        <p class="text-xs text-ultiq-indigo/50">
                            {move || format!("Last {} nights", range.get().days())}
                        </p>
                    </header>
                    <DurationChart records=records stats=stats today=today range=range />
                </section>

                <div class="grid grid-cols-1 md:grid-cols-2 gap-6 mt-6">
                    <section class="bg-white rounded-2xl shadow p-6">
                        <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                            "Quality distribution"
                        </h2>
                        <QualityHistogram records=records />
                    </section>

                    <section class="bg-white rounded-2xl shadow p-6">
                        <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                            "Phone pickups during sleep"
                        </h2>
                        <PickupsBar records=records today=today range=range />
                    </section>
                </div>
            </div>
        </AppShell>
    }
}

/// §10.x — Renders the per-event detection list for the most-recent
/// sleep_record. Each row shows time + type + duration; rows with an
/// attached Pro clip get an inline ▶ that expands to a scrub bar + delete.
/// Auto-hides entirely when no events were captured (users who never
/// turned audio tracking on never see this surface).
#[component]
fn LastNightSoundsCard(
    events: RwSignal<Vec<SleepAudioEvent>>,
    records: RwSignal<Vec<SleepRecord>>,
) -> impl IntoView {
    // Single-row-expanded state: which event id is currently expanded for
    // playback. None = nothing expanded. Auto-advance updates this when
    // one clip ends so the next clipped event opens itself.
    let playing_event_id: RwSignal<Option<String>> = RwSignal::new(None);

    view! {
        {move || {
            let evs = events.get();
            if evs.is_empty() {
                return Either::Left(view! { <></> });
            }
            // Date label: "Last night" for records within ~36h, else
            // MMM dd by sleep_day so the card name matches the chart bar.
            let label = records
                .get()
                .iter()
                .find(|r| !r.is_nap)
                .map(|r| {
                    let now = chrono::Utc::now();
                    let age = now - r.actual_bedtime;
                    if age.num_hours() <= 36 {
                        "Last night".to_string()
                    } else {
                        crate::sleep_day::sleep_day_for(r.actual_bedtime)
                            .format("%a, %b %d")
                            .to_string()
                    }
                })
                .unwrap_or_else(|| "Last night".to_string());

            let any_clips = evs.iter().any(|e| e.has_clip);
            let snore_count = evs.iter().filter(|e| e.event_type == "snore").count();
            let cough_count = evs.iter().filter(|e| e.event_type == "cough").count();
            let talk_count = evs.iter().filter(|e| e.event_type == "sleep_talk").count();

            Either::Right(view! {
                <section class="bg-white rounded-2xl shadow p-6 mt-6">
                    <header class="flex items-center justify-between mb-3 flex-wrap gap-2">
                        <h2 class="text-lg font-semibold text-ultiq-indigo">
                            "Sleep sounds — " {label}
                        </h2>
                        <p class="text-xs text-ultiq-indigo/50">
                            {if any_clips { "Clips auto-expire after 30 days" } else { "Detection only · audio not uploaded" }}
                        </p>
                    </header>

                    <div class="flex flex-wrap gap-x-4 gap-y-1 mb-4 text-sm text-ultiq-indigo/70">
                        {(snore_count > 0).then(|| view! {
                            <span><span class="inline-block w-2 h-2 rounded-full mr-1.5" style="background:#7C8AFC"></span>{format!("{} snore", snore_count)}</span>
                        })}
                        {(cough_count > 0).then(|| view! {
                            <span><span class="inline-block w-2 h-2 rounded-full mr-1.5" style="background:#FFC83D"></span>{format!("{} cough", cough_count)}</span>
                        })}
                        {(talk_count > 0).then(|| view! {
                            <span><span class="inline-block w-2 h-2 rounded-full mr-1.5" style="background:#2ECC71"></span>{format!("{} sleep-talk", talk_count)}</span>
                        })}
                    </div>

                    <ul class="divide-y divide-ultiq-indigo/10">
                        {evs.iter().enumerate().map(|(idx, e)| {
                            let event_owned = e.clone();
                            let all_events = evs.clone();
                            view! {
                                <li>
                                    <EventRow
                                        event=event_owned
                                        all_events=all_events
                                        index=idx
                                        playing_event_id=playing_event_id
                                        on_deleted=move |id: String| {
                                            events.update(|list| {
                                                if let Some(found) = list.iter_mut().find(|x| x.id == id) {
                                                    found.has_clip = false;
                                                    found.clip_duration_ms = None;
                                                }
                                            });
                                        }
                                    />
                                </li>
                            }
                        }).collect_view()}
                    </ul>
                </section>
            })
        }}
    }
}

fn event_label(event_type: &str) -> &'static str {
    match event_type {
        "snore" => "Snore",
        "cough" => "Cough",
        "sleep_talk" => "Sleep-talk",
        _ => "Event",
    }
}

fn event_color(event_type: &str) -> &'static str {
    match event_type {
        "snore" => "#7C8AFC",
        "cough" => "#FFC83D",
        "sleep_talk" => "#2ECC71",
        _ => "#7C8AFC",
    }
}

/// §10.x-fix (v2.14.4) — Fetch clip bytes via the backend proxy (JWT
/// auth, CORS already wired for app→api) and wrap in a same-origin
/// `blob:` URL the audio element can play without any cross-origin
/// gymnastics.
///
/// The blob URL is leaked intentionally — `URL.revokeObjectURL` isn't
/// called when the row collapses. With ~80 KB clips and one expansion at
/// a time the leak is bounded by single-digit MB per page session and the
/// URLs are freed at the next page navigation.
async fn fetch_clip_bytes_as_blob_url(event_id: &str) -> Result<String, String> {
    let token = crate::auth::AuthContext::token()
        .ok_or_else(|| "Not signed in".to_string())?;
    let url = format!(
        "{}/sleep-audio-events/{}/clip-bytes",
        crate::api::client::api_base_url(),
        event_id,
    );
    let resp = gloo_net::http::Request::get(&url)
        .header("Authorization", &format!("Bearer {}", token))
        .send()
        .await
        .map_err(|e| format!("Couldn't reach clip server: {}", e))?;
    if !(200..300).contains(&resp.status()) {
        return Err(format!("Clip server returned {}", resp.status()));
    }
    let bytes = resp
        .binary()
        .await
        .map_err(|e| format!("Couldn't read clip body: {}", e))?;

    // §10.x-fix (v2.14.5) — Use `new_with_u8_array_sequence_and_options`
    // (the wasm-bindgen-blessed path) instead of buffer_source. Same idea
    // but with explicit Uint8Array semantics. v2.14.4's
    // `parts.push(&array.buffer())` pushed the underlying ArrayBuffer,
    // which Chrome's Blob constructor apparently interpreted weirdly
    // (audio element decoded as 0 sec despite valid bytes arriving).
    let uint8 = js_sys::Uint8Array::from(&bytes[..]);
    let parts = js_sys::Array::of1(&uint8);
    let opts = web_sys::BlobPropertyBag::new();
    opts.set_type("audio/mp4");
    let blob = web_sys::Blob::new_with_u8_array_sequence_and_options(&parts, &opts)
        .map_err(|_| "Couldn't wrap clip in a Blob".to_string())?;
    let blob_url = web_sys::Url::create_object_url_with_blob(&blob)
        .map_err(|_| "Couldn't create blob URL".to_string())?;
    Ok(blob_url)
}

fn format_clip_duration(ms: Option<i32>) -> String {
    let ms = ms.unwrap_or(0);
    if ms <= 0 {
        return "—".to_string();
    }
    let secs = (ms + 500) / 1000;
    format!("{}s", secs)
}

#[component]
fn EventRow<F>(
    event: SleepAudioEvent,
    all_events: Vec<SleepAudioEvent>,
    index: usize,
    playing_event_id: RwSignal<Option<String>>,
    on_deleted: F,
) -> impl IntoView
where
    F: Fn(String) + 'static + Clone + Send + Sync,
{
    let event_id_for_play = event.id.clone();
    let event_id_for_collapse = event.id.clone();
    let event_id_for_render = event.id.clone();
    let event_id_for_audio = event.id.clone();
    let event_id_for_delete = event.id.clone();
    let has_clip = event.has_clip;
    let event_type = event.event_type.clone();
    let duration_label = format_clip_duration(event.clip_duration_ms);
    let confidence_pct = (event.peak_confidence * 100.0).round() as i32;

    // Format started_at in the user's local timezone.
    let time_label = event.started_at.with_timezone(&Local).format("%H:%M:%S").to_string();
    let color = event_color(&event_type);
    let label = event_label(&event_type);

    let is_expanded = {
        let id = event_id_for_render.clone();
        std::sync::Arc::new(move || playing_event_id.get().as_deref() == Some(id.as_str()))
    };

    let toggle = {
        let ev_id = event_id_for_play.clone();
        let collapse_id = event_id_for_collapse.clone();
        move |_| {
            if !has_clip {
                return;
            }
            playing_event_id.update(|p| {
                if p.as_deref() == Some(collapse_id.as_str()) {
                    *p = None;
                } else {
                    *p = Some(ev_id.clone());
                }
            });
        }
    };

    view! {
        <div class="py-2">
            <button
                class=move || {
                    let base = "w-full flex items-center justify-between gap-3 text-left rounded-lg px-2 py-2 transition-colors";
                    if has_clip {
                        format!("{} hover:bg-ultiq-indigo/5 cursor-pointer", base)
                    } else {
                        format!("{} cursor-default opacity-80", base)
                    }
                }
                disabled=!has_clip
                on:click=toggle
            >
                <div class="flex items-center gap-3">
                    <span
                        class="inline-block w-2.5 h-2.5 rounded-full flex-shrink-0"
                        style=format!("background:{}", color)
                    />
                    <span class="font-mono text-sm text-ultiq-indigo/80 tabular-nums">{time_label.clone()}</span>
                    <span class="text-sm text-ultiq-indigo">{label}</span>
                </div>
                <div class="flex items-center gap-3 text-sm text-ultiq-indigo/60">
                    {if has_clip {
                        let is_exp = is_expanded.clone();
                        Either::Left(view! {
                            <span class="text-xs">{duration_label.clone()}</span>
                            <span class="text-ultiq-indigo">{move || if is_exp() { "▾" } else { "▸" }}</span>
                        })
                    } else {
                        Either::Right(view! {
                            <span class="text-xs italic text-ultiq-indigo/40">"no clip"</span>
                        })
                    }}
                </div>
            </button>

            <Show when={
                let is_exp = is_expanded.clone();
                move || is_exp() && has_clip
            }>
                <ExpandedPlayer
                    event_id=event_id_for_audio.clone()
                    all_events=all_events.clone()
                    index=index
                    confidence_pct=confidence_pct
                    playing_event_id=playing_event_id
                    on_deleted={
                        let cb = on_deleted.clone();
                        let id = event_id_for_delete.clone();
                        move || cb(id.clone())
                    }
                />
            </Show>
        </div>
    }
}

#[component]
fn ExpandedPlayer<F>(
    event_id: String,
    all_events: Vec<SleepAudioEvent>,
    index: usize,
    confidence_pct: i32,
    playing_event_id: RwSignal<Option<String>>,
    on_deleted: F,
) -> impl IntoView
where
    F: Fn() + 'static + Clone + Send + Sync,
{
    let url: RwSignal<Option<String>> = RwSignal::new(None);
    let load_error: RwSignal<Option<String>> = RwSignal::new(None);
    let deleting = RwSignal::new(false);
    let confirming_delete = RwSignal::new(false);

    {
        // §10.x-fix (v2.14.4) — Fetch the clip bytes through the BACKEND
        // proxy (api.ultiqapp.com/sleep-audio-events/:id/clip-bytes) and
        // wrap them in a Blob → object URL. The audio element then loads
        // from a same-origin `blob:` URL. This sidesteps every CORS edge
        // case that the v2.14.0-v2.14.3 attempts kept hitting:
        //   - v2.14.0: audio src = presigned S3 URL → stuck at 0:00
        //   - v2.14.1: + audio/mp4 MIME on the S3 object → still 0:00
        //   - v2.14.2: + S3 bucket CORS for app.ultiqapp.com → still 0:00
        //   - v2.14.3: blob URL from direct S3 fetch via gloo-net →
        //     "TypeError: Failed to fetch" (gloo-net's mode=cors triggered
        //     a preflight S3 didn't honor for non-OPTIONS-aware origin)
        // Backend proxy works because the existing API CORS already
        // allows app.ultiqapp.com → api.ultiqapp.com fully, and S3 is
        // hit server-side with the ECS task role IAM (no signing /
        // preflight in the picture at all).
        let event_id = event_id.clone();
        wasm_bindgen_futures::spawn_local(async move {
            match fetch_clip_bytes_as_blob_url(&event_id).await {
                Ok(blob_url) => url.set(Some(blob_url)),
                Err(msg) => load_error.set(Some(msg)),
            }
        });
    }

    // Auto-advance: when this clip ends, look for the next event (later in
    // the list) that still has a clip and expand it. Stops at the end of
    // the list so the user isn't surprised by a sudden silence-then-replay.
    let on_ended = {
        let all_events = all_events.clone();
        move |_| {
            let next = all_events
                .iter()
                .skip(index + 1)
                .find(|e| e.has_clip)
                .map(|e| e.id.clone());
            playing_event_id.set(next);
        }
    };

    let confirm_delete = {
        let event_id = event_id.clone();
        let on_deleted = on_deleted.clone();
        move |_| {
            deleting.set(true);
            let event_id = event_id.clone();
            let on_deleted = on_deleted.clone();
            wasm_bindgen_futures::spawn_local(async move {
                let _ = delete_audio_clip(&event_id).await;
                deleting.set(false);
                confirming_delete.set(false);
                playing_event_id.set(None);
                on_deleted();
            });
        }
    };

    view! {
        <div class="px-2 pt-2 pb-3 bg-ultiq-indigo/5 rounded-lg mx-2 mt-1">
            {move || match (url.get(), load_error.get()) {
                (Some(u), _) => Either::Left(view! {
                    // §10.x-fix (v2.14.3) — preload="metadata" forces the
                    // browser to fetch enough of the MP4 to read mvhd +
                    // mdhd up-front, so the duration shows immediately
                    // instead of "0:00 / 0:00". Without it Chrome on
                    // desktop sometimes defers metadata loading until the
                    // user presses play — and if a CORS preflight fails
                    // mid-Range-request, the user just sees a stuck 0:00
                    // control with no obvious error.
                    <audio
                        src=u
                        controls=true
                        autoplay=true
                        preload="metadata"
                        class="w-full"
                        on:ended=on_ended.clone()
                    />
                }),
                (None, Some(err)) => Either::Right(Either::Left(view! {
                    <p class="text-xs text-ultiq-red px-1 py-2">{err}</p>
                })),
                (None, None) => Either::Right(Either::Right(view! {
                    <p class="text-xs text-ultiq-indigo/50 px-1 py-2">"Loading clip…"</p>
                })),
            }}
            <div class="flex items-center justify-between mt-2 px-1">
                <span class="text-xs text-ultiq-indigo/60">
                    {format!("Detection confidence {}%", confidence_pct)}
                </span>
                <Show when=move || !confirming_delete.get()>
                    <button
                        class="text-xs text-ultiq-red hover:underline cursor-pointer"
                        on:click=move |_| confirming_delete.set(true)
                    >
                        "Delete clip"
                    </button>
                </Show>
                <Show when=move || confirming_delete.get()>
                    <div class="flex items-center gap-2">
                        <span class="text-xs text-ultiq-indigo/70">"Delete this recording?"</span>
                        <button
                            class="text-xs text-ultiq-red font-medium hover:underline cursor-pointer disabled:opacity-50"
                            disabled=move || deleting.get()
                            on:click=confirm_delete.clone()
                        >
                            {move || if deleting.get() { "Deleting…" } else { "Confirm" }}
                        </button>
                        <button
                            class="text-xs text-ultiq-indigo/60 hover:underline cursor-pointer"
                            on:click=move |_| confirming_delete.set(false)
                        >
                            "Cancel"
                        </button>
                    </div>
                </Show>
            </div>
        </div>
    }
}

#[component]
fn RangeToggle(range: RwSignal<Range>) -> impl IntoView {
    view! {
        <div class="flex items-center gap-1 bg-white rounded-full border border-ultiq-indigo/15 p-1">
            {[Range::Week, Range::Month, Range::Quarter].iter().map(|r| {
                let r = *r;
                let is_active = move || range.get() == r;
                view! {
                    <button
                        on:click=move |_| range.set(r)
                        class=move || {
                            let base = "px-3 py-1 text-sm rounded-full transition-colors cursor-pointer";
                            if is_active() {
                                format!("{} bg-ultiq-indigo text-ultiq-cream", base)
                            } else {
                                format!("{} text-ultiq-indigo/70 hover:text-ultiq-indigo", base)
                            }
                        }
                    >
                        {r.label()}
                    </button>
                }
            }).collect_view()}
        </div>
    }
}

#[component]
fn StatRow(stats: SleepStats) -> impl IntoView {
    let avg_duration = format_minutes(stats.avg_duration_minutes);
    let avg_quality = if stats.total_records == 0 {
        "—".to_string()
    } else {
        format!("{:.1}/5", stats.avg_quality)
    };
    let debt = if stats.debt_minutes > 0.0 {
        format_minutes(stats.debt_minutes)
    } else {
        "—".to_string()
    };
    let extra = if stats.extra_minutes > 0.0 {
        format_minutes(stats.extra_minutes)
    } else {
        "—".to_string()
    };
    let pickups = if stats.total_records == 0 {
        "—".to_string()
    } else {
        format!("{:.1}", stats.avg_phone_pickups)
    };
    let target = format_minutes(stats.sleep_target_minutes as f64);

    view! {
        <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
            <Stat label="Avg duration" value=avg_duration sub=Some(format!("target {}", target)) />
            <Stat label="Avg quality" value=avg_quality sub=None />
            <Stat label="Sleep debt" value=debt sub=None />
            <Stat label="Extra sleep" value=extra sub=None />
            <Stat label="Avg pickups" value=pickups sub=None />
        </div>
    }
}

#[component]
fn Stat(
    label: &'static str,
    value: String,
    sub: Option<String>,
) -> impl IntoView {
    view! {
        <div class="bg-white rounded-2xl p-4 shadow-sm">
            <p class="text-xs text-ultiq-indigo/60 font-medium uppercase tracking-wider">{label}</p>
            <p class="text-2xl font-bold text-ultiq-indigo mt-1">{value}</p>
            <Show when={
                let s = sub.clone();
                move || s.is_some()
            }>
                <p class="text-xs text-ultiq-indigo/50 mt-1">
                    {sub.clone().unwrap_or_default()}
                </p>
            </Show>
        </div>
    }
}

fn format_minutes(m: f64) -> String {
    if m <= 0.0 {
        return "0m".to_string();
    }
    let h = (m / 60.0).floor() as i64;
    let mins = (m - (h as f64 * 60.0)).round() as i64;
    if h == 0 {
        format!("{}m", mins)
    } else if mins == 0 {
        format!("{}h", h)
    } else {
        format!("{}h {}m", h, mins)
    }
}

fn duration_minutes(record: &SleepRecord) -> f64 {
    let secs = (record.actual_wake_time - record.actual_bedtime).num_seconds() as f64;
    (secs / 60.0).max(0.0)
}

fn quality_color(rating: i16) -> &'static str {
    match rating {
        5 => "#2ECC71",
        4 => "#7ED957",
        3 => "#FFC83D",
        2 => "#E67E22",
        _ => "#D9474C",
    }
}

#[component]
fn DurationChart(
    records: RwSignal<Vec<SleepRecord>>,
    stats: RwSignal<Option<SleepStats>>,
    today: NaiveDate,
    range: RwSignal<Range>,
) -> impl IntoView {
    view! {
        {move || {
            let recs = records.get();
            let r = range.get();
            let st = stats.get();
            let target_min = st.as_ref().map(|s| s.sleep_target_minutes as f64).unwrap_or(480.0);
            let days = r.days();
            let start = today - Duration::days(days - 1);

            // §sleep-day (v2.13.x) — Each record anchors on its sleep_day
            // (bedtime − 6 h in local tz), matching the Android Dashboard
            // and Sleep tab. A Tue 02:00 bedtime now sits in Monday's
            // column instead of Tuesday's — Monday-night sleep finally
            // gets a Monday bar.
            let mut points: Vec<(i64, f64, i16)> = Vec::new();
            for rec in &recs {
                let sleep_day = crate::sleep_day::sleep_day_for(rec.actual_bedtime);
                let idx = (sleep_day - start).num_days();
                if idx >= 0 && idx < days {
                    points.push((idx, duration_minutes(rec), rec.quality_rating));
                }
            }

            if points.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-12 text-center">
                        "No sleep records in this range."
                    </p>
                }.into_any();
            }

            // Y-axis: 0 to max(target * 1.4, max_duration * 1.1) hours capped at 12h
            let max_duration = points.iter().map(|p| p.1).fold(0.0_f64, f64::max);
            let y_max = (target_min * 1.4).max(max_duration * 1.1).min(720.0).max(target_min + 60.0);

            // SVG dims
            let w: f64 = 720.0;
            let h: f64 = 240.0;
            let pad_l = 40.0;
            let pad_r = 16.0;
            let pad_t = 16.0;
            let pad_b = 32.0;
            let plot_w = w - pad_l - pad_r;
            let plot_h = h - pad_t - pad_b;

            // §v2.16.4 chart-fix — Slot-based positioning. Each day
            // occupies a slot of width plot_w/days; the bar centres in its
            // slot. The previous formula put bar centres on the chart
            // edges (idx=0 at pad_l, idx=days-1 at pad_l+plot_w), so the
            // leftmost bar's left half extended into the y-axis label
            // gutter and painted over the "2h"/"4h"/"6h" labels in the
            // wide-bar Week view. With slots, every label is fully clear
            // of bars regardless of view (Week / Month / 90d).
            let x_for = |idx: i64| -> f64 {
                if days <= 1 {
                    pad_l + plot_w / 2.0
                } else {
                    let slot_w = plot_w / (days as f64);
                    pad_l + ((idx as f64) + 0.5) * slot_w
                }
            };
            let y_for = |val: f64| -> f64 {
                pad_t + (1.0 - (val / y_max).clamp(0.0, 1.0)) * plot_h
            };

            let target_y = y_for(target_min);

            // Bars (one per record)
            let bars = points.iter().map(|&(idx, dur, q)| {
                let cx = x_for(idx);
                let bw = (plot_w / (days as f64) * 0.55).max(4.0);
                let by = y_for(dur);
                let bh = (pad_t + plot_h - by).max(2.0);
                view! {
                    <rect
                        x=cx - bw / 2.0
                        y=by
                        width=bw
                        height=bh
                        rx=2
                        fill=quality_color(q)
                    >
                        <title>
                            {format!("{} · quality {}/5", format_minutes(dur), q)}
                        </title>
                    </rect>
                }
            }).collect_view();

            // Y-axis ticks every 2 hours
            let mut ticks: Vec<i64> = Vec::new();
            let mut t = 0;
            while (t as f64) <= y_max {
                ticks.push(t);
                t += 120;
            }
            let tick_lines = ticks.iter().map(|&m| {
                let y = y_for(m as f64);
                // §v2.16.4 chart-fix — The bottom tick label ("0h") sits at
                // the same y-coordinate as the bar bottoms; with font-size
                // 10 and a +4 baseline offset, the top ~4 px of the glyph
                // ended up inside the plot area and got painted over by
                // any full-height bar. Push the bottom label fully below
                // the axis line (+14 offset puts the glyph top a couple
                // px below the bar bottom). Other ticks keep the original
                // centred look since bars never reach them.
                let label_y_offset = if m == 0 { 14.0 } else { 4.0 };
                view! {
                    <g>
                        <line
                            x1=pad_l y1=y x2=pad_l + plot_w y2=y
                            stroke="currentColor" stroke-opacity="0.06" stroke-width="1"
                        />
                        <text
                            x=pad_l - 8.0 y=y + label_y_offset
                            text-anchor="end"
                            font-size="10"
                            fill="currentColor"
                            opacity="0.5"
                        >
                            {format!("{}h", m / 60)}
                        </text>
                    </g>
                }
            }).collect_view();

            view! {
                <div class="overflow-x-auto">
                    <svg
                        viewBox=format!("0 0 {} {}", w, h)
                        class="w-full h-auto text-ultiq-indigo"
                        preserveAspectRatio="xMidYMid meet"
                    >
                        {tick_lines}
                        {bars}
                        // Target line
                        <line
                            x1=pad_l y1=target_y x2=pad_l + plot_w y2=target_y
                            stroke="currentColor" stroke-opacity="0.5" stroke-width="1.5" stroke-dasharray="4 4"
                        />
                        <text
                            x=pad_l + plot_w - 4.0 y=target_y - 6.0
                            text-anchor="end" font-size="10" fill="currentColor" opacity="0.7"
                        >
                            {format!("target {}", format_minutes(target_min))}
                        </text>
                    </svg>
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn QualityHistogram(records: RwSignal<Vec<SleepRecord>>) -> impl IntoView {
    view! {
        {move || {
            let recs = records.get();
            if recs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        "No nights recorded."
                    </p>
                }.into_any();
            }
            let mut counts = [0_i32; 6]; // index 1..=5 used
            for r in &recs {
                let q = r.quality_rating.clamp(1, 5) as usize;
                counts[q] += 1;
            }
            let max = *counts.iter().max().unwrap_or(&1).max(&1);
            view! {
                <div class="flex items-end justify-around gap-2 h-32">
                    {(1..=5).map(|q| {
                        let c = counts[q];
                        let pct = (c as f64 / max as f64) * 100.0;
                        let color = quality_color(q as i16);
                        view! {
                            <div class="flex flex-col items-center justify-end h-full flex-1 gap-1">
                                <span class="text-xs text-ultiq-indigo/60 font-medium">{c}</span>
                                <div
                                    class="w-full rounded-t transition-all"
                                    style:height=format!("{}%", pct.max(2.0))
                                    style:background-color=color
                                />
                                <span class="text-xs text-ultiq-indigo/70 mt-1">
                                    {format!("{}★", q)}
                                </span>
                            </div>
                        }
                    }).collect_view()}
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn PickupsBar(
    records: RwSignal<Vec<SleepRecord>>,
    today: NaiveDate,
    range: RwSignal<Range>,
) -> impl IntoView {
    view! {
        {move || {
            let recs = records.get();
            let r = range.get();
            let days = r.days();
            let start = today - Duration::days(days - 1);

            if recs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        "No nights recorded."
                    </p>
                }.into_any();
            }

            // Map per night: (idx, pickups)
            let mut series: Vec<(i64, i32)> = Vec::with_capacity(days as usize);
            for d in 0..days {
                series.push((d, 0));
            }
            for rec in &recs {
                // §sleep-day — Same bucketing as DurationChart above so
                // the two charts always agree column-for-column.
                let sleep_day = crate::sleep_day::sleep_day_for(rec.actual_bedtime);
                let idx = (sleep_day - start).num_days();
                if idx >= 0 && (idx as usize) < series.len() {
                    series[idx as usize].1 = series[idx as usize].1.max(rec.phone_pickups);
                }
            }

            let max = series.iter().map(|s| s.1).max().unwrap_or(0).max(1);
            let avg = (recs.iter().map(|r| r.phone_pickups as i64).sum::<i64>() as f64)
                / (recs.len() as f64);

            view! {
                <div class="space-y-3">
                    <div class="flex items-end gap-1 h-32">
                        {series.into_iter().map(|(_idx, count)| {
                            let pct = (count as f64 / max as f64) * 100.0;
                            view! {
                                <div
                                    class="flex-1 bg-ultiq-red/70 rounded-t hover:bg-ultiq-red transition-colors"
                                    style:height=format!("{}%", pct.max(2.0))
                                    title=format!("{} pickups", count)
                                />
                            }
                        }).collect_view()}
                    </div>
                    <p class="text-xs text-ultiq-indigo/60">
                        {format!("Avg {:.1} pickups/night · max {} on a single night", avg, max)}
                    </p>
                </div>
            }.into_any()
        }}
    }
}
