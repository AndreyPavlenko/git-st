package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.PROP_AUTO_LOCATE_CACHE_AGENT;
import static com.googlecode.gitst.RepoProperties.PROP_BRANCH;
import static com.googlecode.gitst.RepoProperties.PROP_CACHE_AGENT_HOST;
import static com.googlecode.gitst.RepoProperties.PROP_CACHE_AGENT_PORT;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_BRANCH;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_USER_NAME_PATTERN;
import static com.googlecode.gitst.RepoProperties.PROP_HOST;
import static com.googlecode.gitst.RepoProperties.PROP_MAX_THREADS;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_PORT;
import static com.googlecode.gitst.RepoProperties.PROP_PROJECT;
import static com.googlecode.gitst.RepoProperties.PROP_USER;
import static com.googlecode.gitst.RepoProperties.*;
import static com.googlecode.gitst.RepoProperties.PROP_VIEW;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.starbase.starteam.Project;
import com.starbase.starteam.Server;
import com.starbase.starteam.ServerInfo;
import com.starbase.starteam.User;
import com.starbase.starteam.View;

/**
 * @author Andrey Pavlenko
 */
public class Repo implements AutoCloseable {
    private final RepoProperties _repoProperties;
    private final String _host;
    private final int _port;
    private final String _projectName;
    private final String _viewName;
    private final String _userName;
    private final String _password;
    private final String _branchName;
    private final String _userNamePattern;
    private final int _maxThreads;
    private final Git _git;
    private Server _server;
    private Project _project;
    private View _view;
    private int _currentUserId;
    private ExecutorService _threadPool;

    public Repo(final RepoProperties repoProperties) {
        final String maxThreads = repoProperties.getProperty(PROP_MAX_THREADS,
                PROP_DEFAULT_MAX_THREADS);

        try {
            _maxThreads = Integer.parseInt(maxThreads);
        } catch (final NumberFormatException ex) {
            throw new ConfigurationException("The property " + PROP_MAX_THREADS
                    + " has invalid value: " + maxThreads, ex);
        }
        _userName = repoProperties.getOrRequestProperty(PROP_USER,
                "Username: ", false);
        _password = repoProperties.getOrRequestProperty(PROP_PASSWORD,
                "Password: ", true);
        _host = repoProperties.getProperty(PROP_HOST);
        _port = Integer.parseInt(repoProperties.getProperty(PROP_PORT));
        _projectName = repoProperties.getProperty(PROP_PROJECT);
        _viewName = repoProperties.getProperty(PROP_VIEW);
        _branchName = repoProperties.getProperty(PROP_BRANCH,
                PROP_DEFAULT_BRANCH);
        _userNamePattern = repoProperties.getProperty(PROP_USER_NAME_PATTERN,
                PROP_DEFAULT_USER_NAME_PATTERN);
        _repoProperties = repoProperties;
        _git = new Git(repoProperties.getRepoDir());
    }

    public synchronized View connect() {
        if (_server == null) {
            final RepoProperties props = getRepoProperties();
            final ServerInfo info = new ServerInfo();

            if (Boolean.parseBoolean(props.getProperty(
                    PROP_AUTO_LOCATE_CACHE_AGENT, "false"))) {
                info.setAutoLocateCacheAgent(true);
            } else {
                final String cah = props.getProperty(PROP_CACHE_AGENT_HOST,
                        null);
                final String cap = props.getProperty(PROP_CACHE_AGENT_PORT,
                        null);

                if (cah != null) {
                    info.setMPXCacheAgentAddress(cah);
                }
                if (cap != null) {
                    info.setMPXCacheAgentPort(Integer.parseInt(cap));
                }
            }

            info.setHost(getHost());
            info.setPort(getPort());
            info.setMPXCacheAgentThreadCount(getMaxThreads());
            _server = new Server(info);
            _server.connect();
            _currentUserId = _server.logOn(getUserName(), getPassword());

            if (_currentUserId == 0) {
                throw new ConfigurationException("Failed to login to "
                        + getHost() + ':' + getPort() + " as user "
                        + getUserName());
            }

            _project = findProject(_server, getProjectName());
            _view = findView(_project, getViewName());
        }

        return _view;
    }

    @Override
    public synchronized void close() {
        if (_server != null) {
            final Server s = _server;
            _server = null;
            _project = null;
            _view = null;
            _currentUserId = 0;

            if (_threadPool != null) {
                _threadPool.shutdown();
                _threadPool = null;
            }

            s.disconnect();
        }
    }

    public RepoProperties getRepoProperties() {
        return _repoProperties;
    }

    public String getHost() {
        return _host;
    }

    public int getPort() {
        return _port;
    }

    public String getProjectName() {
        return _projectName;
    }

    public String getViewName() {
        return _viewName;
    }

    public String getBranchName() {
        return _branchName;
    }

    public String getUserName() {
        return _userName;
    }

    public String getPassword() {
        return _password;
    }

    public String getUserNamePattern() {
        return _userNamePattern;
    }

    public Server getServer() {
        return _server;
    }

    public int getMaxThreads() {
        return _maxThreads;
    }

    public synchronized Project getProject() {
        return _project;
    }

    public synchronized View getView() {
        return _view;
    }

    public synchronized int getCurrentUserId() {
        return _currentUserId;
    }

    public synchronized ExecutorService getThreadPool() {
        if (_threadPool == null) {
            _threadPool = Executors.newFixedThreadPool(getMaxThreads());
        }
        return _threadPool;
    }

    public Git getGit() {
        return _git;
    }

    public boolean isBare() {
        final RepoProperties props = getRepoProperties();
        return props.getGitstDir().getParentFile().equals(props.getRepoDir());
    }

    public String toCommitter(final int userId) {
        String name = getRepoProperties().getUserMapping(userId);

        if (name != null) {
            return name;
        }

        final View v = getView();

        if (v != null) {
            final User user = getServer().getUser(userId);

            if (user != null) {
                name = user.getName();
            }
        }
        if (name == null) {
            name = "Unknown User";
        }

        final List<String> l = new ArrayList<>();
        l.add(name);

        for (final StringTokenizer st = new StringTokenizer(name, " ,"); st
                .hasMoreTokens();) {
            l.add(st.nextToken());
        }

        name = MessageFormat.format(getUserNamePattern(), l.toArray());
        getRepoProperties().setSessionUserMapping(userId, name);
        return name;
    }

    private static Project findProject(final Server s, final String name) {
        for (final Project p : s.getProjects()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }

        throw new ConfigurationException("No such project: " + name);
    }

    private static View findView(final Project p, final String name) {
        for (final View v : p.getViews()) {
            if (name.equals(v.getName())) {
                return v;
            }
        }

        throw new ConfigurationException("No such view: " + name);
    }
}
