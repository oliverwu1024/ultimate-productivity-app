use gloo_storage::{LocalStorage, Storage};
use leptos::prelude::*;

const KEY: &str = "ultiq_theme";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Theme {
    Light,
    Dark,
    System,
}

impl Theme {
    fn key(&self) -> Option<&'static str> {
        match self {
            Self::Light => Some("light"),
            Self::Dark => Some("dark"),
            Self::System => None,
        }
    }
    fn from_key(s: &str) -> Self {
        match s {
            "light" => Self::Light,
            "dark" => Self::Dark,
            _ => Self::System,
        }
    }
}

#[derive(Clone, Copy)]
pub struct ThemeContext {
    pub theme: RwSignal<Theme>,
}

pub fn provide_theme() {
    let initial = LocalStorage::get::<String>(KEY)
        .ok()
        .map(|s| Theme::from_key(&s))
        .unwrap_or(Theme::System);
    let theme = RwSignal::new(initial);
    provide_context(ThemeContext { theme });

    Effect::new(move |_| {
        let t = theme.get();
        apply(t);
        match t.key() {
            Some(k) => { let _ = LocalStorage::set(KEY, k); }
            None => LocalStorage::delete(KEY),
        }
    });
}

pub fn use_theme() -> ThemeContext {
    expect_context::<ThemeContext>()
}

fn apply(t: Theme) {
    let dark = match t {
        Theme::Light => false,
        Theme::Dark => true,
        Theme::System => system_prefers_dark(),
    };
    if let Some(window) = web_sys::window() {
        if let Some(html) = window.document().and_then(|d| d.document_element()) {
            let classes = html.class_list();
            if dark {
                let _ = classes.add_1("dark");
            } else {
                let _ = classes.remove_1("dark");
            }
        }
    }
}

fn system_prefers_dark() -> bool {
    web_sys::window()
        .and_then(|w| w.match_media("(prefers-color-scheme: dark)").ok().flatten())
        .map(|m| m.matches())
        .unwrap_or(false)
}
