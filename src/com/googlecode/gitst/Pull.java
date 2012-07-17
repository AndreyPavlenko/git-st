package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.META_PROP_OLEDATE;
import static com.googlecode.gitst.RepoProperties.META_PROP_SHA;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_USER;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import com.googlecode.gitst.fastimport.Commit;
import com.googlecode.gitst.fastimport.CommitId;
import com.googlecode.gitst.fastimport.FastImport;
import com.googlecode.gitst.fastimport.FileData;
import com.googlecode.gitst.fastimport.Filedelete;
import com.googlecode.gitst.fastimport.Filemodify;
import com.googlecode.gitst.fastimport.Filerename;
import com.starbase.starteam.File;
import com.starbase.starteam.Folder;
import com.starbase.starteam.FolderUpdateEvent;
import com.starbase.starteam.FolderUpdateListener;
import com.starbase.starteam.Item;
import com.starbase.starteam.ItemUpdateEvent;
import com.starbase.starteam.ItemUpdateListener;
import com.starbase.starteam.View;
import com.starbase.starteam.ViewConfiguration;
import com.starbase.starteam.ViewConfigurationDiffer;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class Pull {
    private final DateFormat _logDateFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT);
    private final Repo _repo;
    private final Logger _log;

    public Pull(final Repo repo, final Logger logger) {
        _repo = repo;
        _log = logger;
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            printHelp(System.out);
        } else {
            try {
                final Args a = new Args(args);
                final String dir = a.get("-d", ".");
                final String confDir = a.get("-c", null);
                final String user = a.get("-u", null);
                final String password = a.get("-p", null);
                final RepoProperties props = new RepoProperties(
                        new java.io.File(dir), confDir == null ? null
                                : new java.io.File(confDir));

                if (user != null) {
                    props.setSessionProperty(PROP_USER, user);
                }
                if (password != null) {
                    props.setSessionProperty(PROP_PASSWORD, password);
                }

                try (final Repo repo = new Repo(props)) {
                    new Pull(repo, Logger.createConsoleLogger()).pull();
                }
            } catch (final IllegalArgumentException ex) {
                System.err.println(ex.getMessage());
                printHelp(System.err);
                System.exit(1);
            } catch (final IOException | ConfigurationException
                    | InterruptedException ex) {
                System.err.println(ex.getMessage());
                System.exit(1);
            } catch (final ExecutionException ex) {
                System.exit(ex.getExitCode());
            }
        }
    }

    public Repo getRepo() {
        return _repo;
    }

    public Logger getLogger() {
        return _log;
    }

    public void pull() throws IOException, InterruptedException,
            ExecutionException {
        final Repo repo = getRepo();

        repo.isBare();
        final Git git = repo.getGit();
        final View v = repo.connect();
        final RepoProperties props = repo.getRepoProperties();
        final ViewListener listener = new ViewListener();
        final ViewConfigurationDiffer diff = new ViewConfigurationDiffer(v);
        String ole = props.getMetaProperty(META_PROP_OLEDATE);
        String sha = props.getMetaProperty(META_PROP_SHA);
        final OLEDate startDate = (ole != null) ? new OLEDate(
                Long.parseLong(ole)) : v.getCreatedTime();
        final OLEDate endDate = new OLEDate();

        diff.addFolderUpdateListener(listener);
        diff.addItemUpdateListener(listener,
                repo.getServer().typeForName("File"));

        _log.echo("Requesting changes since " + startDate);
        _log.echo("");
        diff.compare(ViewConfiguration.createFromTime(startDate),
                ViewConfiguration.createFromTime(endDate));

        final Collection<Commit> commits = listener.getCommits().values();

        if (!commits.isEmpty()) {
            _log.echo("");
            _log.echo("Executing git fast-import");
            _log.echo("");
            new FastImport(repo, commits, getLogger()).exec();

            ole = String.valueOf(endDate.getLongValue());
            sha = git.getCurrentSha(props.getRepoDir(), repo.getBranchName());
            props.setMetaProperty(META_PROP_OLEDATE, ole);
            props.setMetaProperty(META_PROP_SHA, sha);
            props.saveMeta();

            if (!repo.isBare()) {
                git.checkout(repo.getBranchName()).exec().waitFor();
            }
        } else {
            _log.echo("No changes found");
        }
    }

    private static void printHelp(final PrintStream ps) {
        ps.println("Usage: git st pull [-u <user>] [-p password] [-d <directory>] [-c <confdir>]");
    }

    private final class ViewListener implements FolderUpdateListener,
            ItemUpdateListener {
        private final Map<CommitId, Commit> _commits = new TreeMap<>();

        public Map<CommitId, Commit> getCommits() {
            return _commits;
        }

        @Override
        public void itemAdded(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();
            final String path = getPath(f);
            final long time = f.getCreatedTime().getLongValue();
            final int user = f.getCreatedBy();
            final Commit cmt = getCommit(user, time);
            cmt.addChange(new Filemodify(path, new FileData(f), true));
            logChange(time, "A", path);
        }

        @Override
        public void itemChanged(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();
            final String path = getPath(f);
            final long time = f.getModifiedTime().getLongValue();
            final int user = f.getModifiedBy();
            final Commit cmt = getCommit(user, time);
            cmt.addChange(new Filemodify(path, new FileData(f), false));
            logChange(time, "M", path);
        }

        @Override
        public void itemMoved(final ItemUpdateEvent e) {
            final File source = (File) e.getOldItem();
            final File dest = (File) e.getNewItem();
            final String sourcePath = getPath(source);
            final String destPath = getPath(dest);
            final long time = dest.getModifiedTime().getLongValue();
            final int user = dest.getModifiedBy();
            final Commit cmt = getCommit(user, time);
            cmt.addChange(new Filerename(sourcePath, destPath, dest));
            logChange(time, "R", sourcePath + " -> " + destPath);
        }

        @Override
        public void itemRemoved(final ItemUpdateEvent e) {
            final File f = (File) e.getOldItem();
            final String path = getPath(f);
            final long time = f.getDeletedTime().getLongValue();
            final int user = f.getDeletedUserID();
            final Commit cmt = getCommit(user, time);
            cmt.addChange(new Filedelete(path));
            logChange(time, "D", path);
        }

        @Override
        public void folderAdded(final FolderUpdateEvent e) {
        }

        @Override
        public void folderChanged(final FolderUpdateEvent e) {
        }

        @Override
        public void folderMoved(final FolderUpdateEvent e) {
            final Folder source = e.getOldFolder();
            final Folder dest = e.getNewFolder();
            final String sourcePath = getPath(source);
            final String destPath = getPath(dest);
            final long time = dest.getModifiedTime().getLongValue();
            final int user = dest.getModifiedBy();
            final Commit cmt = getCommit(user, time);
            cmt.addChange(new Filerename(sourcePath, destPath, dest));
            logChange(time, "R", sourcePath + " -> " + destPath);
        }

        @Override
        public void folderRemoved(final FolderUpdateEvent e) {
            final Folder f = e.getOldFolder();
            final String path = getPath(f);
            final long time = f.getDeletedTime().getLongValue();
            final int user = f.getDeletedUserID();
            final Commit cmt = getCommit(user, time);
            cmt.addChange(new Filedelete(path));
            logChange(time, "D", path);
        }

        private Commit getCommit(final int userId, final long time) {
            final CommitId id = new CommitId(userId, time);
            Commit cmt = _commits.get(id);

            if (cmt == null) {
                cmt = new Commit(id);
                _commits.put(id, cmt);
            }

            return cmt;
        }

        private String getPath(final Item i) {
            String path;

            if (i instanceof File) {
                final File f = (File) i;
                path = f.getParentFolderHierarchy() + f.getName();
            } else {
                path = ((Folder) i).getFolderHierarchy();
            }

            path = path.replace('\\', '/');
            final int ind = path.indexOf('/');

            if (ind != -1) {
                return path.substring(ind + 1);
            } else {
                return path;
            }
        }

        private void logChange(final long time, final String type,
                final String path) {
            _log.echo(_logDateFormat.format(time) + "| " + type + ' ' + path);
        }
    }
}
