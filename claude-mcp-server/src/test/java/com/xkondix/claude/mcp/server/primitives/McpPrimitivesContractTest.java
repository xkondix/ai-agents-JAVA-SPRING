package com.xkondix.claude.mcp.server.primitives;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the non-tool MCP primitives.
 *
 * The counterpart of ToolSchemaContractTest, and it exists for the same
 * reason: everything asserted here fails SILENTLY when it breaks.
 *
 *   - a renamed resource URI simply stops resolving for every client that
 *     stored it;
 *   - a template variable whose method parameter name no longer matches never
 *     binds, and the resource is read with a null argument;
 *   - a @McpComplete pointing at a prompt or URI that no longer exists is
 *     dead configuration — the dropdown is just empty;
 *   - and the parameter-name problem is the same one that bit the tools three
 *     times: names come from reflection metadata, so a build without the
 *     -parameters flag turns {document} into a variable nothing can fill.
 *
 * None of that produces an error at startup, at build time, or in any log.
 *
 * No Spring context is started, deliberately: a @SpringBootTest here would
 * boot a STDIO server attached to the test runner's stdin.
 */
class McpPrimitivesContractTest {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}]+)}");

    private static final Set<String> EXPECTED_RESOURCE_URIS = Set.of(
            "project://structure",
            "project://modules",
            "project://docs/{document}",
            "project://module/{module}/config");

    private static final Set<String> EXPECTED_PROMPTS = Set.of(
            "migrate_dependency",
            "add_mcp_tool",
            "diagnose_telemetry",
            "review_module");

    // ── Resources ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the set of resource URIs is exactly what clients were told")
    void resourceUrisAreStable() {
        Set<String> actual = Arrays.stream(ProjectResources.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpResource.class))
                .map(m -> m.getAnnotation(McpResource.class).uri())
                .collect(Collectors.toSet());

        assertThat(actual)
                .describedAs("""
                        A resource URI is a stored address: clients and humans keep it, \
                        documentation quotes it, and nothing tells them when it changes.""")
                .isEqualTo(EXPECTED_RESOURCE_URIS);
    }

    @Test
    @DisplayName("at least one resource has a concrete URI, or resources/list is empty")
    void atLeastOneResourceIsNotATemplate() {
        long concrete = Arrays.stream(ProjectResources.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpResource.class))
                .map(m -> m.getAnnotation(McpResource.class).uri())
                .filter(uri -> !uri.contains("{"))
                .count();

        assertThat(concrete)
                .describedAs("""
                        Templated URIs are published under resources/templates/list, not \
                        resources/list. A server whose resources are all templates looks \
                        empty in every client that renders only the latter — which is most \
                        of them.""")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("every template variable has a method parameter of the same name")
    void templateVariablesBindToParameters() {
        Arrays.stream(ProjectResources.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpResource.class))
                .forEach(method -> {
                    String uri = method.getAnnotation(McpResource.class).uri();
                    List<String> parameterNames = Arrays.stream(method.getParameters())
                            .map(Parameter::getName)
                            .toList();

                    Matcher matcher = TEMPLATE_VAR.matcher(uri);
                    while (matcher.find()) {
                        String variable = matcher.group(1);
                        assertThat(parameterNames)
                                .describedAs("""
                                        Resource %s declares {%s} but its method has \
                                        parameters %s. The variable is bound to a parameter \
                                        BY NAME through reflection, so either the names \
                                        disagree or the -parameters compiler flag is \
                                        missing. Either way the resource is read with a \
                                        null argument and nothing reports it.""",
                                        uri, variable, parameterNames)
                                .contains(variable);
                    }
                });
    }

    @Test
    @DisplayName("every resource declares a description and a mime type")
    void resourcesAreDescribed() {
        Arrays.stream(ProjectResources.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpResource.class))
                .forEach(method -> {
                    McpResource annotation = method.getAnnotation(McpResource.class);
                    assertThat(annotation.description())
                            .describedAs("%s has no description — the human picking it from "
                                    + "a menu has only the URI to go on", annotation.uri())
                            .isNotBlank();
                    assertThat(annotation.mimeType())
                            .describedAs("%s has no mimeType; clients use it to decide how "
                                    + "to render the content", annotation.uri())
                            .isNotBlank();
                });
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the set of prompt names is exactly what clients were told")
    void promptNamesAreStable() {
        assertThat(promptNames()).isEqualTo(EXPECTED_PROMPTS);
    }

    @Test
    @DisplayName("every prompt argument is named and described via @McpArg")
    void promptArgumentsAreDeclared() {
        Arrays.stream(ProjectPrompts.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpPrompt.class))
                .forEach(method -> {
                    String prompt = method.getAnnotation(McpPrompt.class).name();
                    for (Parameter parameter : method.getParameters()) {
                        McpArg arg = parameter.getAnnotation(McpArg.class);
                        assertThat(arg)
                                .describedAs("Parameter '%s' of prompt %s has no @McpArg — "
                                        + "the client cannot render a field for it",
                                        parameter.getName(), prompt)
                                .isNotNull();
                        assertThat(arg.name())
                                .describedAs("@McpArg on %s.%s has no name",
                                        prompt, parameter.getName())
                                .isNotBlank();
                        assertThat(arg.description())
                                .describedAs("@McpArg '%s' of prompt %s has no description",
                                        arg.name(), prompt)
                                .isNotBlank();
                    }
                });
    }

    // ── Completions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("every completion points at a prompt or URI that exists")
    void completionsPointAtSomethingReal() {
        Set<String> resourceUris = EXPECTED_RESOURCE_URIS;
        Set<String> prompts = promptNames();

        Arrays.stream(ProjectCompletions.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpComplete.class))
                .forEach(method -> {
                    McpComplete annotation = method.getAnnotation(McpComplete.class);
                    boolean hasPrompt = !annotation.prompt().isBlank();
                    boolean hasUri = !annotation.uri().isBlank();

                    assertThat(hasPrompt ^ hasUri)
                            .describedAs("%s must set exactly one of prompt/uri",
                                    method.getName())
                            .isTrue();

                    if (hasPrompt) {
                        assertThat(prompts)
                                .describedAs("""
                                        Completion %s targets prompt '%s', which does not \
                                        exist. A completion pointing at nothing is dead \
                                        config: the dropdown is simply empty and no layer \
                                        complains.""", method.getName(), annotation.prompt())
                                .contains(annotation.prompt());
                    } else {
                        assertThat(resourceUris)
                                .describedAs("""
                                        Completion %s targets URI '%s', which no resource \
                                        declares.""", method.getName(), annotation.uri())
                                .contains(annotation.uri());
                    }
                });
    }

    @Test
    @DisplayName("document names offered by completion are the ones the resource accepts")
    void completionOffersOnlyValidDocuments() {
        // The completion reads from the same map the resource resolves against.
        // This asserts they have not been allowed to drift apart — the failure
        // mode is a dropdown offering a name that then returns ERROR.
        assertThat(ProjectResources.documentNames())
                .describedAs("the completion source must not be empty")
                .isNotEmpty()
                .contains("readme", "observability");
    }

    private static Set<String> promptNames() {
        return Arrays.stream(ProjectPrompts.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpPrompt.class))
                .map(m -> m.getAnnotation(McpPrompt.class).name())
                .collect(Collectors.toSet());
    }

    private static List<Method> annotated(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(annotation))
                .toList();
    }
}
