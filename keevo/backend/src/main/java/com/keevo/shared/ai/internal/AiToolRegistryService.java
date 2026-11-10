package com.keevo.shared.ai.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.ai.AiToolResult;
import com.keevo.shared.ai.AiToolResult.Outcome;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ErrorMessages;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.application.ModulePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Story 21.8 — {@link AiToolRegistry}: discovers, once every singleton exists, the {@code @Tool} methods of the
 * {@code *McpTools} beans of an {@code adapter.in.mcp} package (Registry), and calls them through the bean's Spring
 * proxy (Proxy: role, permission and module gates are the REST interceptors).
 *
 * <p><b>Listing</b> (O(tools), in memory): the {@code @PreAuthorize} expression is evaluated like Spring method security
 * ({@link DefaultMethodSecurityExpressionHandler}), the permissions against {@link ModulePermissions} (cached map), the
 * module against {@link EntitlementService#enabledModules}, read once per listing.
 *
 * <p><b>Call</b>: the same three checks first (a refusal never invokes the tool and is answered with the REST
 * message), then the method through {@link MethodToolCallback} (JSON arguments → parameters), the result serialised
 * with the application {@link ObjectMapper} and capped at {@value #MAX_RESULT_CHARS} characters, the failure mapped to
 * a French message, one audit row ({@link AiToolCallAudit}).
 */
@Component
public class AiToolRegistryService implements AiToolRegistry, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistryService.class);

    /** Above this size a result is refused: a model context is not a data export. */
    static final int MAX_RESULT_CHARS = 64_000;

    static final String FORBIDDEN_MESSAGE = "Vous n'avez pas les droits nécessaires";
    static final String NO_SESSION_MESSAGE = "Session requise : connectez-vous à votre entreprise.";
    static final String UNKNOWN_TOOL_MESSAGE = "Outil inconnu : %s";
    static final String INVALID_ARGUMENTS_MESSAGE =
            "Paramètres invalides : vérifiez leur format (dates AAAA-MM-JJ, nombres sans espace).";
    static final String TOO_LARGE_MESSAGE =
            "Résultat trop volumineux : précisez la période, la boutique ou un filtre.";
    /** Audit name of a call without tool name (the column is NOT NULL). */
    static final String UNNAMED_TOOL = "?";
    static final String REFUSED_MESSAGE = "La demande n'a pas pu aboutir.";
    static final String ERROR_MESSAGE = "Une erreur est survenue. Réessayez dans quelques instants.";

    /** Package suffix and bean name suffix a tool bean must have (architecture rule 10, Story 21.8). */
    public static final String TOOL_PACKAGE_MARKER = ".adapter.in.mcp";
    public static final String TOOL_BEAN_SUFFIX = "McpTools";

    private final ListableBeanFactory beans;
    private final ObjectProvider<EntitlementService> entitlements;
    private final ObjectProvider<ModulePermissions> permissions;
    private final AiToolCallAudit audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final DefaultMethodSecurityExpressionHandler expressions = new DefaultMethodSecurityExpressionHandler();

    /** name → tool, sorted; replaced once at startup. */
    private volatile Map<String, Entry> tools = Map.of();

    record Entry(AiToolDescriptor descriptor, ToolDefinition definition, MethodToolCallback invoker, Object bean,
                 Method method, Expression roleRule) {
    }

    public AiToolRegistryService(ListableBeanFactory beans, ObjectProvider<EntitlementService> entitlements,
                                 ObjectProvider<ModulePermissions> permissions, AiToolCallAudit audit,
                                 ObjectMapper mapper, Clock clock) {
        this.beans = beans;
        this.entitlements = entitlements;
        this.permissions = permissions;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ── discovery ───────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Entry> found = new TreeMap<>();
        for (String name : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(name, false);
            if (type == null) {
                continue;
            }
            Class<?> user = ClassUtils.getUserClass(type);
            if (!isToolBeanType(user)) {
                continue;
            }
            register(beans.getBean(name), user, found);
        }
        tools = Collections.unmodifiableMap(found);
        log.info("AI tool registry: {} tools", found.size());
    }

    /** Whether a class is a tool bean by convention ({@code *McpTools} in an {@code adapter.in.mcp} package). */
    public static boolean isToolBeanType(Class<?> type) {
        return type.getSimpleName().endsWith(TOOL_BEAN_SUFFIX)
                && (type.getPackageName() + ".").contains(TOOL_PACKAGE_MARKER + ".");
    }

    /** Registers every {@code @Tool} method of {@code bean} (visible for tests). */
    void register(Object bean, Class<?> user, Map<String, Entry> into) {
        List<Method> methods = Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(user,
                        m -> AnnotationUtils.findAnnotation(m, Tool.class) != null))
                .sorted(Comparator.comparing(Method::getName)).toList();
        for (Method method : methods) {
            ToolDefinition definition = ToolDefinitions.from(method);
            if (into.containsKey(definition.name())) {
                throw new IllegalStateException("Duplicate AI tool name '" + definition.name() + "' ("
                        + user.getSimpleName() + "#" + method.getName() + " and "
                        + into.get(definition.name()).descriptor().handler() + ")");
            }
            RequiresEntitlement gate = gateOf(method, user);
            RequiresPermission permission = permissionOf(method, user);
            PreAuthorize role = roleOf(method, user);
            MethodToolCallback invoker = MethodToolCallback.builder()
                    .toolDefinition(definition)
                    .toolMethod(method)
                    .toolObject(bean)
                    .toolCallResultConverter((result, type) -> serialise(result))
                    .build();
            AiToolDescriptor descriptor = new AiToolDescriptor(definition.name(), definition.description(),
                    definition.inputSchema(), gate != null ? gate.value() : null,
                    permission != null ? List.of(permission.value()) : List.of(),
                    role != null ? role.value() : "", user.getSimpleName() + "#" + method.getName());
            Expression roleRule = role != null ? expressions.getExpressionParser().parseExpression(role.value()) : null;
            into.put(definition.name(), new Entry(descriptor, definition, invoker, bean, method, roleRule));
        }
    }

    /**
     * Same resolution as the other gates (meta-annotations and inherited declarations included): the method's
     * annotation, else its declaring class'.
     */
    static RequiresEntitlement gateOf(Method method, Class<?> user) {
        RequiresEntitlement found = AnnotationUtils.findAnnotation(method, RequiresEntitlement.class);
        return found != null ? found : AnnotationUtils.findAnnotation(user, RequiresEntitlement.class);
    }

    static RequiresPermission permissionOf(Method method, Class<?> user) {
        RequiresPermission found = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
        return found != null ? found : AnnotationUtils.findAnnotation(user, RequiresPermission.class);
    }

    static PreAuthorize roleOf(Method method, Class<?> user) {
        PreAuthorize found = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
        return found != null ? found : AnnotationUtils.findAnnotation(user, PreAuthorize.class);
    }

    // ── listing ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<AiToolDescriptor> allTools() {
        return tools.values().stream().map(Entry::descriptor).toList();
    }

    @Override
    public List<AiToolDescriptor> allowedTools() {
        Authentication auth = CurrentActor.authentication();
        String tenant = TenantContext.getCurrentTenant();
        if (!hasSession(auth, tenant)) {
            return List.of();
        }
        Set<String> held = permissions.getObject().permissionsOf(ModulePermissions.rolesOf(auth));
        // one read of the module states for every tool (also off the request thread: MCP transport, async client)
        Set<String> enabled = entitlements.getObject().enabledModules(tenant);
        List<AiToolDescriptor> allowed = new ArrayList<>();
        for (Entry entry : tools.values()) {
            AiToolDescriptor d = entry.descriptor();
            if (roleAllows(entry, auth) && held.containsAll(d.permissions())
                    && (d.module() == null || enabled.contains(d.module()))) {
                allowed.add(d);
            }
        }
        return allowed;
    }

    @Override
    public List<ToolCallback> allowedToolCallbacks(AiToolChannel channel) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AiToolDescriptor d : allowedTools()) {
            callbacks.add(new KeevoToolCallback(tools.get(d.name()).definition(), this,
                    channel == null ? AiToolChannel.INTERNAL : channel, mapper));
        }
        return callbacks;
    }

    private boolean roleAllows(Entry entry, Authentication auth) {
        if (entry.roleRule() == null) {
            return true;
        }
        EvaluationContext context = expressions.createEvaluationContext(() -> auth,
                new SimpleMethodInvocation(entry.bean(), entry.method()));
        return ExpressionUtils.evaluateAsBoolean(entry.roleRule(), context);
    }

    private static boolean hasSession(Authentication auth, String tenant) {
        return auth != null && auth.isAuthenticated() && CurrentActor.actorIdOrNull() != null
                && tenant != null && !tenant.isBlank();
    }

    // ── call ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public AiToolResult call(String toolName, String jsonArguments, AiToolChannel channel, UUID accessTokenId) {
        long start = System.nanoTime();
        if (channel == null) {
            channel = AiToolChannel.INTERNAL;
        }
        Authentication auth = CurrentActor.authentication();
        String tenant = TenantContext.getCurrentTenant();
        if (!hasSession(auth, tenant)) {
            // no company bound: nothing to audit against, nothing invoked
            return AiToolResult.failure(Outcome.REFUSED, "FORBIDDEN", NO_SESSION_MESSAGE);
        }
        AiToolResult result = invoke(toolName, jsonArguments, auth, tenant);
        record(tenant, toolName, channel, result, start, accessTokenId);
        return result;
    }

    private AiToolResult invoke(String toolName, String jsonArguments, Authentication auth, String tenant) {
        Entry entry = toolName == null ? null : tools.get(toolName);
        if (entry == null) {
            return AiToolResult.failure(Outcome.INVALID, "VALIDATION_ERROR",
                    String.format(UNKNOWN_TOOL_MESSAGE, toolName));
        }
        try {
            AiToolResult refusal = precheck(entry, auth, tenant);
            if (refusal != null) {
                return refusal;
            }
            String arguments = jsonArguments == null || jsonArguments.isBlank() ? "{}" : jsonArguments;
            String json;
            try {
                json = entry.invoker().call(arguments);
            } catch (ToolExecutionException e) {
                // thrown by the method itself (or a gate of its proxy), or by the argument conversion
                return failureOf(entry, e.getCause() != null ? e.getCause() : e);
            } catch (ResultNotSerialisable e) {
                return failureOf(entry, e);
            } catch (Exception e) {
                // not wrapped by MethodToolCallback: the JSON → parameters conversion (Jackson exceptions may surface
                // undeclared) — an invalid argument; anything else is a bug of the tool or its proxy, never hidden as
                // a model mistake (21.4.2 review)
                if (conversionFailure(e)) {
                    return AiToolResult.failure(Outcome.INVALID, "VALIDATION_ERROR", INVALID_ARGUMENTS_MESSAGE);
                }
                return failureOf(entry, e);
            }
            if (json != null && json.length() > MAX_RESULT_CHARS) {
                return AiToolResult.failure(Outcome.INVALID, "RESULT_TOO_LARGE", TOO_LARGE_MESSAGE);
            }
            return AiToolResult.ok(json);
        } catch (RuntimeException e) {
            try {
                return failureOf(entry, e);
            } catch (RuntimeException mapping) {
                return AiToolResult.failure(Outcome.ERROR, "INTERNAL_ERROR", ERROR_MESSAGE); // never throws
            }
        }
    }

    /** An exception of the JSON → parameters conversion (an invalid argument from the model). */
    static boolean conversionFailure(Throwable e) {
        return e instanceof JsonProcessingException || e instanceof IllegalArgumentException
                || e instanceof java.time.DateTimeException || e instanceof ArithmeticException
                || e instanceof org.springframework.core.convert.ConversionException
                || e.getCause() instanceof JsonProcessingException;
    }

    /** Role, then permission, then module — the order of the REST interceptors. {@code null} = allowed. */
    private AiToolResult precheck(Entry entry, Authentication auth, String tenant) {
        AiToolDescriptor d = entry.descriptor();
        if (!roleAllows(entry, auth)) {
            return AiToolResult.failure(Outcome.REFUSED, "FORBIDDEN", FORBIDDEN_MESSAGE);
        }
        if (!d.permissions().isEmpty() && !permissions.getObject()
                .permissionsOf(ModulePermissions.rolesOf(auth)).containsAll(d.permissions())) {
            return AiToolResult.failure(Outcome.REFUSED, "FORBIDDEN", FORBIDDEN_MESSAGE);
        }
        if (d.module() != null) {
            entitlements.getObject().assertEnabled(tenant, d.module()); // DomainException → failureOf
        }
        return null;
    }

    private AiToolResult failureOf(Entry entry, Throwable cause) {
        if (cause instanceof DomainException e) {
            String code = e.getDomainCode();
            // never the exception's own (technical, possibly English) text
            String message = ErrorMessages.userMessage(code, e.getUserMessage(), REFUSED_MESSAGE, e.getDetails());
            boolean refused = code != null && ("FORBIDDEN".equals(code) || code.startsWith("MODULE_"));
            return AiToolResult.failure(refused ? Outcome.REFUSED : Outcome.INVALID, code, message);
        }
        if (cause instanceof AuthorizationDeniedException) {
            return AiToolResult.failure(Outcome.REFUSED, "FORBIDDEN", FORBIDDEN_MESSAGE);
        }
        if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException || cause instanceof NumberFormatException
                || cause instanceof ArithmeticException || cause instanceof java.time.DateTimeException) {
            // the JSON → parameters conversion of MethodToolCallback (wrapped in a ToolExecutionException)
            return AiToolResult.failure(Outcome.INVALID, "VALIDATION_ERROR", INVALID_ARGUMENTS_MESSAGE);
        }
        if (cause instanceof InvalidRequestParameterException e) {
            String message = e.getUserMessage() != null ? e.getUserMessage() : INVALID_ARGUMENTS_MESSAGE;
            return AiToolResult.failure(Outcome.INVALID, "VALIDATION_ERROR", message);
        }
        log.warn("AI tool {} failed ({}: {})", entry.descriptor().name(), cause.getClass().getSimpleName(),
                cause.getMessage());
        return AiToolResult.failure(Outcome.ERROR, "INTERNAL_ERROR", ERROR_MESSAGE);
    }

    private String serialise(Object result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new ResultNotSerialisable(e);
        }
    }

    /** A tool returned a value Jackson cannot write: a bug of the tool, reported as ERROR. */
    static final class ResultNotSerialisable extends RuntimeException {
        ResultNotSerialisable(Throwable cause) {
            super("AI tool result is not serialisable", cause);
        }
    }

    private void record(String tenant, String tool, AiToolChannel channel, AiToolResult result, long start,
                        UUID accessTokenId) {
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        UUID actor = CurrentActor.actorIdOrNull();
        try {
            audit.record(new AiToolCallAudit.AiToolCall(tenant, actor, tool == null || tool.isBlank() ? UNNAMED_TOOL
                    : tool, channel, result.outcome(),
                    result.errorCode(), durationMs, clock.instant(), accessTokenId));
        } catch (RuntimeException e) {
            log.warn("AI tool call audit failed for {} ({}: {})", tool, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Test helper: the tools, name → descriptor, in name order. */
    Map<String, AiToolDescriptor> descriptors() {
        Map<String, AiToolDescriptor> out = new LinkedHashMap<>();
        tools.forEach((name, entry) -> out.put(name, entry.descriptor()));
        return out;
    }

    /** Test helper: replaces the tool table. */
    void replaceTools(Map<String, Entry> table) {
        this.tools = Collections.unmodifiableMap(new TreeMap<>(table));
    }
}
