package com.xkondix.claude.mcp.server.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wire contract this server publishes to Claude Desktop.
 *
 * WHY THIS TEST EXISTS. Every serious failure in this module was silent: the
 * build passed, the context started, the tools appeared in the client, and the
 * log contained no error — yet the schema advertised arg0/arg1 instead of
 * path/content, so every argument arrived as null and the tools answered about
 * the whole project instead of the file that was asked for. Three separate
 * debugging sessions went into rediscovering that.
 *
 * The root cause is structural, not accidental: @McpToolParam carries only
 * `description` and `required` — there is NO `name` attribute. Parameter names
 * can therefore come from one place only, the reflection metadata emitted by
 * javac -parameters. spring-boot-starter-parent turns that flag on; the
 * project's own parent did not. Nothing in Spring AI can warn about it, because
 * from the framework's point of view arg0 is a perfectly valid parameter name.
 *
 * So the check has to live here. These assertions run without a Spring context
 * on purpose — the failure mode is a compiler flag, and a plain reflection test
 * pins it down in milliseconds with no chance of a STDIO server trying to
 * attach to the test runner's stdin.
 */
@DisplayName("MCP tool schema contract")
class ToolSchemaContractTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "read_file", "list_files", "get_project_structure", "search_in_files",
            "write_file", "create_file", "move_file", "move_directory", "delete_file");

    private static List<Method> toolMethods() {
        return Arrays.stream(CodeToolsService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .toList();
    }

    @Test
    @DisplayName("exposes exactly the nine documented tools")
    void exposesExactlyTheDocumentedTools() {
        Set<String> declared = toolMethods().stream()
                .map(m -> m.getAnnotation(McpTool.class).name())
                .collect(Collectors.toSet());

        assertThat(declared)
                .as("A tool silently disappearing from the client is the failure "
                        + "this assertion is meant to catch")
                .isEqualTo(EXPECTED_TOOLS);
    }

    @Test
    @DisplayName("compiled with -parameters, so no argument is published as argN")
    void parameterNamesSurviveCompilation() {
        for (Method method : toolMethods()) {
            for (Parameter parameter : method.getParameters()) {

                assertThat(parameter.isNamePresent())
                        .as("%s(%s): compiled WITHOUT -parameters. The JSON schema will "
                                        + "advertise argN, the client will send the wrong keys, "
                                        + "and every argument will arrive as null with no error "
                                        + "anywhere. Check that the parent POM is "
                                        + "spring-boot-starter-parent.",
                                method.getName(), parameter.getName())
                        .isTrue();

                assertThat(parameter.getName())
                        .as("%s: parameter name leaked as a synthetic argN",
                                method.getName())
                        .doesNotMatch("arg\\d+");
            }
        }
    }

    @Test
    @DisplayName("every tool name is declared explicitly, not derived from the method")
    void toolNamesAreDeclaredExplicitly() {
        for (Method method : toolMethods()) {
            assertThat(method.getAnnotation(McpTool.class).name())
                    .as("%s relies on name derivation — renaming the method would "
                            + "silently rename the tool for every client", method.getName())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("every tool parameter is documented for the model")
    void everyParameterIsDescribed() {
        for (Method method : toolMethods()) {
            for (Parameter parameter : method.getParameters()) {
                McpToolParam annotation = parameter.getAnnotation(McpToolParam.class);

                assertThat(annotation)
                        .as("%s(%s) has no @McpToolParam — the model gets a bare type "
                                        + "with no hint about what to put in it",
                                method.getName(), parameter.getName())
                        .isNotNull();

                assertThat(annotation.description()).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("read_file publishes 'path', which is the exact regression that kept coming back")
    void readFilePublishesPathParameter() {
        Method readFile = toolMethods().stream()
                .filter(m -> m.getAnnotation(McpTool.class).name().equals("read_file"))
                .findFirst()
                .orElseThrow();

        assertThat(readFile.getParameters()[0].getName()).isEqualTo("path");
    }

    @Test
    @DisplayName("optional arguments use boxed types so a missing value cannot blow up reflection")
    void optionalArgumentsAreNullable() {
        for (Method method : toolMethods()) {
            for (Parameter parameter : method.getParameters()) {
                McpToolParam annotation = parameter.getAnnotation(McpToolParam.class);
                if (annotation != null && !annotation.required()) {
                    assertThat(parameter.getType().isPrimitive())
                            .as("%s(%s) is optional but primitive — an absent argument "
                                            + "arrives as null and fails before the method body runs",
                                    method.getName(), parameter.getName())
                            .isFalse();
                }
            }
        }
    }
}
