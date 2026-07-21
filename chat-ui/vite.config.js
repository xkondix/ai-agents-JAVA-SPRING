import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Proxy for agents — bypasses CORS in dev
    proxy: {
      '/api/raw-agent':          { target: 'http://localhost:8090', rewrite: p => p.replace(/^\/api\/raw-agent/, ''),          changeOrigin: true },
      '/api/lc4j-agent-local':   { target: 'http://localhost:8082', rewrite: p => p.replace(/^\/api\/lc4j-agent-local/, ''),   changeOrigin: true },
      '/api/lc4j-agent-mcp':     { target: 'http://localhost:8083', rewrite: p => p.replace(/^\/api\/lc4j-agent-mcp/, ''),     changeOrigin: true },
      '/api/spring-agent-local': { target: 'http://localhost:8084', rewrite: p => p.replace(/^\/api\/spring-agent-local/, ''), changeOrigin: true },
      '/api/spring-agent-mcp':   { target: 'http://localhost:8085', rewrite: p => p.replace(/^\/api\/spring-agent-mcp/, ''),   changeOrigin: true },
      // Patterns Lab — mirror modules (same endpoints, different framework)
      '/api/patterns-lc4j':      { target: 'http://localhost:8087', rewrite: p => p.replace(/^\/api\/patterns-lc4j/, ''),      changeOrigin: true },
      '/api/patterns-spring':    { target: 'http://localhost:8088', rewrite: p => p.replace(/^\/api\/patterns-spring/, ''),    changeOrigin: true },
    }
  }
})
