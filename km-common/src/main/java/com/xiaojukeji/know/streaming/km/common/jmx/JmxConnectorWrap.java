package com.xiaojukeji.know.streaming.km.common.jmx;

import com.xiaojukeji.know.streaming.km.common.bean.entity.config.JmxConfig;
import com.xiaojukeji.know.streaming.km.common.utils.BackoffUtils;
import com.xiaojukeji.know.streaming.km.common.utils.ValidateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.naming.Context;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMXConnector包装类
 * @author tukun
 * @date 2015/11/9.
 */
public class JmxConnectorWrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(JmxConnectorWrap.class);

    private final Long physicalClusterId;

    private final Integer brokerId;

    private final Long brokerStartupTime;

    private final String host;

    private final Integer port;

    private JMXConnector jmxConnector;

    private final AtomicInteger atomicInteger;

    private JmxConfig jmxConfig;

    public JmxConnectorWrap(Long physicalClusterId, Integer brokerId, Long brokerStartupTime, String host, Integer port, JmxConfig jmxConfig) {
        this.physicalClusterId = physicalClusterId;
        this.brokerId = brokerId;
        this.brokerStartupTime = brokerStartupTime;
        this.host = host;

        if (port == null || port == -1 && jmxConfig.getJmxPort() != null) {
            this.port = jmxConfig.getJmxPort();
        } else {
            this.port = port;
        }

        this.jmxConfig = jmxConfig;
        if (ValidateUtils.isNull(this.jmxConfig)) {
            this.jmxConfig = new JmxConfig();
        }
        if (ValidateUtils.isNullOrLessThanZero(this.jmxConfig.getMaxConn())) {
            this.jmxConfig.setMaxConn(1000);
        }
        this.atomicInteger = new AtomicInteger(this.jmxConfig.getMaxConn());
    }

    public boolean checkJmxConnectionAndInitIfNeed() {
        if (jmxConnector != null) {
            return true;
        }
        if (port == null || port == -1) {
            return false;
        }
        return createJmxConnector();
    }

    public boolean brokerChanged(Long startTime) {
        if (this.brokerStartupTime == null || !this.brokerStartupTime.equals(startTime)) {
            return true;
        }

        return false;
    }

    public synchronized void close() {
        if (jmxConnector == null) {
            return;
        }
        try {
            jmxConnector.close();
        } catch (IOException e) {
            LOGGER.warn("close JmxConnector exception, physicalClusterId:{} brokerId:{} host:{} port:{}.", physicalClusterId, brokerId, host, port, e);
        }
    }

    public Object getAttribute(ObjectName name, String attribute) throws
            MBeanException,
            AttributeNotFoundException,
            InstanceNotFoundException,
            ReflectionException,
            IOException {
        try {
            acquire();
            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
            return mBeanServerConnection.getAttribute(name, attribute);
        } finally {
            atomicInteger.incrementAndGet();
        }
    }

    public AttributeList getAttributes(ObjectName name, String[] attributes) throws
            MBeanException,
            AttributeNotFoundException,
            InstanceNotFoundException,
            ReflectionException,
            IOException {
        try {
            acquire();
            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
            return mBeanServerConnection.getAttributes(name, attributes);
        } finally {
            atomicInteger.incrementAndGet();
        }
    }

    public Set<ObjectName> queryNames(ObjectName name, QueryExp query)
            throws IOException {
        try {
            acquire();
            MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
            return mBeanServerConnection.queryNames(name, query);
        } finally {
            atomicInteger.incrementAndGet();
        }
    }


    /**************************************************** private method ****************************************************/


    private synchronized boolean createJmxConnector() {
        if (jmxConnector != null) {
            return true;
        }
        String jmxUrl = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", host, port);
        try {
            Map<String, Object> environment = new HashMap<String, Object>();
            if (!ValidateUtils.isBlank(this.jmxConfig.getUsername()) && !ValidateUtils.isBlank(this.jmxConfig.getToken())) {
                // fixed by riyuetianmu
                environment.put(JMXConnector.CREDENTIALS, new String[]{this.jmxConfig.getUsername(), this.jmxConfig.getToken()});
            }
            if (jmxConfig.getOpenSSL() != null && this.jmxConfig.getOpenSSL()) {
                environment.put(Context.SECURITY_PROTOCOL, "ssl");
                SslRMIClientSocketFactory clientSocketFactory = new SslRMIClientSocketFactory();
                environment.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, clientSocketFactory);
                environment.put("com.sun.jndi.rmi.factory.socket", clientSocketFactory);
            }

            jmxConnector = JMXConnectorFactory.connect(new JMXServiceURL(jmxUrl), environment);
            LOGGER.info("JMX connect success, physicalClusterId:{} brokerId:{} host:{} port:{}.", physicalClusterId, brokerId, host, port);
            return true;
        } catch (MalformedURLException e) {
            LOGGER.error("JMX url exception, physicalClusterId:{} brokerId:{} host:{} port:{} jmxUrl:{}", physicalClusterId, brokerId, host, port, jmxUrl, e);
        } catch (Exception e) {
            LOGGER.error("JMX connect exception, physicalClusterId:{} brokerId:{} host:{} port:{}.", physicalClusterId, brokerId, host, port, e);
        }
        return false;
    }

    private void acquire() {
        long now = System.currentTimeMillis();
        while (true) {
            try {
                int num = atomicInteger.get();
                if (num <= 0) {
                    BackoffUtils.backoff(2);
                }

                if (atomicInteger.compareAndSet(num, num - 1) || System.currentTimeMillis() - now > 6000) {
                    break;
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
