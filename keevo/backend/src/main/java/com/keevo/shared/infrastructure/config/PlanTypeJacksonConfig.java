package com.keevo.shared.infrastructure.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.keevo.identity.api.PlanType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Story 19.7a — {@link PlanType} is no longer an enum but a value type (plans are data): this module keeps its JSON
 * form the plain code string it had as an enum ({@code "PREMIUM"}), both ways. Durable events carrying a plan
 * (Spring Modulith publication registry, {@code public.event_publication}) serialise and replay unchanged, including
 * the publications written before the change. Registered on the application {@code ObjectMapper} by Spring Boot.
 */
@Configuration
public class PlanTypeJacksonConfig {

    @Bean
    public Module planTypeJacksonModule() {
        return module();
    }

    /** The module itself (also for a hand-built {@code ObjectMapper}). */
    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("PlanTypeModule");
        module.addSerializer(PlanType.class, new JsonSerializer<>() {
            @Override
            public void serialize(PlanType value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.code());
            }
        });
        module.addDeserializer(PlanType.class, new JsonDeserializer<>() {
            @Override
            public PlanType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String code = p.getValueAsString();
                return code == null ? null : PlanType.of(code);
            }
        });
        return module;
    }
}
