package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import com.starbase.starteam.ItemList;
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
    private final Repo _repo;
    private final Logger _log;

    public FastImport(final Repo repo) {
        _repo = repo;
        _log = repo.getLogger();
    }

    public Repo getRepo() {
        return _repo;
    }

    public Map<CommitId, Commit> loadChanges(final View v,
            final OLEDate endDate, final boolean checkout) {
        final long end = endDate.getLongValue();
        final ConcurrentSkipListMap<CommitId, Commit> commits = new ConcurrentSkipListMap<>();
        _log.echo("Loading history");
        _log.echo();

        if (checkout) {
            final CheckoutManager cmgr = createCheckoutManager(v);
            final ExecutorService threadPool = createCheckoutThreadPool();
            load(commits, end, v.getRootFolder(), cmgr, threadPool);
            threadPool.shutdown();
        } else {
            load(commits, end, v.getRootFolder(), null, null);
        }

        return commits;
    }

    public Map<CommitId, Commit> loadChanges(final View v,
            final OLEDate startDate, final OLEDate endDate,
            final boolean checkout) {
        final Repo repo = getRepo();
        final ViewListener listener = new ViewListener(v, checkout);
        final ViewConfigurationDiffer diff = new ViewConfigurationDiffer(v);

        _log.echo("Requesting changes since " + startDate);
        diff.addFolderUpdateListener(listener);
        diff.addItemUpdateListener(listener,
                repo.getServer().typeForName("File"));
        diff.compare(ViewConfiguration.createFromTime(startDate),
                ViewConfiguration.createFromTime(endDate));
        return listener.getCommits();
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
            final PrintStream s) throws IOException, InterruptedException {
        final Repo repo = getRepo();
        final CommitId id = cmt.getId();
        final String committer = repo.toCommitter(id.getUserId());

        _log.echo(repo.getDateTimeFormat().format(id.getTime()) + " "
                + committer + ':');
        _log.echo(cmt.getComment());
        _log.echo();

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

        cmt.write(getRepo(), s);
    }

    private void load(final ConcurrentMap<CommitId, Commit> commits,
            final long endDate, final Folder folder,
            final CheckoutManager cmgr, final ExecutorService threadPool) {
        for (final Enumeration<?> en = getFiles(cmgr, folder).elements(); en
                .hasMoreElements();) {
            final File f = (File) en.nextElement();
            final Item[] history = f.getHistory();
            long time;
            File prev = null;

            for (int i = history.length - 1; i >= 0; i--) {
                final File h = (File) history[i];
                time = h.getModifiedTime().getLongValue();

                if (time < endDate) {
                    load(commits, time, h, prev, cmgr, threadPool);
                    prev = h;
                } else {
                    break;
                }
            }
        }
        for (final Enumeration<?> en = getFolders(cmgr, folder).elements(); en
                .hasMoreElements();) {
            load(commits, endDate, (Folder) en.nextElement(), cmgr, threadPool);
        }
    }

    private static ItemList getFiles(final CheckoutManager cmgr,
            final Folder folder) {
        if (cmgr != null) {
            synchronized (cmgr) {
                return folder.getList("File");
            }
        } else {
            return folder.getList("File");
        }
    }

    private static ItemList getFolders(final CheckoutManager cmgr,
            final Folder folder) {
        if (cmgr != null) {
            synchronized (cmgr) {
                return folder.getList("Folder");
            }
        } else {
            return folder.getList("Folder");
        }
    }

    private void load(final ConcurrentMap<CommitId, Commit> commits,
            final long time, final File file, final File prev,
            final CheckoutManager cmgr, final ExecutorService threadPool) {
        if (prev == null) {
            final FileData data = new FileData(file);
            createFilemodify(commits, time, data, true, cmgr, threadPool);
        } else if (file.isDeleted()) {
            createFiledelete(commits, time, file);
        } else {
            final String path = Repo.getPath(file);
            final String prevPath = Repo.getPath(prev);

            if (path.equals(prevPath)) {
                final FileData data = new FileData(file, path);
                createFilemodify(commits, time, data, false, cmgr, threadPool);
            } else {
                final FileRename rename = new FileRename(prev, file, prevPath,
                        path);
                createFilerename(commits, time, rename);
            }
        }
    }

    private void createFilemodify(
            final ConcurrentMap<CommitId, Commit> commits, final long time,
            final FileData data, final boolean isNewFile,
            final CheckoutManager cmgr, final ExecutorService threadPool) {
        if (cmgr != null) {
            try {
                data.checkout(getRepo(), cmgr, threadPool);
            } catch (final Throwable ex) {
                _log.error("Error occurred", ex);
            }
        }

        final int user = data.getFile().getModifiedBy();
        final FileModify c = new FileModify(data, isNewFile);
        final Commit cmt = getCommit(commits, user, time);
        cmt.addChange(c);
        logChange(time, isNewFile ? "A" : "M", c.getPath());
    }

    private void createFilerename(
            final ConcurrentMap<CommitId, Commit> commits, final long time,
            final FileRename c) {
        final int user = c.getDestItem().getModifiedBy();
        final Commit cmt = getCommit(commits, user, time);
        cmt.addChange(c);
        logChange(time, "R", c.getSourcePath() + " -> " + c.getDestPath());
    }

    private void createFiledelete(
            final ConcurrentMap<CommitId, Commit> commits, final long time,
            final Item item) {
        final int user = item.getDeletedUserID();
        final FileDelete c = new FileDelete(item);
        final Commit cmt = getCommit(commits, user, time);
        cmt.addChange(c);
        logChange(time, "D", c.getPath());
    }

    private static Commit getCommit(
            final ConcurrentMap<CommitId, Commit> commits, final int userId,
            final long time) {
        final CommitId id = new CommitId(userId, time);
        final Commit cmt = new Commit(id);
        final Commit old = commits.putIfAbsent(id, cmt);
        return (old == null) ? cmt : old;
    }

    private void logChange(final long time, final String type, final String path) {
        _log.echo(getRepo().getDateTimeFormat().format(time) + "| " + type
                + ' ' + path);
    }

    private static CheckoutManager createCheckoutManager(final View view) {
        final CheckoutOptions co = new CheckoutOptions(view);
        co.setEOLConversionEnabled(false);
        co.setOptimizeForSlowConnections(true);
        co.setUpdateStatus(false);
        co.setForceCheckout(true);
        return view.createCheckoutManager(co);
    }

    private static ExecutorService createCheckoutThreadPool() {
        return Executors.newSingleThreadExecutor();
    }

    private final class ViewListener implements FolderUpdateListener,
            ItemUpdateListener {
        private final ConcurrentMap<CommitId, Commit> _commits = new ConcurrentSkipListMap<>();
        private final CheckoutManager _cmgr;
        private final ExecutorService _threadPool;

        ViewListener(final View view, final boolean checkout) {
            if (checkout) {
                _cmgr = createCheckoutManager(view);
                _threadPool = createCheckoutThreadPool();

            } else {
                _cmgr = null;
                _threadPool = null;
            }
        }

        public synchronized Map<CommitId, Commit> getCommits() {
            if (_threadPool != null) {
                _threadPool.shutdown();
            }
            return _commits;
        }

        @Override
        public void itemAdded(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();
            final FileData data = new FileData(f);
            final long time = f.getModifiedTime().getLongValue();
            createFilemodify(_commits, time, data, true, _cmgr, _threadPool);
        }

        @Override
        public void itemChanged(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();
            final FileData data = new FileData(f);
            final long time = f.getModifiedTime().getLongValue();
            createFilemodify(_commits, time, data, false, _cmgr, _threadPool);
        }

        @Override
        public void itemMoved(final ItemUpdateEvent e) {
            final Item src = e.getOldItem();
            final Item dest = e.getNewItem();
            final long time = dest.getModifiedTime().getLongValue();
            final FileRename c = new FileRename(src, dest);
            createFilerename(_commits, time, c);
        }

        @Override
        public void itemRemoved(final ItemUpdateEvent e) {
            final Item item = e.getOldItem();
            final long time = item.getModifiedTime().getLongValue();
            createFiledelete(_commits, time, item);
        }

        @Override
        public void folderMoved(final FolderUpdateEvent e) {
            final Item src = e.getOldFolder();
            final Item dest = e.getNewFolder();
            final long time = dest.getModifiedTime().getLongValue();
            final FileRename c = new FileRename(src, dest);
            createFilerename(_commits, time, c);
        }

        @Override
        public void folderRemoved(final FolderUpdateEvent e) {
            final Item item = e.getOldFolder();
            final long time = item.getModifiedTime().getLongValue();
            createFiledelete(_commits, time, item);
        }

        @Override
        public void folderAdded(final FolderUpdateEvent e) {
        }

        @Override
        public void folderChanged(final FolderUpdateEvent e) {
        }
    }
}
