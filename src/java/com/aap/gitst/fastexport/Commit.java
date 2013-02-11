package com.aap.gitst.fastexport;

import static com.aap.gitst.Repo.LS;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import com.aap.gitst.Logger;
import com.aap.gitst.Logger.ProgressBar;
import com.aap.gitst.RemoteFile;
import com.aap.gitst.Repo;
import com.aap.gitst.Utils;
import com.starbase.starteam.CheckinEvent;
import com.starbase.starteam.CheckinListener;
import com.starbase.starteam.CheckinManager;
import com.starbase.starteam.CheckinProgress;
import com.starbase.starteam.File;
import com.starbase.starteam.ItemList;

/**
 * @author Andrey Pavlenko
 */
public class Commit implements FastExportCommand {
    private final List<FileChange> _changes = new ArrayList<>();
    private Integer _mark;
    private Integer _from;
    private long _date;
    private String _committer;
    private String _comment = "";
    private boolean _committed;

    public Integer getMark() {
        return _mark;
    }

    public void setMark(final Integer mark) {
        _mark = mark;
    }

    public Integer getFrom() {
        return _from;
    }

    public void setFrom(final Integer from) {
        _from = from;
    }

    public long getDate() {
        return _date;
    }

    public void setDate(final long date) {
        _date = date;
    }

    public void setDate(final String date, final String shift) {
        final long d = Long.parseLong(date) * 1000;
        final int off = TimeZone.getDefault().getOffset(d)
                - TimeZone.getTimeZone("GMT" + shift).getOffset(d);
        _date = d + off;
    }

    public String getCommitter() {
        return _committer;
    }

    public void setCommitter(final String committer) {
        _committer = committer;
    }

    public String getComment() {
        return _comment;
    }

    public void setComment(final String comment) {
        _comment = comment;
    }

    public List<FileChange> getChanges() {
        return _changes;
    }

    public void addChange(final FileChange c) {
        getChanges().add(c);
    }

    public synchronized boolean exec(final Repo repo) throws IOException,
            FastExportException {
        final List<FileChange> changes = getChanges();

        if (!_committed && !changes.isEmpty()) {
            final CheckinManager mgr = repo.createCheckinManager(getComment());
            final Map<RemoteFile, FileModify> mod = new LinkedHashMap<>();
            final Logger log = repo.getLogger();

            if (log.isInfoEnabled()) {
                log.info(this);
                log.info("--------------------------------------------------------------------------------");
            }
            for (final FileChange c : changes) {
                if (c instanceof FileModify) {
                    final FileModify m = (FileModify) c;
                    final File f = m.getFile(repo);
                    mod.put(new RemoteFile(f), m);
                } else {
                    c.exec(repo, this);
                }
            }
            if (!mod.isEmpty()) {
                commit(mgr, repo, mod);
            }

            return _committed = true;
        } else {
            return false;
        }
    }

    private void commit(final CheckinManager mgr, final Repo repo,
            final Map<RemoteFile, FileModify> changes) throws IOException {
        final ItemList items = new ItemList();
        long size = 0;

        for (final FileModify c : changes.values()) {
            final File f = c.getFile(repo);
            items.addItem(f);
            size += c.getLocalFile(repo).length();
        }

        final Listener l = new Listener(repo, changes, size);
        mgr.addCheckinListener(l);
        mgr.checkin(items);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final String comment = getComment();
        sb.append(new SimpleDateFormat(Repo.DATE_FORMAT).format(getDate()));
        sb.append(' ');
        sb.append(getCommitter()).append(LS);
        sb.append(comment);

        if (!comment.endsWith("\n")) {
            sb.append(LS);
        }
        for (final FileChange c : getChanges()) {
            sb.append(LS).append(c);
        }

        return sb.toString();
    }

    private final class Listener implements CheckinListener {
        private final Repo _repo;
        private final Map<RemoteFile, FileModify> _changes;
        private final ProgressBar _pb;
        private final AtomicLong _totalBytes = new AtomicLong();
        private final String _avgSize;

        public Listener(final Repo repo,
                final Map<RemoteFile, FileModify> changes, final long avgSize) {
            _repo = repo;
            _changes = changes;
            _avgSize = Repo.bytesToString(avgSize);
            _pb = repo.getLogger().createProgressBar(this, changes.size());
        }

        @Override
        public void onStartFile(final CheckinEvent e) {
            try {
                final File f = e.getCurrentFile();
                final FileModify m = _changes.get(new RemoteFile(f));
                e.setCurrentWorkingFile(m.getLocalFile(_repo));
                f.put(f.getPropertyNames().FILE_EXECUTABLE, m.isExecutable()
                        ? 1 : 0);
            } catch (final IOException ex) {
                _repo.getLogger().error("Error occurred", ex);
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void onNotifyProgress(final CheckinEvent e) {
            if (!Utils.isApi12()) {
                handleProgress(e.getCheckinManager().getProgress());
            }

            if (e.isFinished()) {
                if (!e.isSuccessful()) {
                    _repo.getLogger().error(
                            "Checkin failed: " + e.getErrorMessage(),
                            e.getError());
                }

                _pb.done(1);
                e.getCurrentWorkingFile().delete();
            }
        }

        private void handleProgress(final CheckinProgress progress) {
            final long p = progress.getTotalBytesCheckedIn();

            for (;;) {
                final long total = _totalBytes.get();

                if (total >= p) {
                    return;
                } else if (_totalBytes.compareAndSet(total, p)) {
                    _pb.update();
                    return;
                }
            }
        }

        @Override
        public String toString() {
            return Utils.isApi12() ? "Checking in" : "Checking in ("
                    + Repo.bytesToString(_totalBytes.get()) + " / " + _avgSize
                    + ')';

        }
    }
}
