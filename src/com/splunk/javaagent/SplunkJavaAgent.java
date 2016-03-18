package com.splunk.javaagent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ArrayBlockingQueue;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.splunk.javaagent.hprof.HprofDump;
import com.splunk.javaagent.jmx.JMXMBeanPoller;
import com.splunk.javaagent.jmx.mbean.JavaAgentMXBean;
import com.splunk.javaagent.trace.FilterListItem;
import com.splunk.javaagent.trace.SplunkClassFileTransformer;
import com.splunk.javaagent.transport.SplunkTransport;

public class SplunkJavaAgent implements JavaAgentMXBean {

    private static SplunkJavaAgent agent;

    private Properties props;
    private SplunkTransport transport;
    private String transportImpl;
    private List<FilterListItem> whiteList;
    private List<FilterListItem> blackList;
    private boolean traceMethodExited;
    private boolean traceMethodEntered;
    private boolean traceClassLoaded;
    private boolean traceErrors;
    private boolean traceJMX;
    private boolean traceHprof;
    private Map<String, Integer> jmxConfigFiles;
    private int defaultJMXFrequency = 60;// seconds
    private List<Byte> hprofRecordFilter;
    private Map<Byte, List<Byte>> hprofHeapDumpSubRecordFilter;
    private String hprofFile;
    private int hprofFrequency = 600;// seconds
    private Map<String, String> userTags;
    private ArrayBlockingQueue<SplunkLogEvent> eventQueue;
    private int queueSize = 100000;
    private String appName;
    private String appID;
    private String loggingLevel;
    private static Logger logger = Logger.getLogger(SplunkJavaAgent.class);

    private boolean paused = false;
    private JMXThread jmxThread;
    private HprofThread hprofThread;

    private TransporterThread transporterThread;

    public SplunkJavaAgent() {

    }


