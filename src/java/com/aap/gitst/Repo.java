package com.aap.gitst;

import static com.aap.gitst.RepoProperties.PROP_CA;
import static com.aap.gitst.RepoProperties.PROP_CATHREADS;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_CATHREADS;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_IGNORE;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_USER_PATTERN;
import static com.aap.gitst.RepoProperties.PROP_IGNORE;
import static com.aap.gitst.RepoProperties.PROP_PASSWORD;
import static com.aap.gitst.RepoProperties.PROP_URL;
import static com.aap.gitst.RepoProperties.PROP_USER;
import static com.aap.gitst.RepoProperties.PROP_USER_PATTERN;
import static com.starbase.starteam.ServerConfiguration.PROTOCOL_TCP_IP_SOCKETS_XML;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.starbase.starteam.CheckinManager;
import com.starbase.starteam.CheckinOptions;
import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.CheckoutOptions;
import com.starbase.starteam.Folder;
import com.starbase.starteam.Item;
import com.starbase.starteam.LogonException;
import com.starbase.starteam.Project;
import com.starbase.starteam.Server;
import com.starbase.starteam.ServerInfo;
import com.starbase.starteam.StarTeamFinder;
import com.starbase.starteam.StarTeamURL;
import com.starbase.starteam.User;
import com.starbase.starteam.View;
import com.starbase.starteam.ViewConfiguration;
import com.starbase.starteam.vts.comm.NetMonitor;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class Repo implements AutoCloseable {
    private static final String FOOTER_START = "----------------------------------- StarTeam -----------------------------------";
    private static final String FOOTER_END = "--------------------------------------------------------------------------------";
    public static final String LS = System.getProperty("line.separator");
    public static final String DATE_FORMAT = "dd.MM.yy HH:mm:ss";
    private final RepoProperties _repoProperties;
    private final Logger _logger;
    private final ConnectionPool _pool;
    private final List<View> _viewCache;
    private final String _branchName;
    private final String _userNamePattern;
    private final Map<String, Folder> _folderCache;
    private final Map<String, com.starbase.starteam.File> _fileCache;
    private final MessageFormat _commentFormat;
    private volatile List<Pattern> _ignoreFiles;
    private View _view;
    private Folder _rootFolder;
    private File _tempDir;

    public Repo(final RepoProperties repoProperties, final Logger logger) {
        _branchName = repoProperties.getBranchName();
        _userNamePattern = repoProperties.getProperty(PROP_USER_PATTERN,
                PROP_DEFAULT_USER_PATTERN);
        _repoProperties = repoProperties;
        _logger = logger;
        _pool = new ConnectionPool();
        _viewCache = new ArrayList<>();
        _folderCache = new ConcurrentHashMap<>();
        _fileCache = new ConcurrentHashMap<>();
        //@formatter:off
        _commentFormat = new MessageFormat(
           "{0}\n\n"
         + FOOTER_START + '\n'
         + "{1}\n"
         + FOOTER_END);
      //@formatter:on
    }

    public Server getIdleConnection() {
        return _pool.get();
    }

    public void releaseConnection(final Server s) {
        _pool.release(s);
    }

    private Server createNewConnection() {
        final StarTeamURL url = getUrl();
        final RepoProperties props = getRepoProperties();
        final int port = Integer.parseInt(url.getPort());
        final int protocol = url.getProtocol();
        final String host = url.getHostName();
        final String ca = props.getProperty(PROP_CA, null);
        final ServerInfo info = new ServerInfo();
        String userName = props.getProperty(PROP_USER, null);
        String password = props.getProperty(PROP_PASSWORD, null);
        final Server server;

        if (userName == null) {
            userName = url.getUserName();
        }
        if (password == null) {
            password = url.getPassword();
        }
        if (ca != null) {
            if (Boolean.parseBoolean(ca)) {
                info.setAutoLocateCacheAgent(true);
            } else {
                final int ind = ca.indexOf(':');

                if (ind == -1) {
                    info.setMPXCacheAgentAddress(ca);
                } else {
                    info.setMPXCacheAgentAddress(ca.substring(0, ind));
                    info.setMPXCacheAgentPort(Integer.parseInt(ca
                            .substring(ind + 1)));
                }
            }
        }

        info.setHost(host);
        info.setPort(port);
        info.setCompression(true);
        info.setConnectionType(protocol);
        info.setMPXCacheAgentThreadCount(Integer.parseInt(props.getProperty(
                PROP_CATHREADS, PROP_DEFAULT_CATHREADS)));
        info.setEnableCacheAgentForFileContent(ca != null);
        info.setEnableCacheAgentForObjectProperties(ca != null);
        server = new Server(info);
        server.connect();

        if ((userName != null) && (password != null)) {
            if (server.logOn(userName, password) == 0) {
                throw new ConfigurationException("Failed to login to " + host
                        + ':' + port + " as user " + userName);
            }
            cacheLogOnCredentials(server, userName, password);
        } else if (autoLogOn(server) == 0) {
            final CredentialHelper h = getGit().getCredentialHelper(
                    getProtocol(url), host, userName);

            if (h != null) {
                final boolean[] approved = new boolean[1];
                h.getCredentials(new CredentialCallBack() {
                    @Override
                    public boolean approve(final String user,
                            final String password) {
                        try {
                            if (server.logOn(user, password) != 0) {
                                cacheLogOnCredentials(server, user, password);
                                props.setSessionProperty(PROP_USER, user);
                                props.setSessionProperty(PROP_PASSWORD,
                                        password);
                                return approved[0] = true;
                            } else {
                                return approved[0] = false;
                            }
                        } catch (final LogonException ex) {
                            return approved[0] = false;
                        }
                    }
                });

                if (!approved[0]) {
                    throw new ConfigurationException("Failed to login to "
                            + host + ':' + port);
                }
            } else {
                if (userName == null) {
                    userName = props.getOrRequestProperty(PROP_USER,
                            "Username: ", false);
                }
                if (password == null) {
                    password = props.getOrRequestProperty(PROP_PASSWORD,
                            "Password: ", true);
                }
                if (server.logOn(userName, password) == 0) {
                    throw new ConfigurationException("Failed to login to "
                            + host + ':' + port + " as user " + userName);
                }
                cacheLogOnCredentials(server, userName, password);
            }
        }

        return server;
    }

    public synchronized View connect() {
        if (_view == null) {
            final RepoProperties props = getRepoProperties();
            final StarTeamURL url = getUrl();
            final String project = url.getProjectName();
            final String view = getViewName(url);
            final Server server = getIdleConnection();
            boolean ok = false;

            try {
                if (getLogger().isDebugEnabled()) {
                    final java.io.File dir = new File(props.getGitstDir(),
                            getBranchName());
                    dir.mkdirs();
                    NetMonitor.onFile(new java.io.File(dir, "NetMonitor.log"));
                }

                if (getLogger().isInfoEnabled()) {
                    final int port = Integer.parseInt(url.getPort());
                    final String host = url.getHostName();
                    getLogger().info(
                            "Connecting to " + host + ':' + port + '/'
                                    + project + '/' + view);
                }

                _view = findView(findProject(server, project), view);
                _rootFolder = getRootFolder(url, _view);
                ok = true;
            } finally {
                if (ok) {
                    releaseConnection(server);
                } else {
                    close();
                    _pool.clear();
                }
            }
        }

        return _view;
    }

    @Override
    public synchronized void close() {
        if (_view != null) {
            close(_view);

            for (final View v : _viewCache) {
                close(v);
            }

            _view = null;
            _rootFolder = null;
            _pool.clear();
            _viewCache.clear();
        }
    }

    private void close(final View v) {
        try {
            v.close();
        } catch (final Throwable ex) {
            _logger.error("Failed to close View", ex);
        }
    }

    public RepoProperties getRepoProperties() {
        return _repoProperties;
    }

    public Logger getLogger() {
        return _logger;
    }

    public String getBranchName() {
        return _branchName;
    }

    public String getUserNamePattern() {
        return _userNamePattern;
    }

    public Server getServer() {
        return getView().getServer();
    }

    public Project getProject() {
        return getView().getProject();
    }

    public View getView() {
        return connect();
    }

    public synchronized View getView(final OLEDate fromDate,
            final OLEDate toDate) {
        final double from = fromDate.getDoubleValue();
        final double to = toDate.getDoubleValue();

        for (final View v : _viewCache) {
            final double date = v.getConfiguration().getTime().getDoubleValue();

            if ((date >= from) && (date <= to)) {
                return v;
            }
        }

        if (_logger.isDebugEnabled()) {
            _logger.debug("Creating view as of: " + toDate + ". Cache size: "
                    + _viewCache.size() + ".");
        }

        final View v = new View(getView(),
                ViewConfiguration.createFromTime(toDate));
        _viewCache.add(v);
        return v;
    }

    public synchronized Folder getRootFolder() {
        if (_rootFolder == null) {
            connect();
        }
        return _rootFolder;
    }

    public Folder getRootFolder(final View v) {
        return getRootFolder(getUrl(), v);
    }

    public synchronized File getTempDir() throws IOException {
        if (_tempDir == null) {
            _tempDir = createFile(getRepoProperties().getGitstDir(), "tmp");

            if (!_tempDir.mkdirs()) {
                throw new IOException("Failed to create directory: "
                        + _tempDir.getAbsolutePath());
            }

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
        return getRepoProperties().getGit();
    }

    public List<Pattern> getIgnoreFiles() {
        if (_ignoreFiles == null) {
            final List<Pattern> l = new ArrayList<>();
            final String ignores = getRepoProperties().getProperty(PROP_IGNORE,
                    PROP_DEFAULT_IGNORE);

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

    public boolean isEmpty(final Folder f) {
        return (f.getItems("File").length == 0)
                && (f.getItems("Folder").length == 0);
    }

    public Folder getFolder(final String path) {
        Folder f = _folderCache.get(path);

        if (f == null) {
            final Folder root = getRootFolder();
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
            return getRootFolder();
        } else {
            return getFolder(path.substring(0, slash));
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
                folder = getRootFolder();
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

    public synchronized Folder getOrCreateFolder(final String path) {
        Folder f = _folderCache.get(path);

        if (f == null) {
            final Folder root = getRootFolder();
            f = StarTeamFinder.findFolder(root, path);

            if (f == null) {
                final int ind = path.lastIndexOf('/');

                if (ind != -1) {
                    f = getOrCreateFolder(path.substring(0, ind));
                    f = new Folder(f);
                    f.setName(path.substring(ind + 1));
                    f.update();
                } else {
                    f = new Folder(root);
                    f.setName(path);
                    f.update();
                }
            }

            _folderCache.put(path, f);
        }

        return f;
    }

    public synchronized com.starbase.starteam.File getOrCreateFile(
            final String path) {
        com.starbase.starteam.File f = _fileCache.get(path);

        if (f == null) {
            final int slash = path.lastIndexOf('/');
            final String name;
            final Folder folder;

            if (slash == -1) {
                name = path;
                folder = getRootFolder();
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

    public synchronized void rename(final Item i, final String dest,
            final String comment) {
        final String oldPath = getPath(i);
        i.setComment(comment);
        i.moveTo(getOrCreateParentFolder(dest));

        if (i instanceof com.starbase.starteam.File) {
            final com.starbase.starteam.File f = (com.starbase.starteam.File) i;
            f.setName(Repo.getFileName(dest));
            f.update();
            _fileCache.remove(oldPath);
            _fileCache.put(getPath(f), f);
        } else {
            final Folder f = (Folder) i;
            f.setName(Repo.getFileName(dest));
            f.update();
            _folderCache.remove(oldPath);
            _folderCache.put(getPath(f), f);
        }
    }

    public MessageFormat getCommentFormat() {
        return _commentFormat;
    }

    public boolean isGitStComment(final String comment) {
        return comment.endsWith(FOOTER_END) && comment.contains(FOOTER_START);
    }

    public String getPrevImportSha() throws InterruptedException, IOException {
        final Git git = getGit();
        final Exec exec = git.exec("log", "--grep=^" + FOOTER_START + "$",
                "-1", "--pretty=format:%H", getBranchName());
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        exec.setOutStream(baos);
        exec.exec().waitFor();
        return (baos.size() == 0) ? null : new String(baos.toByteArray());
    }

    public synchronized CheckoutManager createCheckoutManager() {
        final View view = getView();
        final CheckoutOptions co = new CheckoutOptions(view);
        co.setOptimizeForSlowConnections(true);
        co.setUpdateStatus(false);
        co.setForceCheckout(true);
        co.setRestoreFileOnError(false);
        return view.createCheckoutManager(co);
    }

    public CheckinManager createCheckinManager(final String reason) {
        final View view = getView();
        final CheckinOptions cio = new CheckinOptions(view);
        cio.setForceCheckin(true);
        cio.setAtomicCheckInDisabled(false);
        cio.setCheckinReason(reason);
        return view.createCheckinManager(cio);
    }

    public String getPath(final Item i) {
        final Folder root = getRootFolder();
        final StringBuilder path = new StringBuilder();

        if (i instanceof com.starbase.starteam.File) {
            path.append(((com.starbase.starteam.File) i).getName());
        } else if (i instanceof com.starbase.starteam.Folder) {
            path.append(((com.starbase.starteam.Folder) i).getName());
        } else {
            throw new IllegalArgumentException("Unsupported item: " + i);
        }

        for (Folder f = i.getParentFolder(); (f != null) && !f.equals(root); f = f
                .getParentFolder()) {
            path.insert(0, '/').insert(0, f.getName());
        }

        return path.toString().intern();
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

    public String toCommitter(final int userId) {
        String name = getRepoProperties().getUserMapping(userId);

        if (name != null) {
            return name;
        }

        final View v = getView();

        if (v != null) {
            final User user = getView().getServer().getUser(userId);

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

    private static String getFileName(final String path) {
        final int slash = path.lastIndexOf('/');

        if (slash == -1) {
            return path;
        } else if (slash == (path.length() - 1)) {
            return "";
        } else {
            return path.substring(slash + 1);
        }
    }

    private Folder getOrCreateParentFolder(final String path) {
        final int slash = path.lastIndexOf('/');

        if (slash == -1) {
            return getRootFolder();
        } else {
            return getOrCreateFolder(path.substring(0, slash));
        }
    }

    private static File createFile(final File dir, final String path) {
        File f = new File(dir, path);
        for (int i = 1; f.exists(); i++) {
            f = new File(dir, path + i);
        }
        return f;
    }

    private static String getViewName(final StarTeamURL url) {
        final String src = url.getSource();
        final String proj = '/' + url.getProjectName() + '/';
        int ind = src.indexOf(proj);

        if (ind != -1) {
            String view = src.substring(ind + proj.length());

            ind = view.indexOf('/');

            if (ind != -1) {
                view = view.substring(0, ind);
            }

            return view;
        }

        return "main";
    }

    private static Folder getRootFolder(final StarTeamURL url, final View v) {
        Folder f = v.getRootFolder();
        boolean skipViewName = true;

        stLoop: for (final StringTokenizer st = url.getFolders(); st
                .hasMoreTokens();) {
            final String name = st.nextToken();

            if (skipViewName) {
                skipViewName = false;
                if (name.equals(v.getName())) {
                    continue;
                }
            }

            for (final Folder sub : f.getSubFolders()) {
                if (name.equals(sub.getName())) {
                    f = sub;
                    continue stLoop;
                }
            }

            throw new IllegalArgumentException("Invalid url '"
                    + url.getSource() + "': folder " + name
                    + " does not exist.");
        }

        return f;
    }

    private int autoLogOn(final Server server) {
        try {
            // Without synchronized autoLogOn() sometimes fails on concurrent
            // access.
            synchronized (Repo.class) {
                return server.autoLogOn();
            }
        } catch (final Throwable ex) {
            if (_logger.isDebugEnabled()) {
                _logger.error(ex.getMessage(), ex);
            }
            return 0;
        }
    }

    private static void cacheLogOnCredentials(final Server server,
            final String userName, final String password) {
        try {
            server.cacheLogOnCredentials(userName, password);
        } catch (final Throwable ex) {
        }
    }

    private StarTeamURL getUrl() {
        final String url = getRepoProperties().getProperty(PROP_URL);

        if (url.startsWith("st::")) {
            return new StarTeamURL(url.substring(4));
        } else {
            return new StarTeamURL(url);
        }
    }

    private static String getProtocol(final StarTeamURL url) {
        switch (url.getProtocol()) {
        case PROTOCOL_TCP_IP_SOCKETS_XML:
            return "starteam:xml";
        default:
            return "starteam";
        }
    }

    private final class ConnectionPool extends SimpleObjectPool<Server> {

        @Override
        protected Server create() {
            return createNewConnection();
        }

        @Override
        protected void destroy(final Server s) {
            s.disconnect();
        }
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
                final File[] files = file.listFiles();

                if (files != null) {
                    for (final File f : files) {
                        delete(f);
                    }
                }
            }

            file.delete();
        }
    }
}
