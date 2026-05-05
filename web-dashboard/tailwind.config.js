/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./index.html", "./src/**/*.rs"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        "ultiq-indigo": "#2A1B6E",
        "ultiq-red": "#D9474C",
        "ultiq-yellow": "#FFC83D",
        "ultiq-cream": "#FFF4E6",
        "ultiq-blue": "#A8C5E8",
        // Dark-mode neutrals (kept here so utilities like bg-ultiq-night-* are
        // available throughout, and we have one place to tune the dark palette).
        "ultiq-night": {
          900: "#0E0A24", // app background
          800: "#1A1244", // raised surfaces / cards
          700: "#2A1F58", // input borders, dividers
          600: "#3A2D70", // hover surfaces
        },
      },
      fontFamily: {
        sans: ["system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
      },
    },
  },
  plugins: [],
};
