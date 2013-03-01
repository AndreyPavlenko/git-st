package com.aap.gitst;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.borland.starteam.impl.Internals;
import com.starbase.starteam.CheckoutListener;
import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.Item;
import com.starbase.starteam.ItemList;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class Utils {
    private static final boolean USE_INTERNALS = !"false"
            .equalsIgnoreCase(System.getenv("GITST_USE_INTERNALS"));
    private static Method GET_HISTORY12;
    private static final boolean IS_API12;

    static {
        Method getHistory = null;

        if (USE_INTERNALS) {
            try {
                Class.forName("com.starteam.Item");
                getHistory = Class.forName("com.starteam.Internals12")
                        .getMethod("getHistory", Repo.class, Item.class);
            } catch (final Throwable ex) {
            }
        }

        GET_HISTORY12 = getHistory;
        IS_API12 = getHistory != null;
    }

    public static String bytesToString(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < (1024 * 1024)) {
            return (bytes / 1024) + " KB";
        } else {
            return new BigDecimal((double) bytes / (1024 * 1024)).setScale(1,
                    RoundingMode.HALF_UP) + " MB";
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

    public static String getParentFolderPath(final String path) {
        final int slash = path.lastIndexOf('/');

        if (slash == -1) {
            return "";
        } else {
            return path.substring(0, slash);
        }
    }

    public static boolean isApi12() {
        return IS_API12;
    }

    public static com.starbase.starteam.Item[] getHistory(final Repo repo,
            final com.starbase.starteam.Item i,
            final com.starbase.starteam.ItemList list) {
        com.starbase.starteam.Item[] history = null;
        final boolean deleted = i.isDeleted();

        if (deleted) {
            // FIXME: this is a workaround to avoid unexpected failures during
            // checkout of deleted files.
            try {
                OLEDate asOfTime = i.getModifiedTime();
                OLEDate viewCreateTime = repo.getView().getCreatedTime();
                if (asOfTime.getDoubleValue() < viewCreateTime.getDoubleValue())
                    asOfTime = viewCreateTime;
                final com.starbase.starteam.View historyView = repo.getView(asOfTime);
                final com.starbase.starteam.Item historyItem = historyView
                        .findItem(i.getType(), i.getID());

                if (historyItem != null) {
                    history = getHistory(repo, historyItem);
                }
            } catch (final Throwable ex) {
                if (repo.getLogger().isDebugEnabled()) {
                    repo.getLogger().debug(
                            repo.getPath(i) + ": " + ex.getMessage());
                }
            }
            if (history == null) {
                history = getHistory(repo, i);
            }
        } else {
            history = getHistory(repo, i);
        }

        if (deleted) {
            final com.starbase.starteam.Item[] h = new com.starbase.starteam.Item[history.length + 1];
            h[0] = i; // Marker for deleted items

            for (int n = 0; n < history.length; n++) {
                h[n + 1] = history[n];
                list.addItem(history[n]);
            }

            return h;
        } else {
            for (final com.starbase.starteam.Item h : history) {
                list.addItem(h);
            }
            return history;
        }
    }

    public static Item[] getHistory(final Repo repo, final Item i) {
        if (!USE_INTERNALS) {
            return i.getHistory();
        } else if (GET_HISTORY12 != null) {
            try {
                return (Item[]) GET_HISTORY12.invoke(null, repo, i);
            } catch (final Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            return Internals.getHistory(repo, i);
        }
    }

    public static void checkout(final Repo repo, final ItemList items,
            final CheckoutListener listener) throws InterruptedException {
        if (isApi12()) {
            checkout12(repo, items, listener);
        } else {
            final CheckoutManager mgr = repo.createCheckoutManager();
            mgr.addCheckoutListener(listener);
            mgr.checkout(items);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkout12(final Repo repo, final ItemList items,
            final CheckoutListener listener) throws InterruptedException {
        final List<Set<Item>> l = new ArrayList<>();

        itemsLoop: for (final Enumeration<Item> en = items.elements(); en
                .hasMoreElements();) {
            final Item i = en.nextElement();

            for (final Set<Item> set : l) {
                if (!set.contains(i)) {
                    set.add(i);
                    continue itemsLoop;
                }
            }

            final Set<Item> set = new HashSet<>();
            set.add(i);
            l.add(set);
        }

        for (final Set<Item> set : l) {
            final CheckoutManager mgr = repo.createCheckoutManager();
            mgr.addCheckoutListener(listener);
            mgr.checkout(set.toArray(new Item[set.size()]));
        }
    }
}
