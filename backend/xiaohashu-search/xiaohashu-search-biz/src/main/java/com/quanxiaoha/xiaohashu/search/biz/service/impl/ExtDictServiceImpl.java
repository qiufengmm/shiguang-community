package com.quanxiaoha.xiaohashu.search.biz.service.impl;

import com.quanxiaoha.xiaohashu.search.biz.service.ExtDictService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * 拓展词典
 */
@Service
@Slf4j
public class ExtDictServiceImpl implements ExtDictService {

    /**
     * 热更新词典路径
     */
    @Value("${elasticsearch.hotUpdateExtDict}")
    private String hotUpdateExtDict;

    /**
     * 获取热更新词典
     *
     * @return 热更新词典内容和响应头
     */
    @Override
    public ResponseEntity<String> getHotUpdateExtDict() {
        try {
            Path path = Paths.get(hotUpdateExtDict);
            long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

            String fileContent = Files.lines(path).collect(Collectors.joining("\n"));
            String eTag = String.valueOf(fileContent.hashCode());

            HttpHeaders headers = new HttpHeaders();
            headers.set("ETag", eTag);
            headers.setContentType(MediaType.valueOf("text/plain;charset=UTF-8"));

            return ResponseEntity.ok()
                    .headers(headers)
                    .lastModified(lastModifiedTime)
                    .body(fileContent);
        } catch (Exception e) {
            log.error("==> 获取热更新词典异常: ", e);
        }
        return null;
    }
}
