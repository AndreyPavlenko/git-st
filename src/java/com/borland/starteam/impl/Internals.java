package com.borland.starteam.impl;

import java.io.IOException;

import com.borland.starteam.impl._private_.vts.comm.Command;
import com.borland.starteam.impl._private_.vts.comm.CommandMacro;
import com.borland.starteam.impl._private_.vts.comm.CommandRoute;
import com.borland.starteam.impl._private_.vts.comm.Connection;
import com.borland.starteam.impl._private_.vts.pickle.ItemRevision;
import com.borland.starteam.impl.util.DateTime;
import com.borland.starteam.impl.util.GUID;
import com.googlecode.gitst.Repo;

/**
 * @author Andrey Pavlenko
 */
public class Internals {

    public static com.starbase.starteam.Item[] getHistory(final Repo repo,
            final com.starbase.starteam.Item i) {
        final com.starbase.starteam.Server connection = repo
                .getIdleConnection();

        try {
            final com.borland.starteam.impl.Server server = connection.unwrap();
            final com.borland.starteam.impl.Item item = (com.borland.starteam.impl.Item) i
                    .unwrap();
            final Connection c = server.useConnection();
            final CmdGetHistory cmd = new CmdGetHistory(item);
            cmd.exec(c, server.getSession().getID(),
                    server.getViewSession(item.getView()).getID(),
                    server.getClassID(i.getType().getName()));
            return cmd.getHistory();
        } finally {
            repo.releaseConnection(connection);
        }
    }

    private static class CmdGetHistory extends CommandMacro {
        private static final CommandRoute _route = new CommandRoute(
                -2147483648, 524288, 2010, "PROJ_CMD_GET_ITEMS_HISTORY");
        private final com.borland.starteam.impl.Item _item;
        private com.starbase.starteam.Item[] _history;

        public CmdGetHistory(final com.borland.starteam.impl.Item item) {
            _item = item;
        }

        public com.starbase.starteam.Item[] getHistory() {
            return _history;
        }

        public void exec(final Connection c, final GUID guid,
                final int paramInt1, final int paramInt2) {
            c.lock();
            try {
                c.usingCommand(super.getClass().getName());
                final Command cmd = prepare(c, guid, paramInt1, paramInt2);
                push(cmd);
                c.execCommand(cmd);
                pop(cmd);
                c.terminateCommand(cmd);
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            } finally {
                c.commandNotInUse();
                c.unlock();
            }
        }

        @Override
        public boolean isRetrySupported() {
            return true;
        }

        @Override
        public CommandRoute getCommandRoute() {
            return _route;
        }

        @Override
        protected void push(final Command c) throws IOException {
            c.writeInt(_item.getID());
            c.writeTime(0.0D);

            if ((c.supports("1.38"))) {
                c.writeBoolean(true);
            }
        }

        @Override
        public void pop(final Command c) throws IOException {
            final int count = c.readInt();
            _history = new com.starbase.starteam.Item[count];

            for (int i = 0; i < count; i++) {
                _history[i] = wrap(ItemRevision.read(c));
            }
        }

        private com.starbase.starteam.Item wrap(final ItemRevision r) {
            final com.borland.starteam.impl.Server server = _item.getServer();
            final com.borland.starteam.impl.Type type = _item.getType();
            final DateTime time = new DateTime(r.m_time);
            final com.borland.starteam.impl.Item h = server.newItem(type,
                    _item.getView(), false, true);

            h.setSnapshotTime(time);
            h.setParentFolder(_item.getParentFolder());
            h.setVMID(_item.getID());
            h.initializeReplicaValue("RevisionNumber", new Integer(
                    r.m_revisionID.getRevision()));
            h.initializeReplicaValue("ID",
                    new Integer(r.m_revisionID.getObjectID()));
            h.initializeReplicaValue("ModifiedTime", time);
            h.initializeReplicaValue("DotNotation", r.m_dotNotation);
            h.initializeReplicaValue("Comment", r.m_comment);
            h.initializeReplicaValue("ModifiedUserID", new Integer(r.m_userID));
            h.initializeReplicaValue("PathRevision", new Integer(
                    r.m_pathRevision - r.m_revisionID.getRevision() - 1));
            h.initializeReplicaValue("ViewID", new Integer(r.m_viewID));

            return com.starbase.starteam.Item.wrap(h);
        }
    }
}
