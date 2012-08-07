package com.googlecode.gitst.fastimport;

import static com.googlecode.gitst.RepoProperties.META_PROP_ITEM_FILTER;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.googlecode.gitst.Exec;
import com.googlecode.gitst.ExecutionException;
import com.googlecode.gitst.Git;
import com.googlecode.gitst.ItemFilter;
import com.googlecode.gitst.Logger;
import com.googlecode.gitst.Logger.ProgressBar;
import com.googlecode.gitst.RemoteFile;
import com.googlecode.gitst.Repo;
import com.googlecode.gitst.RepoProperties;
import com.starbase.starteam.CheckoutEvent;
import com.starbase.starteam.CheckoutListener;
import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.File;
import com.starbase.starteam.Folder;
import com.starbase.starteam.FolderUpdateEvent;
import com.starbase.starteam.FolderUpdateListener;
import com.starbase.starteam.Item;
import com.starbase.starteam.ItemList;
import com.starbase.starteam.ItemUpdateEvent;
import com.starbase.starteam.ItemUpdateListener;
import com.starbase.starteam.Server;
import com.starbase.starteam.View;
import com.starbase.starteam.ViewConfiguration;
import com.starbase.starteam.ViewConfigurationDiffer;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class FastImport {
    private static final String[] FILE_PROPS = { "Name", "ModifiedTime",
            "ModifiedUserID", "Comment", "DotNotation" };
    private static final String[] FOLDER_PROPS = { "Name", "ModifiedTime",
            "ModifiedUserID", "Comment", "WorkingFolder", "DotNotation" };
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
        final View v = repo.connect();
        final ExecutorService threadPool = repo.getThreadPool();
        final ConcurrentSkipListMap<CommitId, Commit> commits = new ConcurrentSkipListMap<>();
        final ItemFilter filter = new ItemFilter(
                props.getMetaProperty(META_PROP_ITEM_FILTER));
        _log.info("Loading history");

        final Folder rootFolder = v.getRootFolder();
        rootFolder.populateNow("File", FILE_PROPS, -1);
        rootFolder.populateNow("Folder", FOLDER_PROPS, -1);
        final ProgressBar pb = _log.createProgressBar("Loading history",
                (int) rootFolder.countItems(v.getServer().typeForName("File"),
                        -1));
        load(filter, commits, endDate, rootFolder, threadPool, pb);
        threadPool.shutdown();
        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        pb.complete();
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

    public void submit(final Collection<Commit> commits) throws IOException,
            InterruptedException, ExecutionException {
        _log.info("Executing git fast-import");
        _log.info("");
        final Repo repo = getRepo();
        final Git git = repo.getGit();
        final Exec exec = git.fastImport(repo.getBranchName()).exec();
        final Process proc = exec.getProcess();
        @SuppressWarnings("resource")
        final OutputStream out = proc.getOutputStream();

        try {
            submit(commits, out);
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

    public void submit(final Collection<Commit> commits, final OutputStream out)
            throws IOException, InterruptedException, ExecutionException {
        final Repo repo = getRepo();
        final String branch = repo.getBranchName();
        final ProgressBar b = _log.createProgressBar("Importing to git",
                commits.size());
        final ExecutorService threadPool = repo.getThreadPool();
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

            if (_log.isInfoEnabled()) {
                _log.info(cmt);
                _log.info("--------------------------------------------------------------------------------");
            }

            cmt.write(repo, s);
            b.done(1);
        }

        out.flush();
        _log.info("");
        threadPool.shutdown();
        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        b.complete();
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
                    final CheckoutManager mgr = repo.createCheckoutManager();
                    mgr.addCheckoutListener(l);
                    mgr.checkout(l.getItemList());
                } catch (final Throwable ex) {
                    getRepo().getLogger().error("Checkout failed", ex);
                    main.interrupt();
                }
            }
        });
    }

    private void load(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits,
            final OLEDate endDate, final Folder folder,
            final ExecutorService threadPool, final ProgressBar pb) {
        final ItemList files = folder.getList("File");
        final ItemList folders = folder.getList("Folder");

        if ((files.size() == 0) && (folders.size() == 0)) {
            createEmptyDir(filter, commits, folder);
            return;
        }

        final double end = endDate.getDoubleValue();

        for (final Enumeration<?> en = files.elements(); en.hasMoreElements();) {
            final File f = (File) en.nextElement();
            threadPool.submit(new Runnable() {

                @Override
                public void run() {
                    final Item[] history = f.getHistory();
                    final ItemList list = new ItemList();

                    for (int i = history.length - 1; i >= 0; i--) {
                        list.addItem(history[i]);
                    }

                    File prev = null;
                    list.populateNow(FILE_PROPS);

                    for (final Enumeration<?> i = list.elements(); i
                            .hasMoreElements();) {
                        final File h = (File) i.nextElement();
                        final OLEDate date = h.getModifiedTime();

                        if (date.getDoubleValue() >= end) {
                            break;
                        }

                        load(filter, commits, h, prev, date, threadPool);
                        prev = h;
                    }

                    pb.done(1);
                }
            });
        }

        for (final Enumeration<?> en = folders.elements(); en.hasMoreElements();) {
            load(filter, commits, endDate, (Folder) en.nextElement(),
                    threadPool, pb);
        }
    }

    private void load(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final File file,
            final File prev, final OLEDate date,
            final ExecutorService threadPool) {

        if (prev == null) {
            final FileData data = new FileData(file);
            createFilemodify(filter, commits, date, data, true);
        } else if (file.isDeleted()) {
            createFiledelete(filter, commits, date, file);
        } else {
            final String path = Repo.getPath(file);
            final String prevPath = Repo.getPath(prev);

            if (path.equals(prevPath)) {
                final FileData data = new FileData(file, path);
                createFilemodify(filter, commits, date, data, false);
            } else {
                final FileRename rename = new FileRename(prev, file, prevPath,
                        path);
                createFilerename(filter, commits, date, rename);
            }
        }
    }

    private void createFilemodify(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final OLEDate date,
            final FileData data, final boolean isNewFile) {
        final int user = data.getFile().getModifiedBy();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final FileModify c = new FileModify(data, isNewFile);
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);
            logChange(time, isNewFile ? "A" : "M", c.getPath());
        }
    }

    private void createFilerename(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final OLEDate date,
            final FileRename c) {
        final int user = c.getDestItem().getModifiedBy();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);
            logChange(time, "R", c.getSourcePath() + " -> " + c.getDestPath());
        }
    }

    private void createFiledelete(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final OLEDate date,
            final Item item) {
        final int user = item.getDeletedUserID();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final FileDelete c = new FileDelete(item);
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);
            logChange(time, "D", c.getPath());
        }
    }

    private void createEmptyDir(final ItemFilter filter,
            final ConcurrentMap<CommitId, Commit> commits, final Folder folder) {
        final int user = folder.getModifiedBy();
        final OLEDate date = folder.getModifiedTime();

        if (!filter.apply(user, date.getDoubleValue())) {
            final long time = date.getLongValue();
            final EmptyDir c = new EmptyDir(folder);
            final Commit cmt = getCommit(commits, user, time);
            cmt.addChange(c);
            logChange(time, "A", c.getPath());
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

    private void logChange(final long time, final String type, final String path) {
        if (_log.isInfoEnabled()) {
            _log.info(Repo.DATE_FORMAT.format(time) + "| " + type + ' ' + path);
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

        public ViewListener(final ItemFilter filter) {
            _filter = filter;
        }

        public Map<CommitId, Commit> getCommits() throws InterruptedException {
            return _commits;
        }

        @Override
        public void itemAdded(final ItemUpdateEvent e) {
            final File f = (File) e.getNewItem();
            final FileData data = new FileData(f);
            final OLEDate date = f.getModifiedTime();
            createFilemodify(_filter, _commits, date, data, true);
        }

        @Override
        public void itemChanged(final ItemUpdateEvent e) {
            final File src = (File) e.getOldItem();
            final File dest = (File) e.getNewItem();
            final String srcPath = Repo.getPath(src);
            final String destPath = Repo.getPath(dest);
            final OLEDate date = dest.getModifiedTime();

            if (!srcPath.equals(destPath)) {
                final FileRename c = new FileRename(src, dest, srcPath,
                        destPath);
                createFilerename(_filter, _commits, date, c);
            } else {
                final FileData data = new FileData(dest);
                createFilemodify(_filter, _commits, date, data, false);
            }
        }

        @Override
        public void itemMoved(final ItemUpdateEvent e) {
            final Item src = e.getOldItem();
            final Item dest = e.getNewItem();
            final OLEDate date = dest.getModifiedTime();
            final FileRename c = new FileRename(src, dest);
            createFilerename(_filter, _commits, date, c);
        }

        @Override
        public void itemRemoved(final ItemUpdateEvent e) {
            final Item item = e.getOldItem();
            final OLEDate date = item.getModifiedTime();
            createFiledelete(_filter, _commits, date, item);
        }

        @Override
        public void folderMoved(final FolderUpdateEvent e) {
            final Item src = e.getOldFolder();
            final Item dest = e.getNewFolder();
            final OLEDate date = dest.getModifiedTime();
            final FileRename c = new FileRename(src, dest);
            createFilerename(_filter, _commits, date, c);
        }

        @Override
        public void folderRemoved(final FolderUpdateEvent e) {
            final Item item = e.getOldFolder();
            final OLEDate date = item.getModifiedTime();
            createFiledelete(_filter, _commits, date, item);
        }

        @Override
        public void folderAdded(final FolderUpdateEvent e) {
            final Folder folder = e.getNewFolder();
            final ItemList files = folder.getList("File");

            if (files.size() == 0) {
                final ItemList folders = folder.getList("Folder");

                if (folders.size() == 0) {
                    createEmptyDir(_filter, _commits, folder);
                }
            }
        }

        @Override
        public void folderChanged(final FolderUpdateEvent e) {
            // Not supported
        }
    }

    private static final class CoListener implements CheckoutListener {
        private final Repo _repo;
        private final ProgressBar _pbar;
        private final Map<RemoteFile, List<FileData>> _files;
        private final ItemList _itemList;

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

                        if (log.isDebugEnabled()) {
                            log.debug("Warning! File duplicated in several commits: "
                                    + d.getPath());
                        }
                    }

                    _itemList.addItem(d.getFile());
                }
            }

            _pbar = repo.getLogger().createProgressBar("Checking out",
                    _itemList.size());
        }

        public ItemList getItemList() {
            return _itemList;
        }

        @Override
        public void onStartFile(final CheckoutEvent e) {
            try {
                final File f = e.getCurrentFile();
                e.setCurrentWorkingFile(_repo.createTempFile(_files
                        .get(new RemoteFile(f)).get(0).getPath()));
            } catch (final IOException ex) {
                throw new RuntimeException();
            }
        }

        @Override
        public void onNotifyProgress(final CheckoutEvent e) {
            if (e.isFinished()) {
                if (!e.isSuccessful()) {
                    _repo.getLogger().error(
                            "Failed to checkout file: "
                                    + Repo.getPath(e.getCurrentFile()));
                    return;
                }

                _pbar.done(1);
                final File f = e.getCurrentFile();
                final RemoteFile id = new RemoteFile(f);
                java.io.File wf = e.getCurrentWorkingFile();
                final List<FileData> data = _files.get(id);

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
    }
}
