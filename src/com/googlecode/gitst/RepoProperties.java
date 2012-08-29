package com.googlecode.gitst;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Andrey Pavlenko
 */
public class RepoProperties {
    public static final String PROP_URL = "url";
    public static final String PROP_CA = "ca";
    public static final String PROP_USER = "user";
    public static final String PROP_PASSWORD = "password";
    public static final String PROP_THREADS = "threads";
    public static final String PROP_MAXTHREADS = "maxthreads";
    public static final String PROP_IGNORE = "ignore";
    public static final String PROP_USER_PATTERN = "userpattern";
    public static final String PROP_FETCH = "fetch";
    public static final String PROP_DEFAULT_BRANCH = "master";
    public static final String PROP_DEFAULT_THREADS = "3";
    public static final String PROP_DEFAULT_MAXTHREADS = "30";
    public static final String PROP_DEFAULT_IGNORE = "\\.gitignore;.*/\\.gitignore";
    public static final String PROP_DEFAULT_USER_PATTERN = "{0} <{4}.{2}@mycompany.com>";
    public static final String META_PROP_LAST_PULL_DATE = "LastPullDate";
    public static final String META_PROP_LAST_PUSH_SHA = "LastPushSha";
    public static final String META_PROP_ITEM_FILTER = "ItemFilter";

    private final Git _git;
    private final String _remoteName;
    private final File _gitstDir;
    private final Map<String, String> _globalConfig = new ConcurrentHashMap<>();
    private final Map<String, String> _localConfig = new ConcurrentHashMap<>();
    private final Map<String, String> _sessionConfig = new ConcurrentHashMap<>();
    private final Map<Integer, String> _globalUsers = new ConcurrentHashMap<>();
    private final Map<Integer, String> _localUsers = new ConcurrentHashMap<>();
    private final Map<Integer, String> _sessionUsers = new ConcurrentHashMap<>();
    private final Properties _metaProperties = new Properties();

    public RepoProperties(final Git git, final String remoteName)
            throws IOException, InterruptedException, ExecutionException {
        _git = git;
        _remoteName = remoteName;
        _gitstDir = new File(git.getGitDir(), "git-st");
        load();
        loadMetaProperties();
    }

    public Git getGit() {
        return _git;
    }

    public String getRemoteName() {
        return _remoteName;
    }

    public File getRepoDir() {
        return getGit().getRepoDir();
    }

    public File getGitstDir() {
        return _gitstDir;
    }

    public String getProperty(final String name) throws ConfigurationException {
        return get(name, _sessionConfig, _localConfig, _globalConfig);
    }

    public String getProperty(final String name, final String defaultValue) {
        return get(name, defaultValue, _sessionConfig, _localConfig,
                _globalConfig);
    }

    public String getMetaProperty(final String name) {
        return _metaProperties.getProperty(name);
    }

    public String getUserMapping(final Integer uid) {
        return get(uid, null, _sessionUsers, _localUsers, _globalUsers);
    }

    public String getBranchName() {
        String branch = getProperty(PROP_FETCH, null);

        if (branch == null) {
            return "refs/heads/master";
        } else {
            final int i1 = branch.startsWith("+") ? 1 : 0;
            int i2 = branch.indexOf(':');
            branch = (i2 == -1) ? branch.substring(i1) : branch.substring(i1,
                    i2);
            i2 = branch.indexOf('*');

            if (i2 != -1) {
                branch = branch.substring(0, i2) + "master";
            }

            return branch;
        }
    }

    public String getRemoteBranchName() {
        String branch = getProperty(PROP_FETCH, null);

        if (branch == null) {
            return "refs/remotes/origin/master";
        } else {
            int i = branch.indexOf(':');

            if (i != -1) {
                branch = branch.substring(i + 1);
            }

            i = branch.indexOf('*');

            if (i != -1) {
                branch = branch.substring(0, i) + "master";
            }

            return branch;
        }
    }

    public String setLocalProperty(final String name, final String value) {
        return (value == null) ? _localConfig.remove(name) : _localConfig.put(
                name, value);
    }

    public String setSessionProperty(final String name, final String value) {
        return (value == null) ? _sessionConfig.remove(name) : _sessionConfig
                .put(name, value);
    }

    public String setMetaProperty(final String name, final String value) {
        return (String) ((value == null) ? _metaProperties.remove(name)
                : _metaProperties.setProperty(name, value));
    }

    public String setLocalUserMapping(final Integer uid, final String name) {
        return (name == null) ? _localUsers.remove(uid) : _localUsers.put(uid,
                name);
    }

    public String setSessionUserMapping(final Integer uid, final String name) {
        return (name == null) ? _sessionUsers.remove(uid) : _sessionUsers.put(
                uid, name);
    }

