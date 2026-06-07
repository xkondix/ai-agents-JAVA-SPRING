import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Proxy dla agentow - omija CORS w dev
    proxy: {
      '/api/lc4j-agent':    { target: 'http://localhost:8082', rewrite: p => p.replace(/^\/api\/lc4j-agent/, ''),    changeOrigin: true },
      '/api/lc4j-mcp':      { target: 'http://localhost:8083', rewrite: p => p.replace(/^\/api\/lc4j-mcp/, ''),      changeOrigin: true },
      '/api/spring-agent':  { target: 'http://localhost:8084', rewrite: p => p.replace(/^\/api\/spring-agent/, ''),  changeOrigin: true },
      '/api/spring-mcp':    { target: 'http://localhost:8085', rewrite: p => p.replace(/^\/api\/spring-mcp/, ''),    changeOrigin: true },
    }
  }
})
