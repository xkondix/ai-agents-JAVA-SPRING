# Code MCP Server v2 (Spring AI starter)

MCP Server do edycji kodu projektu ai-agents-JAVA-SPRING.
Uzywa oficjalnego podejscia Spring AI MCP starter.

## Budowanie JAR

```bash
cd code-mcp-server
mvn clean package -DskipTests
# JAR: target/code-mcp-server-1.0.0-SNAPSHOT.jar
```

## Podlaczenie do Claude Desktop

**Plik:** `C:\Users\konra\AppData\Roaming\Claude\claude_desktop_config.json`

Dodaj do istniejacego JSON (w sekcji mcpServers):

```json
"ai-agents-code": {
  "command": "java",
  "args": [
    "-Dspring.ai.mcp.server.transport=STDIO",
    "-Dlogging.file.name=C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\logs\\code-mcp-server.log",
    "-jar",
    "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\code-mcp-server\\target\\code-mcp-server-1.0.0-SNAPSHOT.jar"
  ]
}
```

Zrestartuj Claude Desktop — zobaczysz ikone mlotka w oknie czatu.

## Dostepne narzedzia

| Narzedzie | Approval | Opis |
|---|---|---|
| `read_file` | Brak | Czyta plik |
| `list_files` | Brak | Listuje katalog |
| `get_project_structure` | Brak | Drzewo projektu |
| `search_in_files` | Brak | Szuka tekstu w plikach |
| `write_file` | **Wymagane** | Nadpisuje plik |
| `create_file` | **Wymagane** | Tworzy nowy plik |
| `move_file` | **Wymagane** | Przenosi/rename |
| `delete_file` | **Podwojne** | Usuwa (nieodwracalne) |

## Approval Flow

Gdy Claude chce wykonac wrazliwa operacje:
1. Serwer loguje: `[APPROVAL REQUIRED] id=abc123 ...`
2. W logu widac URL do zatwierdzenia
3. Zatwierdz przez REST lub Chat UI:

```bash
POST http://localhost:8086/approvals/{id}/approve
POST http://localhost:8086/approvals/{id}/reject
GET  http://localhost:8086/approvals
```

## Logi

Logi ida do pliku (nie stdout — stdout jest zarezerwowany dla JSON-RPC):
```
C:\Users\konra\Desktop\ai-agents-JAVA-SPRING\logs\code-mcp-server.log
```

## Dodanie do projektu Maven

W glownym `pom.xml`:
```xml
<module>code-mcp-server</module>
```
