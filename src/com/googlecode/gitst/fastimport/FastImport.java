package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

import com.googlecode.gitst.Exec;
import com.googlecode.gitst.ExecutionException;
import com.googlecode.gitst.Git;
import com.googlecode.gitst.Logger;
import com.googlecode.gitst.Logger.ProgressBar;
import com.googlecode.gitst.Repo;
import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.CheckoutOptions;
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
public class FastImport {
    private final DateFormat _logDateFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT);
    private final Repo _repo;
    private final Logger _log;

    public FastImport(final Repo repo, final Logger logger) {
        _repo = repo;
        _log = logger;
    }

    public Repo getRepo() {
        return _repo;
    }

    public Logger getLogger() {
        return _log;
    }

    public Collection<Commit> loadChanges(final OLEDate startDate,
            final OLEDate endDate, final boolean checkout) {
        final Repo repo = getRepo();
        final View v = repo.connect();
        final ViewListener listener = new ViewListener(checkout);
        final ViewConfigurationDiffer diff = new ViewConfigurationDiffer(v);

        _log.echo("Requesting changes since " + startDate);
        _log.echo();

        diff.addFolderUpdateListener(listener);
        diff.addItemUpdateListener(listener,
                repo.getServer().typeForName("File"));
        diff.compare(ViewConfiguration.createFromTime(startDate),
                ViewConfiguration.createFromTime(endDate));

        return listener.getCommits().values();
    }

    public void exec(final Collection<Commit> commits) throws IOException,
            InterruptedException, ExecutionException {
        _log.echo("Executing git fast-import");
        final Repo repo = getRepo();
        final Git git = repo.getGit();
        long mark = git.getLatestMark();
        final Exec exec = git.fastImport().exec();
        final Process proc = exec.getProcess();

        try {
            final String branch = repo.getBranchName();
            final ProgressBar b = _log.createProgressBar(
                    "Importing to git...    ", commits.size());

            try (final PrintStream s = new PrintStream(proc.getOutputStream())) {
                for (final Commit cmt : commits) {
                    commit(cmt, ++mark, branch, s);
                    b.done(1);
                }

                _log.echo();
            }

            if (exec.waitFor() != 0) {
                throw new ExecutionException(
                        "git fast-import failed with exit code: "
                                + proc.exitValue(), proc.exitValue());
            }
        } finally {
            proc.destroy();
        }
    }

    private void commit(final Commit cmt, final long mark, final String branch,
            final PrintStream s) throws IOException {
        final CommitId id = cmt.getId();
        final String committer = getRepo().toCommitter(id.getUserId());

        _log.echo(_logDateFormat.format(id.getTime()) + " " + committer + ':');
        _log.echo(cmt.getComment());
        _log.echo("");

        for (final FileChange c : cmt.getChanges()) {
            _log.echo(c);
        }

        _log.echo("--------------------------------------------------------------------------------");

        cmt.setMark(":" + mark);
        cmt.setBranch(branch);
        cmt.setCommitter(committer);

        if (mark != 1) {
            cmt.setFromCommittiSh(':' + String.valueOf(mark - 1));
        }

        cmt.write(s);
    }

    private final class ViewListener implements FolderUpdateListener,
            ItemUpdateListener {
        private final Map<CommitId, Commit> _commits = new TreeMap<>();
        private final CheckoutManager _cmgr;
        private final ExecutorService _threadPool;

        ViewListener(final boolean checkout) {
            if (checkout) {
                final Repo repo = getRepo();
                _threadPool = repo.getThreadPool();

                if (_threadPool != null) {
                    final View view = repo.getView();
                    final CheckoutOptions co = new CheckoutOptions(view);
                    co.setEOLConversionEnabled(false);
                    co.setOptimizeForSlowConnections(true);
                    co.setUpdateStatus(false);
                    co.setForceCheckout(true);
                    _cmgr = repo.getView().createCheckoutManager(co);
                } else {
                    _cmgr = null;
                }
            } else {
                _cmgr = null;
                _threadPool = null;
            }
        }

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
            final FileData data = new FileData(f);
            cmt.addChange(new Filemodify(path, data, true));
            logChange(time, "A", path);

            if (_cmgr != null) {
                data.checkout(_cmgr, _threadPool);
            }
        }

        @Override
        public void itemChanged(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();
            final String path = getPath(f);
            final long time = f.getModifiedTime().getLongValue();
            final int user = f.getModifiedBy();
            final Commit cmt = getCommit(user, time);
            final FileData data = new FileData(f);
            cmt.addChange(new Filemodify(path, data, false));
            logChange(time, "M", path);

            if (_cmgr != null) {
                data.checkout(_cmgr, _threadPool);
            }
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
