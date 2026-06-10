import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Proxy for agents — bypasses CORS in dev
    proxy: {
      '/api/lc4j-agent-local': { target: 'http://localhost:8082', rewrite: p => p.replace(/^\/api\/lc4j-agent-local/, ''), changeOrigin: true },
      '/api/lc4j-agent-mcp':   { target: 'http://localhost:8083', rewrite: p => p.replace(/^\/api\/lc4j-agent-mcp/, ''),   changeOrigin: true },
      '/api/spring-agent-local': { target: 'http://localhost:8084', rewrite: p => p.replace(/^\/api\/spring-agent-local/, ''), changeOrigin: true },
      '/api/spring-agent-mcp':   { target: 'http://localhost:8085', rewrite: p => p.replace(/^\/api\/spring-agent-mcp/, ''),   changeOrigin: true },
    }
  }
})
