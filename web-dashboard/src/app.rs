use leptos::prelude::*;
use leptos_router::components::{Route, Router, Routes};
use leptos_router::path;

use crate::api::sse;
use crate::auth::{provide_auth, use_auth, AuthContext};
use crate::pages::admin::AdminPage;
use crate::pages::calendar::CalendarPage;
use crate::pages::checklist::ChecklistPage;
use crate::pages::correlations::CorrelationsPage;
use crate::pages::focus::FocusPage;
use crate::pages::login::LoginPage;
use crate::pages::overview::OverviewPage;
use crate::pages::reports::ReportsPage;
use crate::pages::sleep::SleepPage;

#[component]
pub fn App() -> impl IntoView {
    provide_auth();
    sse::provide_sse();

    let auth = use_auth();
    Effect::new(move |_| {
        // Open the SSE channel as soon as we have a token + a hydrated user.
        // Close it if we lose either (e.g., logout, 401 on /auth/me).
        let signed_in = auth.user.get().is_some() && AuthContext::token().is_some();
        if signed_in {
            sse::connect_for_current_user();
        } else {
            sse::disconnect();
        }
    });

    view! {
        <Router>
            <Routes fallback=|| view! { <p class="p-8">"Page not found"</p> }>
                <Route path=path!("/login") view=LoginPage />
                <Route path=path!("/") view=OverviewPage />
                <Route path=path!("/calendar") view=CalendarPage />
                <Route path=path!("/checklist") view=ChecklistPage />
                <Route path=path!("/sleep") view=SleepPage />
                <Route path=path!("/focus") view=FocusPage />
                <Route path=path!("/correlations") view=CorrelationsPage />
                <Route path=path!("/reports") view=ReportsPage />
                <Route path=path!("/admin") view=AdminPage />
            </Routes>
        </Router>
    }
}
