/**
 * Konfiguracja agentow.
 * proxyPath musi zgadzac sie z kluczami w vite.config.js proxy.
 * healthPath uzywa Spring Boot Actuator.
 */
export const AGENTS = [
  {
    id:          'lc4j-agent',
    name:        'LangChain4j Agent',
    description: 'Raw loop + AiServices + Memory',
    color:       '#6366F1',
    colorClass:  'bg-indigo-500',
    borderClass: 'border-indigo-500',
    textClass:   'text-indigo-400',
    proxyPath:   '/api/lc4j-agent',
    chatPath:    '/api/v1/agent/aiservices',
    healthPath:  '/api/lc4j-agent/actuator/health',
    chatField:   'message',      // pole w request body
    icon:        'LC4J',
  },
  {
    id:          'lc4j-mcp',
    name:        'LangChain4j MCP',
    description: 'Orchestrator + MCP Client (Java + Python)',
    color:       '#10B981',
    colorClass:  'bg-emerald-500',
    borderClass: 'border-emerald-500',
    textClass:   'text-emerald-400',
    proxyPath:   '/api/lc4j-mcp',
    chatPath:    '/api/v1/mcp/chat',
    healthPath:  '/api/lc4j-mcp/actuator/health',
    chatField:   'message',
    icon:        'MCP',
  },
  {
    id:          'spring-agent',
    name:        'Spring AI Agent',
    description: 'ChatClient + Advisors + FunctionCallback',
    color:       '#F59E0B',
    colorClass:  'bg-amber-500',
    borderClass: 'border-amber-500',
    textClass:   'text-amber-400',
    proxyPath:   '/api/spring-agent',
    chatPath:    '/api/v1/agent/chat',
    healthPath:  '/api/spring-agent/actuator/health',
    chatField:   'message',
    icon:        'SAI',
  },
  {
    id:          'spring-mcp',
    name:        'Spring AI MCP',
    description: 'Spring AI MCP Client + Orchestrator',
    color:       '#A855F7',
    colorClass:  'bg-purple-500',
    borderClass: 'border-purple-500',
    textClass:   'text-purple-400',
    proxyPath:   '/api/spring-mcp',
    chatPath:    '/api/v1/mcp/chat',
    healthPath:  '/api/spring-mcp/actuator/health',
    chatField:   'message',
    icon:        'MCP',
  },
]
