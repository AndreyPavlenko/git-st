package com.googlecode.gitst;

import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Andrey Pavlenko
 */
public class RepoProperties {
    public static final String PROP_HOST = "Host";
    public static final String PROP_PORT = "Port";
    public static final String PROP_CACHE_AGENT_HOST = "CacheAgentHost";
    public static final String PROP_CACHE_AGENT_PORT = "CacheAgentPort";
    public static final String PROP_AUTO_LOCATE_CACHE_AGENT = "AutoLocateCacheAgent";
    public static final String PROP_PROJECT = "Project";
    public static final String PROP_VIEW = "View";
    public static final String PROP_USER = "User";
    public static final String PROP_PASSWORD = "Password";
    public static final String PROP_BRANCH = "Branch";
    public static final String PROP_USER_NAME_PATTERN = "UserNamePattern";
    public static final String PROP_DEFAULT_BRANCH = "master";
    public static final String PROP_DEFAULT_USER_NAME_PATTERN = "{0} <{2}.{1}@mycompany.com>";
    public static final String META_PROP_OLEDATE = "OLEDate";
    public static final String META_PROP_SHA = "SHA";

    private final File _repoDir;
    private final File _configDir;
    private final File _gitstDir;
    private final Properties _globalProperties = new Properties();
    private final Properties _repoProperties = new Properties(_globalProperties);
    private final Properties _sessionProperties = new Properties(
            _repoProperties);
    private final Properties _globalUsersMap = new Properties();
    private final Properties _repoUsersMap = new Properties(_globalUsersMap);
    private final Properties _metaProperties = new Properties();
    private final Map<Integer, String> _uidToName = new ConcurrentHashMap<>();

    public RepoProperties(final File repoDir, final File configDir)
            throws IOException {
        File gitDir = new File(repoDir, ".git");

        if (!gitDir.isDirectory()) {
            // Bare repository
            gitDir = repoDir;
        }

        _repoDir = repoDir;
        _configDir = configDir;
        _gitstDir = new File(gitDir, "git-st");
        loadProperties();
        loadUsersMap();
        loadMetaProperties();
    }

    public File getRepoDir() {
        return _repoDir;
    }

    public File getConfigDir() {
        return _configDir;
    }

    public File getGitstDir() {
        return _gitstDir;
    }

    public String getProperty(final String name) throws ConfigurationException {
        final String value = _sessionProperties.getProperty(name);

        if (value == null) {
            throw new ConfigurationException(
                    "Missing required configuration property: " + name);
        }

        return value;
    }

    public String getProperty(final String name, final String defaultValue) {
        return _sessionProperties.getProperty(name, defaultValue);
    }

    public String getMetaProperty(final String name) {
        return _metaProperties.getProperty(name);
    }

    public String getUserMapping(final Integer uid) {
        return _uidToName.get(uid);
    }

    public String setGlobalProperty(final String name, final String value) {
        return (String) _globalProperties.setProperty(name, value);
    }

    public String setRepoProperty(final String name, final String value) {
        return (String) _repoProperties.setProperty(name, value);
    }

    public String setSessionProperty(final String name, final String value) {
        return (String) _sessionProperties.setProperty(name, value);
    }

    public String setMetaProperty(final String name, final String value) {
        return (String) _metaProperties.setProperty(name, value);
    }

    public String setGlobalUserMapping(final Integer uid, final String name) {
        _uidToName.put(uid, name);
        return (String) _globalUsersMap.setProperty(uid.toString(), name);
    }

    public String setRepoUserMapping(final Integer uid, final String name) {
        _uidToName.put(uid, name);
        return (String) _repoUsersMap.setProperty(uid.toString(), name);
    }

    public String setSessionUserMapping(final Integer uid, final String name) {
        return _uidToName.put(uid, name);
    }

    public String getOrRequestProperty(final String name, final String prompt,
            final boolean isPassword) {
        final String value = _sessionProperties.getProperty(name);
        return (value == null) ? requestProperty(name, prompt, isPassword)
                : value;
    }

    public String requestProperty(final String name, final String prompt,
            final boolean isPassword) {
        final Console c = System.console();

        if (c == null) {
            throw new ConfigurationException(
                    "Missing required configuration property: " + name);
        }

        final String value;

        if (isPassword) {
            final char[] pwd = c.readPassword(prompt);
            value = (pwd == null) ? null : new String(pwd);
        } else {
            value = c.readLine(prompt);
        }

        if (value != null) {
            _sessionProperties.setProperty(name, value);
        }

        return value;
    }

    public void saveGlobalProperties() throws IOException {
        final File confDir = getConfigDir();

        if (confDir == null) {
            throw new ConfigurationException("Config dir is not specified");
        }

        save(_globalProperties, new File(confDir, "global.properties"),
                "Global properties");
    }

    public void saveGlobalUserMapings() throws IOException {
        final File confDir = getConfigDir();

        if (confDir == null) {
            throw new ConfigurationException("Config dir is not specified");
        }

        save(_globalUsersMap, new File(confDir, "users.map"),
                "Global users mapping");
    }

    public void saveRepoProperties() throws IOException {
        save(_repoProperties, new File(getGitstDir(), "repository.properties"),
                "Repository properties");
    }

    public void saveRepoUserMapings() throws IOException {
        save(_repoUsersMap, new File(getGitstDir(), "users.properties"),
                "Repository users mapping");
    }

    public void saveMeta() throws IOException {
        save(_metaProperties, new File(getGitstDir(), ".meta"),
                "Repository meta properties");
    }

    private static void save(final Properties props, final File f,
            final String comments) throws IOException {
        final File dir = f.getParentFile();

        if (!dir.isDirectory()) {
            dir.mkdirs();
        }

        try (OutputStream out = new FileOutputStream(f)) {
            props.store(out, comments);
        }
    }

    private void loadProperties() throws IOException {
        final File repoProps = new File(_gitstDir, "repository.properties");

        if (_configDir != null) {
            final File globalProps = new File(_configDir, "global.properties");

            if (globalProps.isFile()) {
                load(_globalProperties, globalProps);
            }
        }
        if (repoProps.isFile()) {
            load(_repoProperties, repoProps);
        }
    }

    private void loadUsersMap() throws IOException {
        final File repoMap = new File(_gitstDir, "users.map");

        if (_configDir != null) {
            final File globalMap = new File(_configDir, "users.map");

            if (globalMap.isFile()) {
                load(_globalUsersMap, globalMap);
            }
        }
        if (repoMap.isFile()) {
            load(_repoUsersMap, repoMap);
        }

        for (final Map.Entry<Object, Object> e : _repoUsersMap.entrySet()) {
            final Integer uid = Integer.valueOf((String) e.getKey());
            final String name = (String) e.getValue();
            _uidToName.put(uid, name);
        }
    }

    private void loadMetaProperties() throws IOException {
        final File meta = new File(getGitstDir(), ".meta");

        if (meta.isFile()) {
            load(_metaProperties, meta);
        }
    }

    private static void load(final Properties props, final File f)
            throws IOException {
        try (final InputStream in = new FileInputStream(f)) {
            props.load(in);
        }
    }
}
