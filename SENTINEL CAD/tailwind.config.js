/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./index.html",
    "./central/**/*.html",
    "./comandancia/**/*.html"
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Segoe UI"', 'Tahoma', 'Geneva', 'Verdana', 'sans-serif'],
        outfit: ['Outfit', 'sans-serif'],
      },
      colors: {
        bomberos: {
          red: '#B91C1C',
          dark: '#7F1D1D',
          light: '#FEF2F2'
        },
        brand: {
          red: '#EF4444',
          darkRed: '#B91C1C',
          dark: '#070913',
          slate: '#1E293B',
          card: '#131C2E',
          gold: '#F59E0B'
        },
        primary: {
          red: '#ef4444',
          green: '#10b981',
          blue: '#3b82f6'
        }
      }
    },
  },
  plugins: [],
}
