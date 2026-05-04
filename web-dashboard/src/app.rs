use leptos::prelude::*;
use leptos_router::components::{Route, Router, Routes};
use leptos_router::path;

use crate::auth::provide_auth;
use crate::pages::admin::AdminPage;
use crate::pages::calendar::CalendarPage;
use crate::pages::login::LoginPage;
use crate::pages::overview::OverviewPage;

#[component]
pub fn App() -> impl IntoView {
    provide_auth();

    view! {
        <Router>
            <Routes fallback=|| view! { <p class="p-8">"Page not found"</p> }>
                <Route path=path!("/login") view=LoginPage />
                <Route path=path!("/") view=OverviewPage />
                <Route path=path!("/calendar") view=CalendarPage />
                <Route path=path!("/admin") view=AdminPage />
            </Routes>
        </Router>
    }
}
