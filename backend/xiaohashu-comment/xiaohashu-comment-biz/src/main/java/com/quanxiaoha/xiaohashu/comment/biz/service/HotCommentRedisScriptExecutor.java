package com.quanxiaoha.xiaohashu.comment.biz.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

/** Executes hot-comment scripts with protocol strings rather than the JSON value serializer. */
public final class HotCommentRedisScriptExecutor {
    private static final RedisSerializer<String> ARGUMENT_SERIALIZER = new StringRedisSerializer();
    private static final RedisSerializer<Long> RESULT_SERIALIZER = new GenericToStringSerializer<>(Long.class);

    private HotCommentRedisScriptExecutor() {
    }

    public static Long execute(RedisTemplate<String, Object> redisTemplate,
                               RedisScript<Long> script,
                               List<String> keys,
                               Object... arguments) {
        Object[] stringArguments = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            stringArguments[i] = String.valueOf(arguments[i]);
        }
        return redisTemplate.execute(script, ARGUMENT_SERIALIZER, RESULT_SERIALIZER, keys, stringArguments);
    }
}
