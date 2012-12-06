package com.aap.gitst;

import static com.aap.gitst.RepoProperties.PROP_PASSWORD;
import static com.aap.gitst.RepoProperties.PROP_USER;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.aap.gitst.Logger.Level;
import com.aap.gitst.Logger.ProgressBar;
import com.starbase.starteam.Folder;
import com.starbase.starteam.Item;
import com.starbase.starteam.View;
import com.starbase.util.MD5;

/**
 * @author Andrey Pavlenko
 */
public class Status {
    private static final String[] FILE_PROPS = { "Name", "MD5", "DotNotation",
            "RootObjectID", "ContentVersion" };
    private static final String[] FOLDER_PROPS = { "Name" };
    private final Repo _repo;
    private final Logger _log;

    public Status(final Repo repo) {
        _repo = repo;
        _log = repo.getLogger();
    }

    public static void main(final String[] args) {
        final Args a = new Args(args);
        final String user = a.get("-u", null);
        final String password = a.get("-p", null);
        final String dir = a.get("-d", null);
        final Level level = a.hasOption("-v") ? Level.DEBUG : Level.INFO;
        final Logger log = Logger.createConsoleLogger(level);

        try {
            final Git git = (dir == null) ? new Git() : new Git(new File(dir));
            final RepoProperties props = new RepoProperties(git, "origin");

            if (user != null) {
                props.setSessionProperty(PROP_USER, user);
            }
            if (password != null) {
                props.setSessionProperty(PROP_PASSWORD, password);
            }

            try (final Repo repo = new Repo(props, log)) {
                new Status(repo).print();
            }
        } catch (final IllegalArgumentException ex) {
            if (!log.isDebugEnabled()) {
                log.error(ex.getMessage());
            } else {
                log.error(ex.getMessage(), ex);
            }

            printHelp(log);
            System.exit(1);
        } catch (final ExecutionException ex) {
            if (!log.isDebugEnabled()) {
                log.error(ex.getMessage());
            } else {
                log.error(ex.getMessage(), ex);
            }

            System.exit(ex.getExitCode());
        } catch (final Throwable ex) {
            if (!log.isDebugEnabled()) {
                log.error(ex.getMessage());
            } else {
                log.error(ex.getMessage(), ex);
            }

            System.exit(1);
        }
    }

    public Repo getRepo() {
        return _repo;
    }

    public void print() throws IOException, InterruptedException {
        final Repo repo = getRepo();
        final View v = repo.connect();
        final Folder rootFolder = repo.getRootFolder();
        final Set<File> localFiles = new HashSet<>();

        if (_log.isDebugEnabled()) {
            _log.debug("Comparing files");
        }

        rootFolder.populateNow("File", FILE_PROPS, -1);
        rootFolder.populateNow("Folder", FOLDER_PROPS, -1);
        final ProgressBar pb = _log.createProgressBar("Comparing files",
                (int) rootFolder.countItems(v.getServer().typeForName("File"),
                        -1));
        print(rootFolder, pb, localFiles);
        pb.complete();

        if (_log.isDebugEnabled()) {
            _log.debug("Searching new files");
        }

        final File repoDir = repo.getGit().getRepoDir();
        final Set<String> ignores = repo.getGit().listIgnored();
        printNewFiles(repoDir, localFiles, ignores, repoDir.getAbsolutePath());
    }

    private void print(final Folder folder, final ProgressBar pb,
            final Set<File> localFiles) throws IOException {
        final Repo repo = getRepo();

        for (final Item i : folder.getItems("File")) {
            final com.starbase.starteam.File f = (com.starbase.starteam.File) i;
            final String path = repo.getPath(f);
            final File localFile = new File(repo.getGit().getRepoDir(), path);
            localFiles.add(localFile);

            if (localFile.isFile()) {
                final MD5 md5 = new MD5();
                md5.computeFileMD5Ex(localFile);

                if (Arrays.equals(md5.getData(), f.getMD5())) {
                    if (_log.isDebugEnabled()) {
                        _log.debug("C " + path);
                    }
                } else {
                    _log.info("M " + path);
                }
            } else if (localFile.isDirectory()) {
                _log.info("D " + path);
                _log.info("A " + path);
            } else {
                _log.info("D " + path);
            }

            pb.done(1);
        }

        for (final Folder f : folder.getSubFolders()) {
            final String path = repo.getPath(f);
            final File localDir = new File(repo.getGit().getRepoDir(), path);
            localFiles.add(localDir);

            if (localDir.isDirectory()) {
                print(f, pb, localFiles);
            } else if (localDir.isFile()) {
                _log.info("D " + path);
                _log.info("A " + path);
            } else {
                _log.info("D " + path);
            }
        }
    }

    private void printNewFiles(final File dir, final Set<File> localFiles,
            final Set<String> ignores, final String rootPath) {
        for (final File f : dir.listFiles()) {
            if (localFiles.contains(f)) {
                if (f.isDirectory()) {
                    printNewFiles(f, localFiles, ignores, rootPath);
                }
            } else {
                final String path = toRelativePath(f, rootPath);

                if (ignores.contains(path)) {
                    if (_log.isDebugEnabled()) {
                        _log.debug("I " + path);
                    }
                    continue;
                } else if (f.isFile()) {
                    if (".gitignore".equals(f.getName())) {
                        continue;
                    }
                } else if (".git".equals(f.getName()) || isEmptyDir(f)) {
                    continue;
                }

                _log.info("A " + path);
            }
        }
    }

    private static String toRelativePath(final File f, final String rootPath) {
        String path = f.getAbsolutePath();

        if (path.startsWith(rootPath)) {
            path = path.substring(rootPath.length() + 1);
        }
        if (f.isDirectory()) {
            path += '/';
        }

        return path.replace('\\', '/');
    }

    private static boolean isEmptyDir(final File dir) {
        for (final File f : dir.listFiles()) {
            if (f.isFile() || !isEmptyDir(f)) {
                return false;
            }
        }
        return true;
    }

    private static void printHelp(final Logger log) {
        log.error("Usage: git st status [-u <user>] [-p password] [-d <directory>] [-v]");
    }
}
