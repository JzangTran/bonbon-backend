package com.bonbon.backend.common.config;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every string in a JSON request body is checked for characters no text field should carry: control characters (a NUL
 * cannot even be stored in PostgreSQL text, and a line break inside a name or subject can forge an email header or a log
 * line) and the bidirectional overrides that make text read differently from how it is stored. Tab, line feed and carriage
 * return stay, for the multi-line fields. A body with such a character is a 400, like any other malformed request.
 */
@Configuration
class SafeTextConfig {

    @Bean
    JacksonModule safeTextModule() {
        SimpleModule module = new SimpleModule("bonbon-safe-text");
        module.addDeserializer(String.class, new SafeStringDeserializer());
        return module;
    }

    /** True when the text has a control character other than tab, line feed and carriage return, or a bidi override. */
    static boolean hasForbiddenCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean control = (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F || (c >= 0x80 && c <= 0x9F);
            boolean bidiOverride = (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
            if (control || bidiOverride) {
                return true;
            }
        }
        return false;
    }

    static final class SafeStringDeserializer extends ValueDeserializer<String> {

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            String text = StringDeserializer.instance.deserialize(parser, context);
            if (text != null && hasForbiddenCharacter(text)) {
                return context.reportInputMismatch(String.class, "Văn bản chứa ký tự điều khiển không được phép.");
            }
            return text;
        }
    }
}
