use leptos::mount::mount_to_body;

mod api;
mod app;
mod auth;
mod components;
mod pages;

fn main() {
    console_error_panic_hook::set_once();
    mount_to_body(app::App);
}
