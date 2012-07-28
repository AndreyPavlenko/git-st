package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.PROP_CA;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_IGNORE;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_THREADS;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_USER_PATTERN;
import static com.googlecode.gitst.RepoProperties.PROP_IGNORE;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_THREADS;
import static com.googlecode.gitst.RepoProperties.PROP_URL;
import static com.googlecode.gitst.RepoProperties.PROP_USER;
import static com.googlecode.gitst.RepoProperties.PROP_USER_PATTERN;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
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
import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.CheckoutOptions;
import com.starbase.starteam.Folder;
import com.starbase.starteam.Item;
import com.starbase.starteam.Project;
import com.starbase.starteam.Server;
import com.starbase.starteam.ServerInfo;
import com.starbase.starteam.StarTeamFinder;
import com.starbase.starteam.StarTeamURL;
import com.starbase.starteam.User;
import com.starbase.starteam.View;

/**
 * @author Andrey Pavlenko
 */
public class Repo implements AutoCloseable {
    private static final String FOOTER_START = "----------------------------------- StarTeam -----------------------------------";
    private static final String FOOTER_END = "--------------------------------------------------------------------------------";
    public static final String LS = System.getProperty("line.separator");
    public static final DateFormat DATE_FORMAT = new SimpleDateFormat(
            "dd.MM.yy HH:mm:ss");
    private final RepoProperties _repoProperties;
    private final Logger _logger;
    private final String _branchName;
    private final String _remoteBranchName;
    private final String _userNamePattern;
    private final Map<String, Folder> _folderCache;
    private final Map<String, com.starbase.starteam.File> _fileCache;
    private final MessageFormat _commentFormat;
    private volatile List<Pattern> _ignoreFiles;
    private View _view;
    private ExecutorService _threadPool;
    private File _tempDir;

    public Repo(final RepoProperties repoProperties, final Logger logger) {
        _branchName = repoProperties.getBranchName();
        _remoteBranchName = repoProperties.getRemoteBranchName();
        _userNamePattern = repoProperties.getProperty(PROP_USER_PATTERN,
                PROP_DEFAULT_USER_PATTERN);
        _repoProperties = repoProperties;
        _logger = logger;
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

    public synchronized View connect() {
        if (_view == null) {
            final RepoProperties props = getRepoProperties();
            final StarTeamURL url = getUrl();
            final int port = Integer.parseInt(url.getPort());
            final int protocol = url.getProtocol();
            final String host = url.getHostName();
            final String project = url.getProjectName();
            final String view = getViewName(url);
            final String ca = props.getProperty(PROP_CA, null);
            final ServerInfo info = new ServerInfo();
            String userName = url.getUserName();
            String password = url.getPassword();
            Server server;

            if (userName == null) {
                userName = props.getOrRequestProperty(PROP_USER, "Username: ",
                        false);
            }
            if (password == null) {
                password = props.getOrRequestProperty(PROP_PASSWORD,
                        "Password: ", true);
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
            if (getLogger().isInfoEnabled()) {
                getLogger().info(
                        "Connecting to " + userName + '@' + host + ':' + port
                                + '/' + project + '/' + view);
            }

            info.setHost(host);
            info.setPort(port);
            info.setCompression(true);
            info.setConnectionType(protocol);
            info.setMPXCacheAgentThreadCount(Integer.parseInt(props
                    .getProperty(PROP_THREADS, PROP_DEFAULT_THREADS)));
            info.setEnableCacheAgentForFileContent(ca != null);
            info.setEnableCacheAgentForObjectProperties(ca != null);
            server = new Server(info);
            server.connect();

            if (server.logOn(userName, password) == 0) {
                throw new ConfigurationException("Failed to login to " + host
                        + ':' + port + " as user " + userName);
            }

            _view = findView(findProject(server, project), view);
        }

        return _view;
    }

    @Override
    public synchronized void close() {
        if (_view != null) {
            final View view = _view;
            _view = null;

            if (_threadPool != null) {
                _threadPool.shutdown();
                _threadPool = null;
            }

            view.getServer().disconnect();
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

    public String getRemoteBranchName() {
        return _remoteBranchName;
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

    public synchronized ExecutorService getThreadPool() {
        if ((_threadPool == null) || _threadPool.isShutdown()) {
            _threadPool = Executors.newFixedThreadPool(Integer
                    .parseInt(getRepoProperties().getProperty(PROP_THREADS,
                            PROP_DEFAULT_THREADS)));
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

    public static String getFileName(final String path) {
        final int slash = path.lastIndexOf('/');

        if ((slash == -1) || (slash == (path.length() - 1))) {
            return "";
        } else {
            return path.substring(slash + 1);
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

    public MessageFormat getCommentFormat() {
        return _commentFormat;
    }

    public boolean isGitStComment(final String comment) {
        return comment.endsWith(FOOTER_END) && comment.contains(FOOTER_START);
    }

    public synchronized CheckoutManager createCheckoutManager() {
        final View view = getView();
        final CheckoutOptions co = new CheckoutOptions(view);
        co.setEOLConversionEnabled(false);
        co.setOptimizeForSlowConnections(true);
        co.setUpdateStatus(false);
        co.setForceCheckout(true);
        return view.createCheckoutManager(co);
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

    public static void copyFile(final java.io.File from, final java.io.File to)
            throws FileNotFoundException, IOException {
        try (FileInputStream in = new FileInputStream(from);
                FileOutputStream out = new FileOutputStream(to);
                FileChannel inc = in.getChannel();
                FileChannel outc = out.getChannel();) {
            final int maxCount = (64 * 1024 * 1024) - (32 * 1024);
            final long size = inc.size();
            long position = 0;
            while (position < size) {
                position += inc.transferTo(position, maxCount, outc);
            }
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

    private StarTeamURL getUrl() {
        final String url = getRepoProperties().getProperty(PROP_URL);

        if (url.startsWith("st::")) {
            return new StarTeamURL(url.substring(4));
        } else {
            return new StarTeamURL(url);
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
                for (final File f : file.listFiles()) {
                    delete(f);
                }
            }
            file.delete();
        }
    }
}