    public static void main(String[] args) {
        agent = new SplunkJavaAgent();
        logger.info("Starting Splunk Java Agent");

        if (!agent.loadProperties((args.length > 0) ? args[0] : null))
            return;
        if (!agent.initCommonProperties())
            return;
        if (!agent.initTransport())
            return;
        if (!agent.initJMX())
            return;

        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                //swallow
            }
        }
    }


    public static void premain(String agentArgument,
                               Instrumentation instrumentation) {

        try {

            logger.info("Starting Splunk Java Agent");

            agent = new SplunkJavaAgent();

            if (!agent.loadProperties(agentArgument))
                return;
            if (!agent.initCommonProperties())
                return;
            if (!agent.initTransport())
                return;
            if (!agent.initTracing())
                return;
            if (!agent.initFilters())
                return;
            if (!agent.initJMX())
                return;
            if (!agent.initHprof())
                return;

            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName objName = new ObjectName("splunkjavaagent:type=agent");
            mbs.registerMBean(agent, objName);

            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try {
                        if (agent.transport != null)
                            agent.transport.stop();
                    } catch (Exception e) {
                        logger.error("Error running Splunk Java Agent shutdown hook : "
                                + e.getMessage());
                    }
                }
            });

            instrumentation.addTransformer(new SplunkClassFileTransformer());
        } catch (Throwable t) {
            logger.error("Error starting Splunk Java Agent : " + t.getMessage());
        }

    }

    private boolean initTracing() {

        logger.info("Initialising tracing");

        this.traceClassLoaded = Boolean.parseBoolean(agent.props.getProperty(
                "trace.classLoaded", "true"));
        this.traceMethodEntered = Boolean.parseBoolean(agent.props.getProperty(
                "trace.methodEntered", "true"));
        this.traceMethodExited = Boolean.parseBoolean(agent.props.getProperty(
                "trace.methodExited", "true"));
        this.traceErrors = Boolean.parseBoolean(agent.props.getProperty(
                "trace.errors", "true"));

        return true;
    }

    private void restartHProf() {

        if (this.traceHprof) {
            logger.info("Restarting HPROF");
            try {
                hprofThread = new HprofThread(Thread.currentThread(),
                        this.hprofFrequency, this.hprofFile);
                hprofThread.start();
            } catch (Exception e) {
                logger.error("Error restarting HPROF");
            }

        }
    }

    private boolean initHprof() {

        logger.info("Initialising HPROF");

        this.traceHprof = Boolean.parseBoolean(agent.props.getProperty(
                "trace.hprof", "false"));
        if (this.traceHprof) {
            this.hprofFile = props.getProperty("trace.hprof.tempfile", "");
            try {
                this.hprofFrequency = Integer.parseInt(props.getProperty(
                        "trace.hprof.frequency", "600"));
            } catch (NumberFormatException e) {

            }
            String hprofRecordFilterString = props.getProperty(
                    "trace.hprof.recordtypes", "");
            if (hprofRecordFilterString.length() >= 1) {
                this.hprofRecordFilter = new ArrayList<Byte>();
                this.hprofHeapDumpSubRecordFilter = new HashMap<Byte, List<Byte>>();
                StringTokenizer st = new StringTokenizer(
                        hprofRecordFilterString, ",");
                while (st.hasMoreTokens()) {
                    StringTokenizer st2 = new StringTokenizer(st.nextToken(),
                            ":");

                    byte val = Byte.parseByte(st2.nextToken());
                    this.hprofRecordFilter.add(val);
                    // subrecords
                    if (st2.hasMoreTokens()) {
                        byte subVal = Byte.parseByte(st2.nextToken());
                        List<Byte> list = this.hprofHeapDumpSubRecordFilter
                                .get(val);
                        if (list == null) {
                            list = new ArrayList<Byte>();
                        }
                        list.add(subVal);
                        this.hprofHeapDumpSubRecordFilter.put(val, list);
                    }

                }
            }
            try {
                if (!paused) {
                    hprofThread = new HprofThread(Thread.currentThread(),
                            this.hprofFrequency, this.hprofFile);
                    hprofThread.start();
                }
            } catch (Exception e) {
                logger.error("Error restarting HPROF");
            }
        }

        return true;
    }

    private void restartJMX() {

        if (this.traceJMX) {
            logger.info("Restarting JMX");
            Set<String> configFileNames = this.jmxConfigFiles.keySet();
            for (String configFile : configFileNames) {
                this.jmxThread = new JMXThread(Thread.currentThread(),
                        this.jmxConfigFiles.get(configFile), configFile);
                jmxThread.start();
            }
        }
    }

    private boolean initJMX() {

        logger.info("Initialising JMX");

        this.traceJMX = Boolean.parseBoolean(agent.props.getProperty(
                "trace.jmx", "false"));
        if (this.traceJMX) {

            this.jmxConfigFiles = new HashMap<String, Integer>();
            String configFiles = props.getProperty("trace.jmx.configfiles", "");
            String defaultFrequency = props.getProperty(
                    "trace.jmx.default.frequency", "60");
            this.defaultJMXFrequency = Integer.parseInt(defaultFrequency);
            StringTokenizer st = new StringTokenizer(configFiles, ",");
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                String frequency = props.getProperty("trace.jmx." + token
                        + ".frequency", defaultFrequency);
                this.jmxConfigFiles.put(token + ".xml",
                        Integer.parseInt(frequency));
            }

            if (!paused) {
                Set<String> configFileNames = this.jmxConfigFiles.keySet();
                for (String configFile : configFileNames) {
                    this.jmxThread = new JMXThread(Thread.currentThread(),
                            this.jmxConfigFiles.get(configFile), configFile);
                    jmxThread.start();
                }
            }
        }

        return true;
    }

    private boolean initCommonProperties() {

        logger.info("Initialising common properties");

        this.appName = props.getProperty("agent.app.name", "");
        this.appID = props.getProperty("agent.app.instance", "");

        this.paused = Boolean.parseBoolean(props.getProperty(
                "agent.startPaused", "false"));
        String tags = (String) props.getProperty("agent.userEventTags", "");
        this.userTags = new HashMap<String, String>();

        this.loggingLevel = props.getProperty("agent.loggingLevel", "ERROR");
        LogManager.getRootLogger().setLevel(Level.toLevel(loggingLevel));

        setUserEventTags(tags);

        return true;
    }

    class TransporterThread extends Thread {

        Thread parent;
        boolean stopped = false;

        TransporterThread(Thread parent) {
            this.parent = parent;
        }

        public void stopThread() {
            this.stopped = true;
        }

        public void run() {

            logger.info("Running transporter thread");

            while (parent.isAlive() && !stopped) {

                try {
                    while (!agent.eventQueue.isEmpty()) {
                        SplunkLogEvent event = agent.eventQueue.poll();

                        if (event != null) {
                            agent.transport.send(event);
                        }
                    }
                } catch (Throwable t) {
                    logger.error("Error running transporter thread : "
                            + t.getMessage());
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
            }
        }

    }

    class PropsFileCheckerThread extends Thread {

        File file;
        long lastModified;

        PropsFileCheckerThread(File file) {
            this.file = file;
            this.lastModified = file.lastModified();

        }

        public void run() {

            while (true) {

                try {

                    long modTime = file.lastModified();

                    // props file changed
                    if (modTime > this.lastModified) {

                        logger.info("Detected that props file has changed , reloading.");
                        // pause agent
                        agent.pause();

                        // load in mew props
                        this.lastModified = modTime;
                        InputStream in = null;
                        try {
                            in = new FileInputStream(file);
                            agent.props = new Properties();
                            agent.props.load(in);
                        } catch (Exception e) {
                            logger.error("Error loading properties file :"
                                    + e.getMessage());
                        } finally {
                            if (in != null)
                                in.close();
                        }

                        agent.initCommonProperties();

                        agent.transporterThread.stopThread();
                        MBeanServer mbs = ManagementFactory
                                .getPlatformMBeanServer();
                        ObjectName objName = new ObjectName(
                                "splunkjavaagent:type=transport,impl="
                                        + transportImpl);
                        mbs.unregisterMBean(objName);
                        agent.initTransport();

                        agent.initTracing();

                        agent.initFilters();

                        agent.initJMX();

                        agent.initHprof();

                    }

                    Thread.sleep(5000);// 5 secs

                } catch (Throwable t) {

                    logger.error("Error running properties file checker thread :"
                            + t.getMessage());
                }

            }

        }
    }

    class JMXThread extends Thread {

        Thread parent;
        int frequencySeconds;
        String configFile;
        JMXMBeanPoller poller = null;

        boolean stopped = false;

        JMXThread(Thread parent, int frequencySeconds, String configFile) {
            this.parent = parent;
            this.configFile = configFile;
            this.frequencySeconds = frequencySeconds;
        }

        public void stopThread() {
            if (poller != null) {
                poller.stop();
            }
            this.stopped = true;
        }

        public void run() {

            logger.info("Running JMX Thread");

            while (parent.isAlive() && !stopped) {
                try {
                    Thread.sleep(frequencySeconds * 1000);
                } catch (InterruptedException e) {
                }

                try {
                    if (poller == null) {
                        poller = new JMXMBeanPoller(configFile);
                    }
                    poller.execute();
                } catch (Throwable t) {
                    logger.error("Error in JMX thread", t);
                    if (poller != null) {
                        poller.stop();
                    }
                    poller = null;
                    continue;
                }
            }
        }

    }

    class HprofThread extends Thread {

        Thread parent;
        int frequencySeconds;
        String hprofFile;
        MBeanServerConnection serverConnection;
        ObjectName mbean;
        String operationName;
        Object[] params;
        String[] signature;
        boolean stopped = false;

        HprofThread(Thread parent, int frequencySeconds, String hprofFile)
                throws Exception {
            this.parent = parent;
            this.hprofFile = hprofFile;
            this.frequencySeconds = frequencySeconds;
            this.serverConnection = ManagementFactory.getPlatformMBeanServer();
            this.mbean = new ObjectName(
                    "com.sun.management:type=HotSpotDiagnostic");
            this.operationName = "dumpHeap";
            this.params = new Object[2];
            this.params[0] = hprofFile;
            this.params[1] = new Boolean(true);
            this.signature = new String[2];
            this.signature[0] = "java.lang.String";
            this.signature[1] = "boolean";
        }

        public void stopThread() {
            this.stopped = true;
        }

        public void run() {

            logger.info("Running HPROF thread");

            while (parent.isAlive() && !stopped) {
                try {

                    Thread.sleep(frequencySeconds * 1000);
                } catch (InterruptedException e) {
                }
                try {
                    // do some housekeeping
                    File file = new File(this.hprofFile);
                    if (file.exists())
                        file.delete();

                    // do the dump via JMX
                    logger.info("Generating HPROF dump");
                    serverConnection.invoke(mbean, operationName, params,
                            signature);

                    // process the dump
                    logger.info("Processing HPROF dump");
                    file = new File(this.hprofFile);
                    HprofDump hprof = new HprofDump(file);
                    hprof.process();

                    // delete the dump files
                    if (file.exists())
                        file.delete();

                } catch (Throwable e) {
                    logger.error("Error running HPROF thread : "
                            + e.getMessage());
                }

            }
        }

    }

    private boolean initFilters() {

        try {
            logger.info("Initialising filters");

            this.whiteList = new ArrayList<FilterListItem>();
            this.blackList = new ArrayList<FilterListItem>();

            String white = (String) props.getProperty("trace.whitelist", "");
            String black = (String) props.getProperty("trace.blacklist", "");

            addToList(white, this.whiteList);
            addToList(black, this.blackList);
            return true;

        } catch (Exception e) {
            logger.error("Error initialising filters : " + e.getMessage());
            return false;
        }
    }

    private void addToList(String items, List<FilterListItem> list) {

        StringTokenizer st = new StringTokenizer(items, ",");
        while (st.hasMoreTokens()) {
            String item = st.nextToken();
            StringTokenizer st2 = new StringTokenizer(item, ":");
            FilterListItem fli = new FilterListItem();
            String className = st2.nextToken();
            fli.setClassName(className);
            if (st2.hasMoreTokens()) {
                String methodName = st2.nextToken();
                fli.setMethodName(methodName);

            }
            list.add(fli);
        }

    }

    public static boolean isWhiteListed(String className) {

        if (agent.whiteList.isEmpty())
            return true;
        for (FilterListItem item : agent.whiteList) {
            if (className.startsWith(item.getClassName()))
                return true;
        }
        return false;
    }

    public static boolean isWhiteListed(String className, String methodName) {

        if (agent.whiteList.isEmpty())
            return true;
        for (FilterListItem item : agent.whiteList) {
            if (className.startsWith(item.getClassName())
                    && methodName.equals(item.getMethodName()))
                return true;
            else if (className.startsWith(item.getClassName())
                    && item.getMethodName() == null) {
                return true;
            }
        }
        return false;

    }

    public static boolean isBlackListed(String className) {

        if (agent.blackList.isEmpty())
            return false;
        for (FilterListItem item : agent.blackList) {
            if (className.startsWith(item.getClassName()))
                return true;
        }
        return false;
    }

    public static boolean isBlackListed(String className, String methodName) {

        if (agent.blackList.isEmpty())
            return true;
        for (FilterListItem item : agent.blackList) {
            if (className.startsWith(item.getClassName())
                    && methodName.equals(item.getMethodName()))
                return true;
            else if (className.startsWith(item.getClassName())
                    && item.getMethodName() == null) {
                return true;
            }
        }
        return false;

    }

    private boolean initTransport() {

        logger.info("Initialising transport");

        this.transportImpl = props.getProperty("splunk.transport.impl",
                "com.splunk.javaagent.transport.SplunkTCPTransport");

        try {

            this.transport = (SplunkTransport) Class.forName(transportImpl)
                    .newInstance();
        } catch (Exception e) {
            logger.error("Error initialising transport class object : "
                    + e.getMessage());
            return false;
        }
        Map<String, String> args = new HashMap<String, String>();
        Set<Object> keys = props.keySet();
        for (Object key : keys) {
            String keyString = (String) key;
            if (keyString.startsWith("splunk."))
                args.put(keyString, props.getProperty(keyString));
        }

        try {
            this.queueSize = Integer.parseInt(props.getProperty(
                    "splunk.transport.internalQueueSize", "100000"));
        } catch (NumberFormatException e) {

        }

        try {

            this.eventQueue = new ArrayBlockingQueue<SplunkLogEvent>(queueSize);

            this.transport.init(args);

            if (!paused) {
                this.transport.start();
            }

            this.transporterThread = new TransporterThread(
                    Thread.currentThread());
            this.transporterThread.start();

            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName objName = new ObjectName(
                    "splunkjavaagent:type=transport,impl=" + transportImpl);
            mbs.registerMBean(this.transport, objName);

        } catch (Exception e) {
            logger.error("Initialising transport : " + e.getMessage());
            return false;
        }
        return true;

    }

    private boolean loadProperties(String propsFile) {

        logger.info("Loading properties file");

        this.props = new Properties();
        InputStream in = null;

        try {
            if (propsFile == null || propsFile.length() == 0) {
                in = ClassLoader
                        .getSystemResourceAsStream("splunkagent.properties");
            } else {
                File file = new File(propsFile);
                in = new FileInputStream(file);
                new PropsFileCheckerThread(file).start();
            }

            this.props.load(in);
        } catch (IOException e) {
            logger.error("Error loading properties file :" + e.getMessage());
            return false;
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                return false;
            }
        }
        return true;

    }

    public static void classLoaded(String className) {

        if (agent.traceClassLoaded && !agent.paused) {
            SplunkLogEvent event = new SplunkLogEvent("class_loaded",
                    "splunkagent", true, false);
            event.addPair("appName", agent.appName);
            event.addPair("appID", agent.appID);
            event.addPair("className", className);
            addUserTags(event);
            agent.transport.send(event);
        }
    }

    private static void addUserTags(SplunkLogEvent event) {

        if (!agent.userTags.isEmpty()) {

            Set<String> keys = agent.userTags.keySet();
            for (String key : keys) {
                event.addPair(key, agent.userTags.get(key));
            }
        }

    }

    public static void methodEntered(String className, String methodName,
                                     String desc) {

        if (agent.traceMethodEntered && !agent.paused) {
            SplunkLogEvent event = new SplunkLogEvent("method_entered",
                    "splunkagent", true, false);
            event.addPair("appName", agent.appName);
            event.addPair("appID", agent.appID);
            event.addPair("className", className);
            event.addPair("methodName", methodName);
            event.addPair("methodDesc", desc);
            event.addPair("threadID", Thread.currentThread().getId());
            event.addPair("threadName", Thread.currentThread().getName());

            try {
                StackTraceElement ste = Thread.currentThread().getStackTrace()[3];
                if (ste != null)
                    event.addPair("lineNumber", ste.getLineNumber());
                event.addPair("sourceFileName", ste.getFileName());
            } catch (Exception e1) {
            }

            addUserTags(event);
            try {
                agent.eventQueue.put(event);
                // agent.eventQueue.offer(event,1000,TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {

            }
        }
    }

    public static void methodExited(String className, String methodName,
                                    String desc) {

        if (agent.traceMethodExited && !agent.paused) {

            SplunkLogEvent event = new SplunkLogEvent("method_exited",
                    "splunkagent", true, false);
            event.addPair("appName", agent.appName);
            event.addPair("appID", agent.appID);
            event.addPair("className", className);
            event.addPair("methodName", methodName);
            event.addPair("methodDesc", desc);
            event.addPair("threadID", Thread.currentThread().getId());
            event.addPair("threadName", Thread.currentThread().getName());
            addUserTags(event);
            try {
                agent.eventQueue.put(event);
                // agent.eventQueue.offer(event,1000,TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {

            }
        }
    }

    public static void throwableCaught(String className, String methodName,
                                       String desc, Throwable t) {

        if (agent.traceErrors && !agent.paused) {

            SplunkLogEvent event = new SplunkLogEvent("throwable_caught",
                    "splunkagent", true, false);
            event.addPair("appName", agent.appName);
            event.addPair("appID", agent.appID);
            event.addPair("className", className);
            event.addPair("methodName", methodName);
            event.addPair("methodDesc", desc);
            event.addThrowable(t);
            event.addPair("methodName", methodName);
            event.addPair("threadID", Thread.currentThread().getId());
            event.addPair("threadName", Thread.currentThread().getName());
            addUserTags(event);
            try {
                agent.eventQueue.put(event);
                // agent.eventQueue.offer(event,1000,TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {

            }

        }
    }

    public static void hprofRecordEvent(byte recordType, byte subRecordType,
                                        SplunkLogEvent event) {

        if (agent.traceJMX && !agent.paused) {
            if (traceHprofRecordType(recordType, subRecordType)) {
                event.addPair("appName", agent.appName);
                event.addPair("appID", agent.appID);
                addUserTags(event);
                try {

                    agent.eventQueue.put(event);

                } catch (InterruptedException e) {

                }

            }
        }
    }

    public static void jmxEvent(SplunkLogEvent event) {

        if (agent.traceJMX && !agent.paused) {
            event.addPair("appName", agent.appName);
            event.addPair("appID", agent.appID);
            addUserTags(event);
            try {

                agent.eventQueue.put(event);

            } catch (InterruptedException e) {

            }
        }

    }

    private static boolean traceHprofRecordType(byte recordType,
                                                byte subRecordType) {
        if (agent.hprofRecordFilter == null
                || agent.hprofRecordFilter.isEmpty())
            return true;
        else {
            for (byte b : agent.hprofRecordFilter) {
                if (b == recordType) {
                    List<Byte> subrecords = agent.hprofHeapDumpSubRecordFilter
                            .get(recordType);
                    if (subrecords == null || subrecords.isEmpty()) {
                        return true;
                    } else {
                        for (byte bb : subrecords) {
                            if (bb == subRecordType) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String getAppName() {

        return this.appName;
    }

    @Override
    public String getAppInstance() {

        return this.appID;
    }

    @Override
    public String getUserEventTags() {

        return this.userTags.toString();

    }

    @Override
    public boolean getStartPaused() {

        return this.paused;

    }

    @Override
    public String getLoggingLevel() {

        return this.loggingLevel;
    }

    @Override
    public String getTracingBlacklist() {

        return this.blackList.toString();
    }

    @Override
    public String getTracingWhitelist() {

        return this.whiteList.toString();
    }

    @Override
    public boolean getTraceMethodEntered() {
        return this.traceMethodEntered;
    }

    @Override
    public boolean getTraceMethodExited() {
        return this.traceMethodExited;
    }

    @Override
    public boolean getTraceClassLoaded() {
        return this.traceClassLoaded;
    }

    @Override
    public boolean getTraceErrors() {
        return this.traceErrors;
    }

    @Override
    public boolean getTraceJMX() {
        return this.traceJMX;
    }

    @Override
    public String getTraceJMXConfigFiles() {
        return this.jmxConfigFiles.toString();
    }

    @Override
    public boolean getTraceHProf() {
        return this.traceHprof;
    }

    @Override
    public String getTraceHProfTempFile() {
        return this.hprofFile;
    }

    @Override
    public int getTraceHProfFrequency() {
        return this.hprofFrequency;
    }

    @Override
    public void setAppName(String val) {
        this.appName = val;

    }

    @Override
    public void setAppInstance(String val) {
        this.appID = val;

    }

    @Override
    public void setUserEventTags(String val) {

        StringTokenizer st = new StringTokenizer(val, ",");
        while (st.hasMoreTokens()) {
            String item = st.nextToken();
            StringTokenizer st2 = new StringTokenizer(item, "=");
            String key = st2.nextToken();
            String value = st2.nextToken();
            this.userTags.put(key, value);

        }

    }

    @Override
    public void setLoggingLevel(String val) {
        this.loggingLevel = val;
        LogManager.getRootLogger().setLevel(Level.toLevel(val));

    }

    @Override
    public void setTracingBlacklist(String val) {
        this.blackList = new ArrayList<FilterListItem>();
        addToList(val, this.blackList);

    }

    @Override
    public void setTracingWhitelist(String val) {
        this.whiteList = new ArrayList<FilterListItem>();
        addToList(val, this.whiteList);

    }

    @Override
    public void setTraceMethodEntered(boolean val) {
        this.traceMethodEntered = val;

    }

    @Override
    public void setTraceMethodExited(boolean val) {
        this.traceMethodExited = val;

    }

    @Override
    public void setTraceClassLoaded(boolean val) {
        this.traceClassLoaded = val;

    }

    @Override
    public void setTraceErrors(boolean val) {
        this.traceErrors = val;

    }

    @Override
    public void setTraceJMX(boolean val) {
        this.traceJMX = val;

    }

    @Override
    public void setTraceJMXConfigFiles(String val) {
        if (this.jmxConfigFiles == null) {
            this.jmxConfigFiles = new HashMap<String, Integer>();
        }
        StringTokenizer st = new StringTokenizer(val, ",");
        while (st.hasMoreTokens()) {
            String token = st.nextToken();
            this.jmxConfigFiles.put(token + ".xml", this.defaultJMXFrequency);
        }

    }

    @Override
    public void setTraceJMXFrequency(int val) {
        this.defaultJMXFrequency = val;

    }

    @Override
    public void setTraceHProf(boolean val) {
        this.traceHprof = val;

    }

    @Override
    public void setTraceHProfTempFile(String val) {
        this.hprofFile = val;

    }

    @Override
    public void setTraceHProfFrequency(int val) {
        this.hprofFrequency = val;

    }

    @Override
    public void pause() throws Exception {
        this.transport.stop();
        this.paused = true;
        stopJMX();
        stopHprof();

    }

    @Override
    public void unpause() throws Exception {
        this.transport.start();
        this.paused = false;
        startJMX();
        startHprof();

    }

    @Override
    public void stopJMX() throws Exception {
        if (this.jmxThread != null) {
            this.jmxThread.stopThread();
            this.jmxThread = null;
        }

    }

    @Override
    public void startJMX() throws Exception {
        if (this.jmxThread == null)
            this.restartJMX();

    }

    @Override
    public void stopHprof() throws Exception {
        if (this.hprofThread != null) {
            this.hprofThread.stopThread();
            this.hprofThread = null;
        }

    }

    @Override
    public void startHprof() throws Exception {
        if (this.hprofThread == null)
            this.restartHProf();

    }

}
