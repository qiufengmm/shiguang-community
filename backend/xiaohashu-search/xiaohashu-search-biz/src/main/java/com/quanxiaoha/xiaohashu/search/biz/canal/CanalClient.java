package com.quanxiaoha.xiaohashu.search.biz.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;

import java.net.InetSocketAddress;

/**
 * @author: 犬小哈
 * @date: 2024/12/14 16:23
 * @version: v1.0.0
 * @description: Canal client
 **/
@Component
@Slf4j
public class CanalClient implements DisposableBean {

    @Resource
    private CanalProperties canalProperties;

    private volatile CanalConnector canalConnector;

    /**
     * 实例化 Canal 链接对象
     * @return
     */
    @Bean
    public CanalConnector getCanalConnector() {
        return openConnector();
    }

    public synchronized CanalConnector getCurrentConnector() {
        return canalConnector;
    }

    public synchronized CanalConnector reconnect() {
        disconnectCurrentConnector();
        canalConnector = null;
        return openConnector();
    }

    public synchronized void invalidateConnection(CanalConnector failedConnector) {
        if (canalConnector != failedConnector) {
            return;
        }
        disconnectCurrentConnector();
        canalConnector = null;
    }

    protected CanalConnector createConnector() {
        // Canal 链接地址
        String address = canalProperties.getAddress();
        String[] addressArr = address.split(":");
        // IP 地址
        String host = addressArr[0];
        // 端口
        int port = Integer.parseInt(addressArr[1]);

        return CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port),
                canalProperties.getDestination(),
                canalProperties.getUsername(),
                canalProperties.getPassword());
    }

    private synchronized CanalConnector openConnector() {
        CanalConnector replacement = createConnector();
        try {
            replacement.connect();
            replacement.subscribe(canalProperties.getSubscribe());
            replacement.rollback();
            canalConnector = replacement;
            return replacement;
        } catch (RuntimeException e) {
            try {
                replacement.disconnect();
            } catch (RuntimeException disconnectError) {
                e.addSuppressed(disconnectError);
            }
            canalConnector = null;
            throw e;
        }
    }

    private void disconnectCurrentConnector() {
        if (canalConnector != null) {
            try {
                canalConnector.disconnect();
            } catch (RuntimeException e) {
                log.warn("Could not close the previous Canal connection before reconnecting", e);
            }
        }
    }

    /**
     * 在 Spring 容器销毁时释放资源
     * @throws Exception
     */
    @Override
    public void destroy() throws Exception {
        disconnectCurrentConnector();
        canalConnector = null;
    }
}