    public String getOrRequestProperty(final String name, final String prompt,
            final boolean isPassword) {
        final String value = getProperty(name, null);
        return (value == null) ? requestProperty(name, prompt, isPassword)
                : value;
    }

    public String requestProperty(final String name, final String prompt,
            final boolean isPassword) {
        final String value;
        final Console c = System.console();

        if (c == null) {
            throw new ConfigurationException(
                    "Missing required configuration property: " + name);
        }

        if (isPassword) {
            final char[] pwd = c.readPassword(prompt);
            value = (pwd == null) ? null : new String(pwd);
        } else {
            value = c.readLine(prompt);
        }

        if (value != null) {
            setSessionProperty(name, value);
        } else {
            throw new ConfigurationException(
                    "Missing required configuration property: " + name);
        }

        return value;
    }

    public void saveLocalProperties() throws IOException, InterruptedException,
            ExecutionException {
        save("remote." + getRemoteName() + '.', _localConfig);
    }

    public void saveLocalUserMapings() throws IOException,
            InterruptedException, ExecutionException {
        save("remote." + getRemoteName() + ".uid", _localUsers);
    }

    public void saveMeta() throws IOException {
        final File dir = new File(getGitstDir(), getBranchName());
        final File f = new File(dir, ".meta");
        dir.mkdirs();

        try (OutputStream out = new FileOutputStream(f)) {
            _metaProperties.store(out, "Git-ST meta properties");
        }
    }

    private void loadMetaProperties() throws IOException {
        final File dir = new File(getGitstDir(), getBranchName());
        final File meta = new File(dir, ".meta");

        if (meta.isFile()) {
            try (final InputStream in = new FileInputStream(meta)) {
                _metaProperties.load(in);
            }
        }
    }

    private void load() throws IOException, InterruptedException,
            ExecutionException {
        load("--system", "git-st.", _globalConfig, _globalUsers);
        load("--global", "git-st.", _globalConfig, _globalUsers);
        load("--local", "remote." + getRemoteName() + '.', _localConfig,
                _localUsers);
    }

    private void load(final String scope, final String pref,
            final Map<String, String> config, final Map<Integer, String> users)
            throws IOException, InterruptedException, ExecutionException {
        final String uidPref = pref + "uid";
        final Exec exec = getGit().exec("config", scope, "--get-regexp",
                pref + '*');
        exec.setOutStream(null);
        final Process proc = exec.exec().getProcess();

        try {
            final BufferedReader r = new BufferedReader(new InputStreamReader(
                    proc.getInputStream()));

            for (String s = r.readLine(); s != null; s = r.readLine()) {
                final int ind = s.indexOf(' ');

                if ((ind != -1) && (ind != (s.length() - 1))) {
                    final String key = s.substring(0, ind);
                    final String value = s.substring(ind + 1).trim();

                    if (key.startsWith(uidPref)) {
                        users.put(Integer.parseInt(key.substring(uidPref
                                .length())), value);
                    } else if (key.startsWith(pref)) {
                        config.put(key.substring(pref.length()), value);
                    }
                }
            }

            final int exit = exec.waitFor();

            if ((exit != 0) && (exit != 1)) {
                throw new ExecutionException(
                        "git config failed with exit code " + exit, exit);
            }
        } finally {
            proc.destroy();
        }
    }

    private <K, V> void save(final String pref, final Map<K, V> config)
            throws IOException, InterruptedException, ExecutionException {
        final Git git = getGit();
        final TreeMap<K, V> sort = new TreeMap<>(config);

        for (final Map.Entry<K, V> e : sort.entrySet()) {
            final String key = pref + e.getKey();
            final String value = String.valueOf(e.getValue());
            final Exec exec = git.exec("config", "--replace-all", key, value);
            final int exit = exec.exec().waitFor();

            if (exit != 0) {
                throw new ExecutionException(
                        "git config failed with exit code " + exit, exit);
            }
        }
    }

    private static <K, V> V get(final K key, final Map<K, V> m1,
            final Map<K, V> m2, final Map<K, V> m3) {
        V value = m1.get(key);

        if ((value == null) && (m2 != null)) {
            value = m2.get(key);

            if ((value == null) && (m3 != null)) {
                value = m3.get(key);
            }
        }

        if (value == null) {
            throw new ConfigurationException(
                    "Missing required configuration property: " + key);
        }

        return value;
    }

    private static <K, V> V get(final K key, final V defaultValue,
            final Map<K, V> m1, final Map<K, V> m2, final Map<K, V> m3) {
        V value = m1.get(key);

        if ((value == null) && (m2 != null)) {
            value = m2.get(key);

            if ((value == null) && (m3 != null)) {
                value = m3.get(key);
            }
        }

        return value == null ? defaultValue : value;
    }
}
