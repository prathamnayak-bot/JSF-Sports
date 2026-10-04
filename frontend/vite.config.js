import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // forward API calls to the Spring Boot backend during development
    // (set BACKEND_URL if your backend runs on another port, e.g. SERVER_PORT=8081)
    proxy: {
      '/api': process.env.BACKEND_URL || 'http://localhost:8080',
    },
  },
})
