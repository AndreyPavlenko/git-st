package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.PROP_AUTO_LOCATE_CACHE_AGENT;
import static com.googlecode.gitst.RepoProperties.PROP_BRANCH;
import static com.googlecode.gitst.RepoProperties.PROP_CACHE_AGENT_HOST;
import static com.googlecode.gitst.RepoProperties.PROP_CACHE_AGENT_PORT;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_BRANCH;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_IGNORE_FILES;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_MAX_THREADS;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_USER_NAME_PATTERN;
import static com.googlecode.gitst.RepoProperties.PROP_HOST;
import static com.googlecode.gitst.RepoProperties.PROP_IGNORE_FILES;
import static com.googlecode.gitst.RepoProperties.PROP_MAX_THREADS;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_PORT;
import static com.googlecode.gitst.RepoProperties.PROP_PROJECT;
import static com.googlecode.gitst.RepoProperties.PROP_USER;
import static com.googlecode.gitst.RepoProperties.PROP_USER_NAME_PATTERN;
import static com.googlecode.gitst.RepoProperties.PROP_VIEW;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.starbase.starteam.CheckinManager;
import com.starbase.starteam.CheckinOptions;
import com.starbase.starteam.Folder;
import com.starbase.starteam.Item;
import com.starbase.starteam.Project;
import com.starbase.starteam.Server;
import com.starbase.starteam.ServerInfo;
import com.starbase.starteam.StarTeamFinder;
import com.starbase.starteam.User;
import com.starbase.starteam.View;

/**
 * @author Andrey Pavlenko
 */
public class Repo implements AutoCloseable {
    private final RepoProperties _repoProperties;
    private final Logger _logger;
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
    private final DateFormat _dateTimeFormat;
    private final Map<String, Folder> _folderCache;
    private final Map<String, com.starbase.starteam.File> _fileCache;
    private volatile List<Pattern> _ignoreFiles;
    private Server _server;
    private Project _project;
    private View _view;
    private int _currentUserId;
    private ExecutorService _threadPool;
    private File _tempDir;
    private Marks _marks;

    public Repo(final RepoProperties repoProperties, final Logger logger) {
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
        _git = new Git(repoProperties.getRepoDir());
        _dateTimeFormat = new SimpleDateFormat("dd.MM.yy HH:mm:ss");
        _repoProperties = repoProperties;
        _logger = logger;
        _folderCache = new ConcurrentHashMap<>();
        _fileCache = new ConcurrentHashMap<>();
    }

