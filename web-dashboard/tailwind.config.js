/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./index.html", "./src/**/*.rs"],
  theme: {
    extend: {
      colors: {
        "ultiq-indigo": "#2A1B6E",
        "ultiq-red": "#D9474C",
        "ultiq-yellow": "#FFC83D",
        "ultiq-cream": "#FFF4E6",
        "ultiq-blue": "#A8C5E8",
      },
      fontFamily: {
        sans: ["system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
      },
    },
  },
  plugins: [],
};
