/**
 * Agent configuration.
 * proxyPath must match keys in vite.config.js proxy.
 * healthPath uses Spring Boot Actuator.
 *
 * ── ONE ENTRY PER RUNNING PROCESS ──────────────────────────────────────────
 *
 * Two modules expose more than one chat endpoint, and in both cases the extra
 * endpoints ARE the point of the module:
 *
 *   langchain4j-agent-local (8082) — the same agent with the loop written by
 *     hand, and with the loop hidden inside AiServices
 *   spring-ai-agent-local (8084) — the same call through three different
 *     advisor chains
 *
 * For a while LangChain4j had two entries here, which was wrong in a way
 * worth recording: they shared a port, a process and a health endpoint, so a
 * stopped module painted TWO red tiles and looked like two failures.
 *
 * A tile is a process. Endpoints inside one process are `variants` — the
 * selector renders them as a row inside the tile, and useAgentHealth still
 * polls each module exactly once.
 *
 * An agent with no `variants` is a single-endpoint module and uses chatPath
 * directly; AgentSelector hands the chosen variant's chatPath to chatApi, so
 * nothing downstream needs to know the difference.
 *
 * ── "RAW" MEANT TWO DIFFERENT THINGS ───────────────────────────────────────
 *
 * raw-agent (8090) has no AI framework at all: hand-built JSON, hand-parsed
 * tool_calls, java.net.http. The LangChain4j /raw endpoint also writes its
 * own loop, but on top of ChatModel, ToolSpecification and
 * ToolExecutionResultMessage — the framework is very much present, it just
 * is not running the loop. Both used to be labelled "Raw", which made them
 * look like the same thing with a different logo.
 */
export const AGENTS = [
  {
    id:          'raw-agent',
    name:        'No Framework Agent',
    description: 'Pure loop, no LangChain4j, no Spring AI, just HTTP',
    color:       '#EF4444',
    colorClass:  'bg-red-500',
    borderClass: 'border-red-500',
    textClass:   'text-red-400',
    proxyPath:   '/api/raw-agent',
    chatPath:    '/api/raw-agent/api/v1/agent/chat',
    healthPath:  '/api/raw-agent/actuator/health',
    chatField:   'message',
    icon:        'RAW',
  },
  {
    id:          'lc4j-agent-local',
    name:        'LangChain4j Agent Local',
    description: 'Raw loop + AiServices + local @Tool',
    color:       '#6366F1',
    colorClass:  'bg-indigo-500',
    borderClass: 'border-indigo-500',
    textClass:   'text-indigo-400',
    proxyPath:   '/api/lc4j-agent-local',
    chatPath:    '/api/lc4j-agent-local/api/v1/agent/aiservices',
    healthPath:  '/api/lc4j-agent-local/actuator/health',
    chatField:   'message',
    icon:        'LC4J',
    // One process, two endpoints. The first is the comparison this module
    // exists for; without it, /api/v1/agent/raw was unreachable from the UI.
    variants: [
      {
        id:       'raw-loop',
        label:    'Raw loop',
        hint:     'ChatModel called directly, the while loop is ours',
        chatPath: '/api/lc4j-agent-local/api/v1/agent/raw',
      },
      {
        id:       'aiservices',
        label:    'AiServices',
        hint:     'Loop hidden behind a proxy',
        chatPath: '/api/lc4j-agent-local/api/v1/agent/aiservices',
      },
    ],
  },
  {
    id:          'lc4j-agent-mcp',
    name:        'LangChain4j Agent MCP',
    description: 'Orchestrator + MCP client over Streamable HTTP',
    color:       '#10B981',
    colorClass:  'bg-emerald-500',
    borderClass: 'border-emerald-500',
    textClass:   'text-emerald-400',
    proxyPath:   '/api/lc4j-agent-mcp',
    chatPath:    '/api/lc4j-agent-mcp/api/v1/mcp/chat',
    healthPath:  '/api/lc4j-agent-mcp/actuator/health',
    chatField:   'message',
    icon:        'MCP',
  },
  {
    id:          'spring-agent-local',
    name:        'Spring AI Agent Local',
    description: 'ChatClient + Advisors + local @Tool',
    color:       '#F59E0B',
    colorClass:  'bg-amber-500',
    borderClass: 'border-amber-500',
    textClass:   'text-amber-400',
    proxyPath:   '/api/spring-agent-local',
    chatPath:    '/api/spring-agent-local/api/v1/agent/chat',
    healthPath:  '/api/spring-agent-local/actuator/health',
    chatField:   'message',
    icon:        'SAI',
    // Same model, same tools, same question. Only the advisor chain changes,
    // so anything that differs in the logs, the trace or the stored
    // conversation has exactly one cause.
    //
    // ToolCallingAdvisor is auto-registered in all three; what moves is who
    // sits inside the loop with it. Order decides that, and the numbers are
    // counter-intuitive: HIGHEST_PRECEDENCE is Integer.MIN_VALUE, so a plain
    // 0 is LARGER than the tool advisor's MIN_VALUE+300 and therefore inside.
    variants: [
      {
        id:       'minimal',
        label:    'Default',
        hint:     'Memory outside the loop, stores the final answer only',
        chatPath: '/api/spring-agent-local/api/v1/agent/chat',
      },
      {
        id:       'advisors',
        label:    'Custom advisor',
        hint:     'InspectionAdvisor runs once per iteration, inside the loop',
        chatPath: '/api/spring-agent-local/api/v1/agent/chat/advisors',
      },
      {
        id:       'memory-in-loop',
        label:    'Memory in loop',
        hint:     'Memory raised above the tool advisor, stores every tool call',
        chatPath: '/api/spring-agent-local/api/v1/agent/chat/memory-in-loop',
      },
    ],
  },
  {
    id:          'spring-agent-mcp',
    name:        'Spring AI Agent MCP',
    description: 'ChatClient + MCP client, W3C trace context propagated',
    color:       '#A855F7',
    colorClass:  'bg-purple-500',
    borderClass: 'border-purple-500',
    textClass:   'text-purple-400',
    proxyPath:   '/api/spring-agent-mcp',
    chatPath:    '/api/spring-agent-mcp/api/v1/mcp/chat',
    healthPath:  '/api/spring-agent-mcp/actuator/health',
    chatField:   'message',
    icon:        'MCP',
  },
]
