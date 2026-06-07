/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        'lc4j':        '#6366F1',
        'lc4j-mcp':    '#10B981',
        'spring':      '#F59E0B',
        'spring-mcp':  '#A855F7',
      }
    }
  },
  plugins: []
}
