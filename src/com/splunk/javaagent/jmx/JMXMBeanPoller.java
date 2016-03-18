package com.splunk.javaagent.jmx;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.log4j.Logger;
import org.exolab.castor.mapping.Mapping;
import org.exolab.castor.xml.Unmarshaller;
import org.xml.sax.InputSource;

import com.splunk.javaagent.jmx.config.Formatter;
import com.splunk.javaagent.jmx.config.JMXPoller;
import com.splunk.javaagent.jmx.config.JMXServer;
import com.splunk.javaagent.jmx.config.Transport;

public class JMXMBeanPoller {

    private static Logger logger = Logger.getLogger(JMXMBeanPoller.class);

    private JMXPoller config;
    private Formatter formatter;
    private Transport transport;
    private Map<JMXServer, JMXConnector> connections = new HashMap<>();
    boolean registerNotifications = true;

    public JMXMBeanPoller(String configFile) throws Exception {
        this.config = JMXMBeanPoller.loadConfig(configFile);
        this.config.normalizeClusters();
        this.formatter = config.getFormatter();
        if (this.formatter == null) {
            this.formatter = new Formatter();// default
        }
        this.transport = config.getTransport();
        if (this.transport == null) {
            this.transport = new Transport();// default
        }
        connect();
    }

    /**
     * Connect to the local JMX Server
     *
     * @throws Exception
     */
    private void connect() throws Exception {
        List<JMXServer> servers = this.config.normalizeMultiPIDs();
        if (servers != null) {

            for (JMXServer server : servers) {
                connections.put(server, createJMXConnector(server));
            }
        }
    }

    private MBeanServerConnection createMBeanServerConnection(JMXServer serverConfig) throws Exception {
        JMXConnector jmxConnector = connections.get(serverConfig);
        if (jmxConnector == null) {
            return ManagementFactory.getPlatformMBeanServer();
        } else {
            return jmxConnector.getMBeanServerConnection();
        }
    }

    private static JMXConnector createJMXConnector(JMXServer serverConfig) throws Exception {
        String host = serverConfig.getHost();
        int port = serverConfig.getJmxport();
        if ((host != null) && !host.isEmpty() && port != 0) {
            logger.info("Connect to JMX server: " + host + ":" + port);
            JMXServiceURL url = new JMXServiceURL(String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", host, port));
            return JMXConnectorFactory.connect(url, null);
        } else {
            return null;
        }
    }

    public void execute() throws Exception {

        logger.info("Starting JMX Poller");
        try {

            if (this.config != null) {
                // get list of JMX Servers and process in their own thread.
                List<JMXServer> servers = this.config.normalizeMultiPIDs();
                if (servers != null) {

                    for (JMXServer server : servers) {
                        new ProcessServerThread(server,
                                this.formatter.getFormatterInstance(),
                                this.transport.getTransportInstance(),
                                this.registerNotifications,
                                createMBeanServerConnection(server)).start();
                    }
                    // we only want to register a notification listener on the
                    // first iteration
                    this.registerNotifications = false;
                } else {
                    logger.error("No JMX servers have been specified");
                }
            } else {
                logger.error("The root config object(JMXPoller) failed to initialize");
            }
        } catch (Exception e) {
            logger.error("JMX Error", e);
            throw e;
        }
    }

    /**
     * Parse the config XML into Java POJOs and validate against XSD
     *
     * @param configFileName
     * @return The configuration POJO root
     * @throws Exception
     */
    private static JMXPoller loadConfig(String configFileName) throws Exception {

        // xsd validation
        try (InputStream in = getConfigStream(configFileName)) {
            InputSource inputSource = new InputSource(in);
            SchemaValidator validator = new SchemaValidator();
            validator.validateSchema(inputSource);
        }

        try (InputStream in = getConfigStream(configFileName)) {
            InputSource inputSource = new InputSource(in);
            // use CASTOR to parse XML into Java POJOs
            Mapping mapping = new Mapping();

            URL mappingURL = JMXMBeanPoller.class
                    .getResource("/com/splunk/javaagent/jmx/mapping.xml");
            mapping.loadMapping(mappingURL);
            Unmarshaller unmar = new Unmarshaller(mapping);

            // for some reason the xsd validator closes the file stream, so re-open

            inputSource = new InputSource(in);
            JMXPoller poller = (JMXPoller) unmar.unmarshal(inputSource);
            return poller;
        }
    }

    private static InputStream getConfigStream(String configFileName) throws IOException {
        InputStream in = null;
        boolean foundFile = false;

        // look inside the jar first
        URL file = JMXMBeanPoller.class.getResource("/" + configFileName);

        if (file != null) {
            in = file.openStream();
            foundFile = true;
        } else {
            try {
                // look on the filesystem
                in = new FileInputStream(configFileName);
                foundFile = true;
            } catch (Exception e) {
                foundFile = false;
            }

        }

        if (!foundFile) {
            throw new IOException("The config file " + configFileName
                    + " does not exist");
        }

        return in;
    }

    public void stop() {
        try {
            if (connections == null) return;

            for (JMXConnector jmxConnector : connections.values()) {
                try {
                    if (jmxConnector == null) break;
                    jmxConnector.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (Throwable th) {
            //swallow
        }
    }
}
