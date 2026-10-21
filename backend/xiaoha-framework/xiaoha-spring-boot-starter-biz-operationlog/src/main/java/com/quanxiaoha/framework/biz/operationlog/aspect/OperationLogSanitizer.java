package com.quanxiaoha.framework.biz.operationlog.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quanxiaoha.framework.common.util.JsonUtils;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Redacts credentials and personal identifiers before operation arguments/results are logged. */
final class OperationLogSanitizer {

    private static final ObjectMapper TREE_MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "phone", "mobile", "code", "verificationcode", "smscode", "captcha",
            "password", "encodepassword", "token", "accesstoken", "refreshtoken",
            "authorization", "accesskeyid", "accesskeysecret", "secretkey", "templateparam"
    );

    private OperationLogSanitizer() {
    }

    static String toSafeJson(Object value) {
        try {
            JsonNode node = TREE_MAPPER.readTree(JsonUtils.toJsonString(value));
            redact(node);
            return node == null ? "null" : node.toString();
        } catch (Exception ignored) {
            return "[unavailable]";
        }
    }

    private static void redact(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedName = field.getKey().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                JsonNode fieldValue = field.getValue();
                if (SENSITIVE_FIELDS.contains(normalizedName)
                        || ("data".equals(normalizedName) && fieldValue.isTextual())) {
                    objectNode.put(field.getKey(), "[REDACTED]");
                } else {
                    redact(fieldValue);
                }
            }
            return;
        }

        for (JsonNode child : node) {
            redact(child);
        }
    }
}
