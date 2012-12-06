package com.aap.gitst.fastimport;

import static com.aap.gitst.RepoProperties.META_PROP_ITEM_FILTER;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_MAXCONNECTIONS;
import static com.aap.gitst.RepoProperties.PROP_MAXCONNECTIONS;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.aap.gitst.Exec;
import com.aap.gitst.ExecutionException;
import com.aap.gitst.Git;
import com.aap.gitst.ItemFilter;
import com.aap.gitst.Logger;
import com.aap.gitst.RemoteFile;
import com.aap.gitst.Repo;
import com.aap.gitst.RepoProperties;
import com.aap.gitst.Utils;
import com.aap.gitst.Logger.ProgressBar;
import com.starbase.starteam.CheckoutEvent;
import com.starbase.starteam.CheckoutListener;
import com.starbase.starteam.CheckoutProgress;
import com.starbase.starteam.File;
import com.starbase.starteam.Folder;
import com.starbase.starteam.FolderUpdateEvent;
import com.starbase.starteam.FolderUpdateListener;
import com.starbase.starteam.Item;
import com.starbase.starteam.ItemList;
import com.starbase.starteam.ItemUpdateEvent;
import com.starbase.starteam.ItemUpdateListener;
import com.starbase.starteam.RecycleBin;
import com.starbase.starteam.Server;
import com.starbase.starteam.Type;
import com.starbase.starteam.View;
import com.starbase.starteam.ViewConfiguration;
import com.starbase.starteam.ViewConfigurationDiffer;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class FastImport {
    private static final String[] FILE_PROPS = { "Name", "ModifiedTime",
            "ModifiedUserID", "Comment", "DotNotation", "ItemDeletedTime",
            "ItemDeletedUserID" };
    private static final String[] FOLDER_PROPS = { "Name", "ModifiedTime",
            "ModifiedUserID", "Comment", "WorkingFolder", "DotNotation",
            "ItemDeletedTime", "ItemDeletedUserID" };
    private final Repo _repo;
    private final Logger _log;

    public FastImport(final Repo repo) {
        _repo = repo;
        _log = repo.getLogger();
    }

    public Repo getRepo() {
        return _repo;
    }

    public Map<CommitId, Commit> loadChanges(final OLEDate endDate)
            throws InterruptedException, IOException {
        final Repo repo = getRepo();
        final RepoProperties props = repo.getRepoProperties();
        final ConcurrentSkipListMap<CommitId, Commit> commits = new ConcurrentSkipListMap<>();
        final ItemFilter filter = new ItemFilter(
                props.getMetaProperty(META_PROP_ITEM_FILTER));
        final List<Item[]> history = loadHistory(filter, commits);
        processHistory(filter, commits, endDate, history);

        return commits;
    }

    public Map<CommitId, Commit> loadChanges(final OLEDate startDate,
            final OLEDate endDate) throws InterruptedException {
        final Repo repo = getRepo();
        final RepoProperties props = repo.getRepoProperties();
        final View v = repo.connect();
        final Server s = v.getServer();
        final ItemFilter filter = new ItemFilter(
                props.getMetaProperty(META_PROP_ITEM_FILTER));
        final IntermediateListener iListener = new IntermediateListener();
        final ViewListener vListener = new ViewListener(filter);
        final ViewConfigurationDiffer diff = new ViewConfigurationDiffer(v);

        if (_log.isInfoEnabled()) {
            _log.info("Requesting changes since " + startDate);
        }

        diff.addRequiredPropertyNames(s.typeForName("File"), FILE_PROPS);
        diff.addRequiredPropertyNames(s.typeForName("Folder"), FOLDER_PROPS);
        diff.addFolderUpdateListener(iListener);
        diff.addItemUpdateListener(iListener,
                repo.getServer().typeForName("File"));
        diff.compare(ViewConfiguration.createFromTime(startDate),
                ViewConfiguration.createFromTime(endDate));

        for (final IntermediateListener.EventWrapper w : iListener.getEvents()) {
            switch (w._type) {
            case ITEM_ADDED:
                vListener.itemAdded((ItemUpdateEvent) w._event);
                break;
            case ITEM_MOVED:
                vListener.itemMoved((ItemUpdateEvent) w._event);
                break;
            case ITEM_CHANGED:
                vListener.itemChanged((ItemUpdateEvent) w._event);
                break;
            case ITEM_REMOVED:
                vListener.itemRemoved((ItemUpdateEvent) w._event);
                break;
            case FOLDER_ADDED:
                vListener.folderAdded((FolderUpdateEvent) w._event);
                break;
            case FOLDER_MOVED:
                vListener.folderMoved((FolderUpdateEvent) w._event);
                break;
            case FOLDER_REMOVED:
                vListener.folderRemoved((FolderUpdateEvent) w._event);
                break;
            default:
                throw new UnsupportedOperationException(w._type.name());
            }
        }

        return vListener.getCommits();
    }

    public void submit(final Collection<Commit> commits, final boolean verbose)
            throws IOException, InterruptedException, ExecutionException {
        final Repo repo = getRepo();
        final Git git = repo.getGit();
        final Exec exec = git.fastImport(repo.getBranchName()).exec();
        final Process proc = exec.getProcess();
        @SuppressWarnings("resource")
        final OutputStream out = proc.getOutputStream();

        try {
            submit(commits, out, verbose);
            out.close();

            if (exec.waitFor() != 0) {
                throw new ExecutionException(
                        "git fast-import failed with exit code: "
                                + proc.exitValue(), proc.exitValue());
            }
        } finally {
            proc.destroy();
        }
    }

    public void submit(final Collection<Commit> commits,
            final OutputStream out, final boolean verbose) throws IOException,
            InterruptedException, ExecutionException {
        final Repo repo = getRepo();
        final long time = System.currentTimeMillis();
        final String branch = repo.getBranchName();
        final ProgressBar b = _log.createProgressBar("Importing to git",
                commits.size());
        final ExecutorService threadPool = Executors.newSingleThreadExecutor();
        final PrintStream s = new PrintStream(out);
        final String from = repo.getGit().showRef(branch);
        int mark = 0;
        checkout(commits, threadPool);

        for (final Commit cmt : commits) {
            final CommitId id = cmt.getId();
            final String committer = repo.toCommitter(id.getUserId());

            if (mark != 0) {
                cmt.setFrom(':' + String.valueOf(mark));
            } else if (from != null) {
                cmt.setFrom(from);
            }

            cmt.setMark(":" + (++mark));
            cmt.setBranch(branch);
            cmt.setCommitter(committer);

            if (verbose && _log.isInfoEnabled()) {
                _log.info(cmt);
                _log.info("--------------------------------------------------------------------------------");
            }

            cmt.write(repo, s);
            b.done(1);
        }

        out.flush();
        if (verbose) {
            _log.info("");
        }
        threadPool.shutdown();
        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        b.complete();

        if (_log.isDebugEnabled()) {
            _log.debug("Imported in " + (System.currentTimeMillis() - time)
                    + " ms.");
        }
    }

    private void checkout(final Collection<Commit> commits,
            final ExecutorService threadPool) {
        final Thread main = Thread.currentThread();

        threadPool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final Repo repo = getRepo();
                    final CoListener l = new CoListener(repo, commits);
                    final ItemList items = l.getItemList();

                    if (items.size() > 0) {
                        Utils.checkout(repo, items, l);
                        l._pbar.complete();
                    }
                } catch (final Throwable ex) {
                    getRepo().getLogger().error("Checkout failed", ex);
                    main.interrupt();
                }
            }
        });
    }

    private List<Item[]> loadHistory(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits)
            throws InterruptedException {
        final Repo repo = getRepo();
        final RepoProperties props = repo.getRepoProperties();
        final int maxc = Integer.parseInt(props.getProperty(
                PROP_MAXCONNECTIONS, PROP_DEFAULT_MAXCONNECTIONS));
        final Folder rootFolder = repo.getRootFolder();
        Folder recycleRootFolder = null;
        final boolean skipDeleted = "true".equalsIgnoreCase(System
                .getenv("GIST_SKIP_DELETED"));
        final Type type = repo.getServer().typeForName("File");
        final int count;
        int recycleCount = 0;
        long time = System.currentTimeMillis();

        _log.info("Loading files tree");
        rootFolder.populateNow("File", FILE_PROPS, -1);
        rootFolder.populateNow("Folder", FOLDER_PROPS, -1);
        count = (int) rootFolder.countItems(type, -1);

        if (_log.isDebugEnabled()) {
            _log.debug("Files tree loaded in "
                    + (System.currentTimeMillis() - time) + " ms.");
        }

        if (!skipDeleted) {
            final RecycleBin recycle = repo.getView().getRecycleBin();
            recycleRootFolder = repo.getRootFolder(recycle);
            time = System.currentTimeMillis();
            _log.info("Loading deleted files tree");
            recycleRootFolder.populateNow("File", FILE_PROPS, -1);
            recycleRootFolder.populateNow("Folder", FOLDER_PROPS, -1);
            recycleCount = (int) recycleRootFolder.countItems(type, -1);

            if (_log.isDebugEnabled()) {
                _log.debug("Deleted files tree loaded in "
                        + (System.currentTimeMillis() - time) + " ms.");
            }
        }

        final List<Item[]> history = new Vector<>(count + recycleCount);
        final ItemList list = new ItemList();
        ExecutorService threadPool = createThreadPool(Math.min(maxc,
                (count * 3) / 1000));
        ProgressBar pb = _log.createProgressBar("Loading files history", count);

        if (!_log.isProgressBarSupported()) {
            _log.info("Loading files history");
        }

        time = System.currentTimeMillis();
        loadHistory(filter, commits, rootFolder, threadPool, pb, history, list,
                false);
        threadPool.shutdown();
        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        pb.complete();

        if (_log.isDebugEnabled()) {
            _log.debug("Files history loaded in "
                    + (System.currentTimeMillis() - time) + " ms.");
        }

        if (!skipDeleted) {
            if (!_log.isProgressBarSupported()) {
                _log.info("Loading deleted files history");
            }

            threadPool = createThreadPool(Math.min(maxc,
                    (recycleCount * 6) / 1000));
            pb = _log.createProgressBar("Loading deleted files history",
                    recycleCount);
            time = System.currentTimeMillis();
            loadHistory(filter, commits, recycleRootFolder, threadPool, pb,
                    history, list, true);
            threadPool.shutdown();
            threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            pb.complete();

            if (_log.isDebugEnabled()) {
                _log.debug("Deleted files history loaded in "
                        + (System.currentTimeMillis() - time) + " ms.");
            }
        }

        if (list.size() > 0) {
            time = System.currentTimeMillis();
            _log.info("Populating properties");
            list.populateNow(FILE_PROPS);

            if (_log.isDebugEnabled()) {
                _log.debug("History populated in "
                        + (System.currentTimeMillis() - time) + " ms.");
            }
        }

        return history;
    }

    private void loadHistory(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final Folder folder,
            final ExecutorService threadPool, final ProgressBar pb,
            final List<Item[]> history, final ItemList list,
            final boolean isRecycle) {
        final Repo repo = getRepo();
        final Thread main = Thread.currentThread();
        final Item[] files = folder.getItems("File");
        final Folder[] folders = folder.getSubFolders();

        if (!isRecycle && (files.length == 0) && (folders.length == 0)) {
            createEmptyDir(filter, commits, folder, false);
        }

        for (final Item f : files) {
            if (main.isInterrupted()) {
                return;
            }
            if (_log.isDebugEnabled()) {
                _log.debug(repo.getPath(f) + ':' + f.getDotNotation());
            }

            threadPool.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        history.add(Utils.getHistory(repo, f, list));
                    } catch (final Throwable ex) {
                        _log.error(ex.getMessage(), ex);
                        threadPool.shutdown();
                        main.interrupt();
                    } finally {
                        pb.done(1);
                    }
                }
            });
        }

        for (final Folder f : folders) {
            loadHistory(filter, commits, f, threadPool, pb, history, list,
                    isRecycle);
        }

        if (folder.isDeleted()) {
            createFiledelete(filter, commits, folder.getDeletedTime(), folder,
                    false);
        }
    }

    private void processHistory(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits,
            final OLEDate endDate, final List<Item[]> history)
            throws InterruptedException {
        final Repo repo = getRepo();
        final Thread main = Thread.currentThread();
        final boolean verbose = _log.isDebugEnabled();
        final long time = System.currentTimeMillis();
        final double end = endDate.getDoubleValue();
        final ProgressBar pb = _log.createProgressBar("Processing history",
                history.size());
        final ExecutorService threadPool = createThreadPool(Runtime
                .getRuntime().availableProcessors() - 1);

        for (final Item[] itemHistory : history) {
            threadPool.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        doRun();
                    } catch (final Throwable ex) {
                        _log.error(ex.getMessage(), ex);
                        threadPool.shutdown();
                        main.interrupt();
                    } finally {
                        pb.done(1);
                    }
                }

                public void doRun() {
                    File prev = null;
                    final boolean deleted = itemHistory[0].isDeleted();
                    final int top = deleted ? 1 : 0;

                    for (int i = itemHistory.length - 1; i >= top; i--) {
                        final File h = (File) itemHistory[i];
                        final OLEDate date = h.getModifiedTime();

                        if (date.getDoubleValue() >= end) {
                            break;
                        } else if (prev == null) {
                            final FileData data = new FileData(h, repo
                                    .getPath(h));
                            createFilemodify(filter, commits, date, data, true,
                                    verbose);
                        } else {
                            final String path = repo.getPath(h);
                            final String prevPath = repo.getPath(prev);

                            if (path.equals(prevPath)) {
                                final FileData data = new FileData(h, path);
                                createFilemodify(filter, commits, date, data,
                                        false, verbose);
                            } else {
                                final FileRename rename = new FileRename(prev,
                                        h, prevPath, path);
                                createFilerename(filter, commits, date, rename,
                                        verbose);
                            }
                        }

                        prev = h;
                    }

                    if (deleted && isNotRestored(itemHistory[0])) {
                        final int user = itemHistory[0].getDeletedUserID();
                        final OLEDate date = itemHistory[0].getDeletedTime();

                        if (!filter.apply(user, date.getDoubleValue())) {
                            final long time = date.getLongValue();
                            final FileDelete c = new FileDelete(itemHistory[1],
                                    getRepo().getPath(itemHistory[1]));
                            final Commit cmt = getCommit(commits, user, time);
                            cmt.addChange(c);

                            if (verbose) {
                                logChange(time, c);
                            }
                        }
                    }
                }
            });
        }

        threadPool.shutdown();
        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        if (_log.isDebugEnabled()) {
            _log.debug("History processed in "
                    + (System.currentTimeMillis() - time) + " ms.");
        }
    }

    private boolean isNotRestored(final Item deletedItem) {
        final Repo repo = getRepo();

        if (deletedItem instanceof File) {
            return repo.getFile(repo.getPath(deletedItem)) == null;
        } else {
            return repo.getFolder(repo.getPath(deletedItem)) == null;
        }
    }

    private static ExecutorService createThreadPool(final int t) {
        return new ThreadPoolExecutor(0, (t <= 0) ? 1 : t, 60L,
                TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private void createFilemodify(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final OLEDate date,
            final FileData data, final boolean isNewFile, final boolean verbose) {
        final int user = data.getFile().getModifiedBy();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final FileModify c = new FileModify(data, isNewFile);
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);

            if (verbose) {
                logChange(time, c);
            }
        }
    }

    private void createFilerename(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final OLEDate date,
            final FileRename c, final boolean verbose) {
        final int user = c.getDestItem().getModifiedBy();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);

            if (verbose) {
                logChange(time, c);
            }
        }
    }

    private void createFiledelete(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final OLEDate date,
            final Item item, final boolean verbose) {
        final int user = item.getDeletedUserID();

        if (!filter.apply(user, date.getDoubleValue()) && isNotRestored(item)) {
            final long time = date.getLongValue();
            final FileDelete c = new FileDelete(item, getRepo().getPath(item));
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);

            if (verbose) {
                logChange(time, c);
            }
        }
    }

    private void createEmptyDir(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final Folder folder,
            final boolean verbose) {
        final int user = folder.getModifiedBy();
        final OLEDate date = folder.getModifiedTime();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final EmptyDir c = new EmptyDir(folder, getRepo().getPath(folder));
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);

            if (verbose) {
                logChange(time, c);
            }
        }
    }

    private static Commit getCommit(
            final ConcurrentMap<CommitId, Commit> commits, final int userId,
            final long time) {
        final CommitId id = new CommitId(userId, time);
        final Commit cmt = new Commit(id);
        final Commit old = commits.putIfAbsent(id, cmt);
        return (old == null) ? cmt : old;
    }

    private void logChange(final long time, final Object msg) {
        if (_log.isInfoEnabled()) {
            _log.info(new SimpleDateFormat(Repo.DATE_FORMAT).format(time)
                    + "| " + msg);
        }
    }

    private static final class IntermediateListener implements
            FolderUpdateListener, ItemUpdateListener {
        private final List<Folder> _folders = new ArrayList<>();
        private final List<EventWrapper> _events = new ArrayList<>();

        public List<EventWrapper> getEvents() {
            if (!_folders.isEmpty()) {
                final ItemList list = new ItemList();
                for (final Folder f : _folders) {
                    list.addItem(f);
                }
                list.populateNow(new String[] { "Name" });
            }
            return _events;
        }

        @Override
        public void itemAdded(final ItemUpdateEvent e) {
            final Item dest = e.getNewItem();
            addFolders(dest);
            _events.add(new EventWrapper(Type.ITEM_ADDED, e));
        }

        @Override
        public void itemMoved(final ItemUpdateEvent e) {
            final Item src = e.getOldItem();
            final Item dest = e.getNewItem();
            addFolders(src);
            addFolders(dest);
            _events.add(new EventWrapper(Type.ITEM_MOVED, e));
        }

        @Override
        public void itemChanged(final ItemUpdateEvent e) {
            final Item src = e.getOldItem();
            final Item dest = e.getNewItem();
            addFolders(src);
            addFolders(dest);
            _events.add(new EventWrapper(Type.ITEM_CHANGED, e));
        }

        @Override
        public void itemRemoved(final ItemUpdateEvent e) {
            final Item src = e.getOldItem();
            addFolders(src);
            _events.add(new EventWrapper(Type.ITEM_REMOVED, e));
        }

        @Override
        public void folderAdded(final FolderUpdateEvent e) {
            final Folder dest = e.getNewFolder();
            addFolders(dest);
            _events.add(new EventWrapper(Type.FOLDER_ADDED, e));
        }

        @Override
        public void folderMoved(final FolderUpdateEvent e) {
            final Item src = e.getOldFolder();
            final Item dest = e.getNewFolder();
            addFolders(src);
            addFolders(dest);
            _events.add(new EventWrapper(Type.FOLDER_MOVED, e));
        }

        @Override
        public void folderRemoved(final FolderUpdateEvent e) {
            final Item src = e.getOldFolder();
            addFolders(src);
            _events.add(new EventWrapper(Type.FOLDER_REMOVED, e));
        }

        @Override
        public void folderChanged(final FolderUpdateEvent e) {
            // Not supported
        }

        private void addFolders(final Item i) {
            for (Folder f = i.getParentFolder(); f != null; f = f
                    .getParentFolder()) {
                _folders.add(f);
            }
        }

        static enum Type {
            ITEM_ADDED,
            ITEM_MOVED,
            ITEM_CHANGED,
            ITEM_REMOVED,
            FOLDER_ADDED,
            FOLDER_MOVED,
            FOLDER_CHANGED,
            FOLDER_REMOVED;
        }

        static final class EventWrapper {
            final Type _type;
            final EventObject _event;

            public EventWrapper(final Type type, final EventObject event) {
                _type = type;
                _event = event;
            }
        }
    }

    private final class ViewListener implements FolderUpdateListener,
            ItemUpdateListener {
        private final ConcurrentMap<CommitId, Commit> _commits = new ConcurrentSkipListMap<>();
        private final ItemFilter _filter;
        private final Folder _rootFolder = _repo.getRootFolder();
        private final boolean _verbose = _log.isDebugEnabled();

        public ViewListener(final ItemFilter filter) {
            _filter = filter;
        }

        public Map<CommitId, Commit> getCommits() throws InterruptedException {
            return _commits;
        }

        @Override
        public void itemAdded(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();

            if (isUnderRoot(f)) {
                final FileData data = new FileData(f, getRepo().getPath(f));
                final OLEDate date = f.getModifiedTime();
                createFilemodify(_filter, _commits, date, data, true, _verbose);
            }
        }

        @Override
        public void itemChanged(final ItemUpdateEvent e) {
            final Repo repo = getRepo();
            final File src = (File) e.getOldItem();
            final File dest = (File) e.getNewItem();
            final boolean srcUnderRoot = isUnderRoot(src);
            final boolean destUnderRoot = isUnderRoot(dest);

            if (srcUnderRoot && destUnderRoot) {
                final String srcPath = repo.getPath(src);
                final String destPath = repo.getPath(dest);
                final OLEDate date = dest.getModifiedTime();

                if (!srcPath.equals(destPath)) {
                    final FileRename c = new FileRename(src, dest, srcPath,
                            destPath);
                    createFilerename(_filter, _commits, date, c, _verbose);
                } else {
                    final FileData data = new FileData(dest, getRepo().getPath(
                            dest));
                    createFilemodify(_filter, _commits, date, data, false,
                            _verbose);
                }
            } else if (srcUnderRoot) {
                final OLEDate date = dest.getModifiedTime();
                createFiledelete(_filter, _commits, date, src, _verbose);
            } else if (destUnderRoot) {
                itemAdded(e);
            }
        }

        @Override
        public void itemMoved(final ItemUpdateEvent e) {
            final Repo repo = getRepo();
            final Item src = e.getOldItem();
            final Item dest = e.getNewItem();
            final boolean srcUnderRoot = isUnderRoot(src);
            final boolean destUnderRoot = isUnderRoot(dest);

            if (srcUnderRoot && destUnderRoot) {
                final String srcPath = repo.getPath(src);
                final String destPath = repo.getPath(dest);
                final OLEDate date = dest.getModifiedTime();
                final FileRename c = new FileRename(src, dest, srcPath,
                        destPath);
                createFilerename(_filter, _commits, date, c, _verbose);
            } else if (srcUnderRoot) {
                final OLEDate date = dest.getModifiedTime();
                createFiledelete(_filter, _commits, date, src, _verbose);
            } else if (destUnderRoot) {
                itemAdded(e);
            }
        }

        @Override
        public void itemRemoved(final ItemUpdateEvent e) {
            final Item item = e.getOldItem();

            if (isUnderRoot(item)) {
                final OLEDate date = item.getDeletedTime();
                createFiledelete(_filter, _commits, date, item, _verbose);
            }
        }

        @Override
        public void folderMoved(final FolderUpdateEvent e) {
            final Repo repo = getRepo();
            final Item src = e.getOldFolder();
            final Item dest = e.getNewFolder();
            final boolean srcUnderRoot = isUnderRoot(src);
            final boolean destUnderRoot = isUnderRoot(dest);

            if (srcUnderRoot && destUnderRoot) {
                final String srcPath = repo.getPath(src);
                final String destPath = repo.getPath(dest);
                final OLEDate date = dest.getModifiedTime();
                final FileRename c = new FileRename(src, dest, srcPath,
                        destPath);
                createFilerename(_filter, _commits, date, c, _verbose);
            } else if (srcUnderRoot) {
                final OLEDate date = dest.getModifiedTime();
                createFiledelete(_filter, _commits, date, src, _verbose);
            } else if (destUnderRoot) {
                folderAdded(e);
            }
        }

        @Override
        public void folderRemoved(final FolderUpdateEvent e) {
            final Item item = e.getOldFolder();

            if (isUnderRoot(item)) {
                final OLEDate date = item.getDeletedTime();
                createFiledelete(_filter, _commits, date, item, _verbose);
            }
        }

        @Override
        public void folderAdded(final FolderUpdateEvent e) {
            final Folder folder = e.getNewFolder();

            if (isUnderRoot(folder)) {
                final ItemList files = folder.getList("File");

                if (files.size() == 0) {
                    final ItemList folders = folder.getList("Folder");

                    if (folders.size() == 0) {
                        createEmptyDir(_filter, _commits, folder, _verbose);
                    }
                }
            }
        }

        private boolean isUnderRoot(final Item i) {
            for (Folder f = i.getParentFolder(); f != null; f = f
                    .getParentFolder()) {
                if (f.equals(_rootFolder)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void folderChanged(final FolderUpdateEvent e) {
            // Not supported
        }
    }

    private static final class CoListener implements CheckoutListener {
        private final Repo _repo;
        private final Map<RemoteFile, List<FileData>> _files;
        private final ItemList _itemList;
        private final AtomicLong _totalBytes = new AtomicLong();
        final ProgressBar _pbar;

        public CoListener(final Repo repo, final Collection<Commit> commits) {
            _repo = repo;
            _files = new HashMap<>();
            _itemList = new ItemList();
            final Logger log = repo.getLogger();

            for (final Commit cmt : commits) {
                for (final FileChange c : cmt.getChanges()) {
                    final FileData d;

                    if (c instanceof FileModify) {
                        d = ((FileModify) c).getFileData();
                    } else if (c instanceof FileRename) {
                        final FileModify mod = ((FileRename) c).getFileModify();

                        if (mod != null) {
                            d = mod.getFileData();
                        } else {
                            continue;
                        }
                    } else {
                        continue;
                    }

                    final File f = d.getFile();
                    final RemoteFile id = new RemoteFile(f);
                    final List<FileData> old = _files.put(id,
                            Collections.singletonList(d));

                    if (old != null) {
                        final List<FileData> newList = new ArrayList<>(
                                old.size() + 1);
                        newList.addAll(old);
                        newList.add(d);
                        _files.put(id, newList);

                        if (log.isWarnEnabled()) {
                            log.warn("Warning! File duplicated in several commits: "
                                    + d.getPath());
                        }
                    }

                    _itemList.addItem(d.getFile());
                }
            }

            _pbar = repo.getLogger().createProgressBar(this, _itemList.size());
        }

        public ItemList getItemList() {
            return _itemList;
        }

        @Override
        public void onStartFile(final CheckoutEvent e) {
            final File f = e.getCurrentFile();
            final RemoteFile id = new RemoteFile(f);
            final String path = _files.get(id).get(0).getPath();

            try {
                // Workaround to disable EOL conversion
                f.put("EOL", null);
                e.setCurrentWorkingFile(_repo.createTempFile(path));
            } catch (final IOException ex) {
                throw new RuntimeException(
                        "Failed to create temporary file for " + path, ex);
            }
        }

        @Override
        public void onNotifyProgress(final CheckoutEvent e) {
            if (!Utils.isApi12()) {
                handleProgress(e.getProgress());
            }

            if (e.isFinished()) {
                final File f = e.getCurrentFile();
                final RemoteFile id = new RemoteFile(f);
                java.io.File wf = e.getCurrentWorkingFile();
                final List<FileData> data = _files.get(id);
                _pbar.done(1);

                if (!e.isSuccessful()) {
                    _repo.getLogger().error(
                            "Failed to checkout file: " + _repo.getPath(f));
                }

                if (data.size() > 1) {
                    for (final Iterator<FileData> it = data.iterator(); it
                            .hasNext();) {
                        final FileData d = it.next();

                        if (it.hasNext()) {
                            try {
                                final java.io.File newFile = _repo
                                        .createTempFile(d.getPath());
                                Repo.copyFile(wf, newFile);
                                d.setCheckout(wf);
                                wf = newFile;
                            } catch (final IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        } else {
                            d.setCheckout(wf);
                        }
                    }
                } else {
                    data.get(0).setCheckout(wf);
                }
            }
        }

        @Override
        public String toString() {
            return Utils.isApi12() ? "Checking out" : "Checking out ("
                    + _totalBytes + " bytes)";
        }

        private void handleProgress(final CheckoutProgress progress) {
            final long p = progress.getTotalBytesCheckedOut();

            for (;;) {
                final long total = _totalBytes.get();

                if (total >= p) {
                    return;
                } else if (_totalBytes.compareAndSet(total, p)) {
                    _pbar.update();
                    return;
                }
            }
        }
    }
}
