package com.xkondix.multimodal.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool schema is a wire contract, and nothing else in the build guards it.
 *
 * WHY THIS EXISTS. In this module the tool NAMES are the routing logic: the
 * model picks a capability by calling a method by name, and the system prompt
 * refers to those names in plain text. Rename generate_music to
 * generateMusic and everything still compiles, still starts, and quietly
 * stops working — the model asks for a tool that is no longer there, or worse,
 * reads a prompt describing tools that do not match the schema it was given.
 *
 * That failure mode is the same one mcp-server already hit three times, which
 * is why ToolSchemaContractTest exists there. This is its counterpart.
 *
 * WHAT IS ASSERTED, AND WHY EACH ONE:
 *
 *   - the exact set of tool names, because the prompt hardcodes them;
 *   - that every @ToolParam has a real name rather than arg0, because
 *     parameter names come from the -parameters compiler flag and NOTHING
 *     ELSE. Without it the schema publishes arg0/arg1 and every argument
 *     arrives as null, with no error anywhere. That flag lives in the root
 *     pom and is one careless edit away from disappearing;
 *   - that every tool description mentions ERROR handling, because the retry
 *     budget only works if the model is also told to stop. The budget is the
 *     hard limit; the description is what keeps it from being reached.
 *
 * VideoTools is deliberately NOT included: the bean only exists when
 * multimodal.video.enabled=true, so asserting on it would make the test
 * depend on configuration. Its schema is checked by the same rules if it is
 * ever switched on in a profile that runs tests.
 */
class ToolSchemaContractTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "analyze_image", "generate_image", "speak_text", "generate_music");

    private static final List<Class<?>> TOOL_CLASSES =
            List.of(MultimodalTools.class, MusicTools.class);

    @Test
    @DisplayName("the set of tool names is exactly what the system prompt describes")
    void toolNamesAreStable() {
        assertThat(toolMethods().keySet())
                .describedAs("""
                        The router's system prompt lists these names in prose. Renaming a \
                        method here without updating MultimodalAgentService leaves the model \
                        reading about tools it cannot call — silently.""")
                .isEqualTo(EXPECTED_TOOLS);
    }

    @Test
    @DisplayName("every tool parameter has a real name, not arg0")
    void parameterNamesSurviveCompilation() {
        toolMethods().forEach((name, method) -> {
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.getName())
                        .describedAs("""
                                Parameter of %s resolved to '%s'. That means the -parameters \
                                compiler flag is missing from the build: @ToolParam has no \
                                name attribute, so reflection metadata is the ONLY source of \
                                argument names. Without it the JSON schema publishes \
                                arg0/arg1 and every argument arrives as null, with no error \
                                in any log.""", name, parameter.getName())
                        .doesNotMatch("arg\\d+");
            }
        });
    }

    @Test
    @DisplayName("every tool parameter carries a description for the model to read")
    void parametersAreDocumentedForTheModel() {
        toolMethods().forEach((name, method) -> {
            for (Parameter parameter : method.getParameters()) {
                ToolParam annotation = parameter.getAnnotation(ToolParam.class);
                assertThat(annotation)
                        .describedAs("%s.%s has no @ToolParam — the model gets a bare type "
                                + "and guesses what to put in it", name, parameter.getName())
                        .isNotNull();
                assertThat(annotation.description())
                        .describedAs("%s.%s has an empty @ToolParam description",
                                name, parameter.getName())
                        .isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("every tool tells the model not to retry after an ERROR")
    void descriptionsCarryTheStopInstruction() {
        toolMethods().forEach((name, method) -> {
            String description = method.getAnnotation(Tool.class).description().toLowerCase();
            assertThat(description)
                    .describedAs("""
                            %s does not mention what to do on ERROR. ToolCallingAdvisor has no \
                            failure semantics — a tool that answers "ERROR: …" has, as far as \
                            the loop is concerned, answered. One request once produced over a \
                            hundred calls to a single failing tool. MediaCollector's budget is \
                            the hard stop; this sentence is what keeps it from being hit.""",
                            name)
                    .contains("error");
        });
    }

    /** Every @Tool method across the tool beans, keyed by the name the model sees. */
    private static Map<String, Method> toolMethods() {
        Map<String, Method> found = new LinkedHashMap<>();
        for (Class<?> type : TOOL_CLASSES) {
            Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Tool.class))
                    .forEach(m -> found.put(m.getName(), m));
        }
        return found;
    }

    @Test
    @DisplayName("no two tool beans declare the same tool name")
    void toolNamesDoNotCollide() {
        List<String> all = TOOL_CLASSES.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .toList();

        assertThat(all)
                .describedAs("""
                        Tools from several beans share one flat namespace — the model sees a \
                        single list. A duplicate name means one of them is unreachable, and \
                        which one depends on registration order.""")
                .doesNotHaveDuplicates();

        assertThat(all.stream().collect(Collectors.toSet())).hasSize(all.size());
    }
}