    public synchronized View connect() {
        if (_server == null) {
            final RepoProperties props = getRepoProperties();
            final ServerInfo info = new ServerInfo();
            boolean caEnabled = false;

            if (Boolean.parseBoolean(props.getProperty(
                    PROP_AUTO_LOCATE_CACHE_AGENT, "false"))) {
                caEnabled = true;
                info.setAutoLocateCacheAgent(true);
            } else {
                final String cah = props.getProperty(PROP_CACHE_AGENT_HOST,
                        null);
                final String cap = props.getProperty(PROP_CACHE_AGENT_PORT,
                        null);

                if (cah != null) {
                    caEnabled = true;
                    info.setMPXCacheAgentAddress(cah);
                }
                if (cap != null) {
                    info.setMPXCacheAgentPort(Integer.parseInt(cap));
                }
            }

            info.setHost(getHost());
            info.setPort(getPort());
            info.setCompression(true);
            info.setMPXCacheAgentThreadCount(getMaxThreads());
            info.setEnableCacheAgentForFileContent(caEnabled);
            info.setEnableCacheAgentForObjectProperties(caEnabled);
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

    public Logger getLogger() {
        return _logger;
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

    public int getMaxThreads() {
        return _maxThreads;
    }

    public synchronized Server getServer() {
        connect();
        return _server;
    }

    public synchronized Project getProject() {
        connect();
        return _project;
    }

    public synchronized View getView() {
        return connect();
    }

    public synchronized int getCurrentUserId() {
        return _currentUserId;
    }

    public synchronized ExecutorService getThreadPool() {
        if (_threadPool == null) {
            final int n = getMaxThreads();

            if (n > 0) {
                _threadPool = Executors.newFixedThreadPool(n);
            }
        }
        return _threadPool;
    }

    public synchronized File getTempDir() {
        if (_tempDir == null) {
            _tempDir = createFile(getRepoProperties().getGitstDir(), "tmp");
            _tempDir.mkdirs();
            Runtime.getRuntime().addShutdownHook(new DeleteHook(_tempDir));
        }
        return _tempDir;
    }

    public File createTempFile(final String path) throws IOException {
        final File f = createFile(getTempDir(), path);
        f.getParentFile().mkdirs();
        f.createNewFile();
        return f;
    }

    public Git getGit() {
        return _git;
    }

    public DateFormat getDateTimeFormat() {
        return _dateTimeFormat;
    }

    public List<Pattern> getIgnoreFiles() {
        if (_ignoreFiles == null) {
            final List<Pattern> l = new ArrayList<>();
            final String ignores = getRepoProperties().getProperty(
                    PROP_IGNORE_FILES, PROP_DEFAULT_IGNORE_FILES);

            for (final StringTokenizer st = new StringTokenizer(ignores, ";"); st
                    .hasMoreTokens();) {
                l.add(Pattern.compile(st.nextToken()));
            }

            _ignoreFiles = l;
        }

        return _ignoreFiles;
    }

    public boolean isIgnored(final String file) {
        for (final Pattern p : getIgnoreFiles()) {
            if (p.matcher(file).matches()) {
                return true;
            }
        }
        return false;
    }

    public Folder getFolder(final String path) {
        Folder f = _folderCache.get(path);

        if (f == null) {
            final Folder root = getView().getRootFolder();
            f = StarTeamFinder.findFolder(root, path);

            if (f != null) {
                _folderCache.put(path, f);
            }
        }

        return f;
    }

    public Folder getParentFolder(final String path) {
        final int slash = path.lastIndexOf('/');

        if (slash == -1) {
            return getView().getRootFolder();
        } else {
            return getFolder(path.substring(0, slash));
        }
    }

    public static String getParentFolderPath(final String path) {
        final int slash = path.lastIndexOf('/');

        if (slash == -1) {
            return "";
        } else {
            return path.substring(0, slash);
        }
    }

    public Folder getOrCreateParentFolder(final String path) {
        final int slash = path.lastIndexOf('/');

        if (slash == -1) {
            return getView().getRootFolder();
        } else {
            return getOrCreateFolder(path.substring(0, slash));
        }
    }

    public com.starbase.starteam.File getFile(final String path) {
        com.starbase.starteam.File f = _fileCache.get(path);

        if (f == null) {
            final int slash = path.lastIndexOf('/');
            final String name;
            final Folder folder;

            if (slash == -1) {
                name = path;
                folder = getView().getRootFolder();
            } else {
                name = path.substring(slash + 1);
                folder = getFolder(path.substring(0, slash));

                if (folder == null) {
                    return null;
                }
            }

            f = StarTeamFinder.findFile(folder, name, true);

            if (f != null) {
                _fileCache.put(path, f);
            }
        }

        return f;
    }

    public Folder getOrCreateFolder(final String path) {
        Folder f = _folderCache.get(path);

        if (f == null) {
            final Folder root = getView().getRootFolder();
            f = StarTeamFinder.findFolder(root, path);

            if (f == null) {
                final int ind = path.lastIndexOf('/');

                if (ind != -1) {
                    f = getOrCreateFolder(path.substring(0, ind));
                    f = new Folder(f);
                    f.setName(path.substring(ind + 1));
                } else {
                    f = new Folder(root);
                    f.setName(path);
                }
            }

            _folderCache.put(path, f);
        }

        return f;
    }

    public com.starbase.starteam.File getOrCreateFile(final String path) {
        com.starbase.starteam.File f = _fileCache.get(path);

        if (f == null) {
            final int slash = path.lastIndexOf('/');
            final String name;
            final Folder folder;

            if (slash == -1) {
                name = path;
                folder = getView().getRootFolder();
            } else {
                name = path.substring(slash + 1);
                folder = getOrCreateFolder(path.substring(0, slash));
            }

            f = StarTeamFinder.findFile(folder, name, true);

            if (f == null) {
                f = new com.starbase.starteam.File(folder);
                f.setName(name);
            }

            _fileCache.put(path, f);
        }

        return f;
    }

    public synchronized Marks getMarks() throws IOException {
        if (_marks == null) {
            final File marksFile = getGit().getMarksFile();
            _marks = marksFile.exists() ? new Marks(marksFile) : new Marks();
        }
        return _marks;
    }

    public CheckinManager createCheckinManager(final String reason) {
        final View view = getView();
        final CheckinOptions cio = new CheckinOptions(view);
        cio.setForceCheckin(true);
        cio.setEOLConversionEnabled(false);
        cio.setAtomicCheckInDisabled(false);
        cio.setCheckinReason(reason);
        return view.createCheckinManager(cio);
    }

    public static String getPath(final Item i) {
        String path;

        if (i instanceof com.starbase.starteam.File) {
            final com.starbase.starteam.File f = (com.starbase.starteam.File) i;
            path = f.getParentFolderHierarchy() + f.getName();
        } else {
            path = ((com.starbase.starteam.Folder) i).getFolderHierarchy();
        }

        path = path.replace('\\', '/');
        final int ind = path.indexOf('/');

        if (ind != -1) {
            return path.substring(ind + 1);
        } else {
            return path;
        }
    }

    public static String quotePath(final String path) {
        if (path.indexOf(' ') != -1) {
            return '"' + path + '"';
        } else {
            return path;
        }
    }

    public static String unquotePath(final String path) {
        final int len = path.length();

        if ((len > 2) && (path.charAt(0) == '"')
                && (path.charAt(len - 1) == '"')) {
            return path.substring(1, len - 1);
        } else {
            return path;
        }
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
            final String next = st.nextToken();
            l.add(next);
            l.add(next.toLowerCase());
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

    private static File createFile(final File dir, final String path) {
        File f = new File(dir, path);
        for (int i = 1; f.exists(); i++) {
            f = new File(dir, path + i);
        }
        return f;
    }

    private static final class DeleteHook extends Thread {
        private final File _file;

        public DeleteHook(final File file) {
            _file = file;
        }

        @Override
        public void run() {
            delete(_file);
        }

        private static void delete(final File file) {
            if (file.isDirectory()) {
                for (final File f : file.listFiles()) {
                    delete(f);
                }
            }
            file.delete();
        }
    }
}
