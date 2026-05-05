// Pre-paint dark-mode application: runs before WASM loads to avoid a flash
// of the wrong theme. Reads localStorage first, falls back to system pref.
// Kept in a standalone file (not inline) so the page's CSP can be a clean
// `script-src 'self' 'wasm-unsafe-eval'` with no per-script SHA hash to
// maintain.
(function () {
  try {
    var stored = localStorage.getItem("ultiq_theme");
    var prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    var dark = stored === "dark" || (stored == null && prefersDark);
    if (dark) document.documentElement.classList.add("dark");
  } catch (e) {}
})();
