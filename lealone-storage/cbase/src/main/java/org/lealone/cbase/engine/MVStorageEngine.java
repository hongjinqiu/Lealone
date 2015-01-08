/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.cbase.engine;

import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.lealone.api.ErrorCode;
import org.lealone.cbase.dbobject.index.ValueDataType;
import org.lealone.cbase.dbobject.table.MVTable;
import org.lealone.command.ddl.CreateTableData;
import org.lealone.dbobject.table.Table;
import org.lealone.engine.Constants;
import org.lealone.engine.Database;
import org.lealone.engine.InDoubtTransaction;
import org.lealone.engine.Session;
import org.lealone.engine.StorageEngine;
import org.lealone.engine.StorageEngineManager;
import org.lealone.message.DbException;
import org.lealone.mvstore.MVMap;
import org.lealone.mvstore.MVStore;
import org.lealone.mvstore.MVStoreTool;
import org.lealone.store.fs.FileChannelInputStream;
import org.lealone.store.fs.FileUtils;
import org.lealone.transaction.local.LocalTransaction;
import org.lealone.transaction.local.TransactionMap;
import org.lealone.transaction.local.TransactionStore;
import org.lealone.util.BitField;
import org.lealone.util.DataUtils;
import org.lealone.util.New;

/**
 * A storage engine that internally uses the MVStore.
 */
public class MVStorageEngine implements StorageEngine {
    public static final String NAME = "MVStore";

    //见StorageEngineManager.StorageEngineService中的注释
    public MVStorageEngine() {
        StorageEngineManager.registerStorageEngine(this);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Table createTable(CreateTableData data) {
        Database db = data.session.getDatabase();
        Store store = stores.get(db.getName());
        if (store == null) {
            synchronized (stores) {
                if (stores.get(db.getName()) == null) {
                    store = init(db);
                    stores.put(db.getName(), store);
                }
            }
        }

        MVTable table = new MVTable(data, store);
        table.init(data.session);
        store.tableMap.put(table.getMapName(), table);
        return table;
    }

    private static HashMap<String, Store> stores = new HashMap<String, Store>(1);

    public static Store getStore(Session session) {
        return stores.get(session.getDatabase().getName());
    }

    public static Store getStore(Database db) {
        return stores.get(db.getName());
    }

    /**
     * Initialize the MVStore.
     *
     * @param db the database
     * @return the store
     */
    private static Store init(final Database db) {
        Store store = null;
        byte[] key = db.getFileEncryptionKey();
        String dbPath = db.getDatabasePath();
        MVStore.Builder builder = new MVStore.Builder();
        if (dbPath == null) {
            store = new Store(db, builder);
        } else {
            String fileName = dbPath + Constants.SUFFIX_MV_FILE;
            builder.pageSplitSize(db.getPageSize());
            MVStoreTool.compactCleanUp(fileName);
            builder.fileName(fileName);
            if (db.isReadOnly()) {
                builder.readOnly();
            } else {
                // possibly create the directory
                boolean exists = FileUtils.exists(fileName);
                if (exists && !FileUtils.canWrite(fileName)) {
                    // read only
                } else {
                    String dir = FileUtils.getParent(fileName);
                    FileUtils.createDirectories(dir);
                }
            }
            if (key != null) {
                char[] password = new char[key.length / 2];
                for (int i = 0; i < password.length; i++) {
                    password[i] = (char) (((key[i + i] & 255) << 16) | ((key[i + i + 1]) & 255));
                }
                builder.encryptionKey(password);
            }
            if (db.getSettings().compressData) {
                builder.compress();
                // use a larger page split size to improve the compression ratio
                builder.pageSplitSize(64 * 1024);
            }
            builder.backgroundExceptionHandler(new UncaughtExceptionHandler() {

                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    db.setBackgroundException(DbException.convert(e));
                }

            });
            try {
                store = new Store(db, builder);
            } catch (IllegalStateException e) {
                int errorCode = DataUtils.getErrorCode(e.getMessage());
                if (errorCode == DataUtils.ERROR_FILE_CORRUPT) {
                    if (key != null) {
                        throw DbException.get(ErrorCode.FILE_ENCRYPTION_ERROR_1, e, fileName);
                    }
                } else if (errorCode == DataUtils.ERROR_FILE_LOCKED) {
                    throw DbException.get(ErrorCode.DATABASE_ALREADY_OPEN_1, e, fileName);
                } else if (errorCode == DataUtils.ERROR_READING_FAILED) {
                    throw DbException.get(ErrorCode.IO_EXCEPTION_1, e, fileName);
                }
                throw DbException.get(ErrorCode.FILE_CORRUPTED_1, e, fileName);
            }
        }
        return store;
    }

    /**
     * A store with open tables.
     */
    public static class Store {

        /**
         * The map of open tables.
         * Key: the map name, value: the table.
         */
        final ConcurrentHashMap<String, MVTable> tableMap = new ConcurrentHashMap<String, MVTable>();

        /**
         * The store.
         */
        private final MVStore store;

        /**
         * The transaction store.
         */
        private final TransactionStore transactionStore;

        private long statisticsStart;

        private int temporaryMapId;

        public Store(Database db, MVStore.Builder builder) {
            this.store = builder.open();
            this.transactionStore = new TransactionStore(store, new ValueDataType(null, db, null));
            transactionStore.init();
        }

        public MVStore getStore() {
            return store;
        }

        public TransactionStore getTransactionStore() {
            return transactionStore;
        }

        public HashMap<String, MVTable> getTables() {
            return new HashMap<String, MVTable>(tableMap);
        }

        /**
         * Remove a table.
         *
         * @param table the table
         */
        public void removeTable(MVTable table) {
            tableMap.remove(table.getMapName());
        }

        /**
         * Store all pending changes.
         */
        public void flush() {
            org.lealone.mvstore.FileStore s = store.getFileStore();
            if (s == null || s.isReadOnly()) {
                return;
            }
            if (!store.compact(50, 4 * 1024 * 1024)) {
                store.commit();
            }
        }

        /**
         * Close the store, without persisting changes.
         */
        public void closeImmediately() {
            if (store.isClosed()) {
                return;
            }
            store.closeImmediately();
        }

        /**
         * Commit all transactions that are in the committing state, and
         * rollback all open transactions.
         */
        public void initTransactions() {
            List<LocalTransaction> list = transactionStore.getOpenTransactions();
            for (LocalTransaction t : list) {
                if (t.getStatus() == LocalTransaction.STATUS_COMMITTING) {
                    t.commit();
                } else if (t.getStatus() != LocalTransaction.STATUS_PREPARED) {
                    t.rollback();
                }
            }
        }

        /**
         * Remove all temporary maps.
         *
         * @param objectIds the ids of the objects to keep
         */
        public void removeTemporaryMaps(BitField objectIds) {
            for (String mapName : store.getMapNames()) {
                if (mapName.startsWith("temp.")) {
                    MVMap<?, ?> map = store.openMap(mapName);
                    store.removeMap(map);
                } else if (mapName.startsWith("table.") || mapName.startsWith("index.")) {
                    int id = Integer.parseInt(mapName.substring(1 + mapName.indexOf(".")));
                    if (!objectIds.get(id)) {
                        ValueDataType keyType = new ValueDataType(null, null, null);
                        ValueDataType valueType = new ValueDataType(null, null, null);
                        LocalTransaction t = transactionStore.begin();
                        TransactionMap<?, ?> m = t.openMap(mapName, keyType, valueType);
                        transactionStore.removeMap(m);
                        t.commit();
                    }
                }
            }
        }

        /**
         * Get the name of the next available temporary map.
         *
         * @return the map name
         */
        public synchronized String nextTemporaryMapName() {
            return "temp." + temporaryMapId++;
        }

        /**
         * Prepare a transaction.
         *
         * @param session the session
         * @param transactionName the transaction name (may be null)
         */
        public void prepareCommit(Session session, String transactionName) {
            LocalTransaction t = (LocalTransaction) session.getTransaction();
            t.setName(transactionName);
            t.prepare();
            store.commit();
        }

        public ArrayList<InDoubtTransaction> getInDoubtTransactions() {
            List<LocalTransaction> list = transactionStore.getOpenTransactions();
            ArrayList<InDoubtTransaction> result = New.arrayList();
            for (LocalTransaction t : list) {
                if (t.getStatus() == LocalTransaction.STATUS_PREPARED) {
                    result.add(new MVInDoubtTransaction(store, t));
                }
            }
            return result;
        }

        public void setCacheSize(int kb) {
            store.setCacheSize(Math.max(1, kb / 1024));
        }

        public InputStream getInputStream() {
            FileChannel fc = store.getFileStore().getEncryptedFile();
            if (fc == null) {
                fc = store.getFileStore().getFile();
            }
            return new FileChannelInputStream(fc, false);
        }

        /**
         * Force the changes to disk.
         */
        public void sync() {
            flush();
            store.sync();
        }

        /**
         * Compact the database file, that is, compact blocks that have a low
         * fill rate, and move chunks next to each other. This will typically
         * shrink the database file. Changes are flushed to the file, and old
         * chunks are overwritten.
         *
         * @param maxCompactTime the maximum time in milliseconds to compact
         */
        public void compactFile(long maxCompactTime) {
            store.setRetentionTime(0);
            long start = System.currentTimeMillis();
            while (store.compact(95, 16 * 1024 * 1024)) {
                store.sync();
                store.compactMoveChunks(95, 16 * 1024 * 1024);
                long time = System.currentTimeMillis() - start;
                if (time > maxCompactTime) {
                    break;
                }
            }
        }

        /**
         * Close the store. Pending changes are persisted. Chunks with a low
         * fill rate are compacted, but old chunks are kept for some time, so
         * most likely the database file will not shrink.
         *
         * @param maxCompactTime the maximum time in milliseconds to compact
         */
        public void close(long maxCompactTime) {
            try {
                if (!store.isClosed() && store.getFileStore() != null) {
                    boolean compactFully = false;
                    if (!store.getFileStore().isReadOnly()) {
                        transactionStore.close();
                        if (maxCompactTime == Long.MAX_VALUE) {
                            compactFully = true;
                        }
                    }
                    String fileName = store.getFileStore().getFileName();
                    store.close();
                    if (compactFully && FileUtils.exists(fileName)) {
                        // the file could have been deleted concurrently,
                        // so only compact if the file still exists
                        MVStoreTool.compact(fileName, true);
                    }
                }
            } catch (IllegalStateException e) {
                int errorCode = DataUtils.getErrorCode(e.getMessage());
                if (errorCode == DataUtils.ERROR_WRITING_FAILED) {
                    // disk full - ok
                } else if (errorCode == DataUtils.ERROR_FILE_CORRUPT) {
                    // wrong encryption key - ok
                }
                store.closeImmediately();
                throw DbException.get(ErrorCode.IO_EXCEPTION_1, e, "Closing");
            }
        }

        /**
         * Start collecting statistics.
         */
        public void statisticsStart() {
            org.lealone.mvstore.FileStore fs = store.getFileStore();
            statisticsStart = fs == null ? 0 : fs.getReadCount();
        }

        /**
         * Stop collecting statistics.
         *
         * @return the statistics
         */
        public Map<String, Integer> statisticsEnd() {
            HashMap<String, Integer> map = New.hashMap();
            org.lealone.mvstore.FileStore fs = store.getFileStore();
            int reads = fs == null ? 0 : (int) (fs.getReadCount() - statisticsStart);
            map.put("reads", reads);
            return map;
        }

    }

    /**
     * An in-doubt transaction.
     */
    private static class MVInDoubtTransaction implements InDoubtTransaction {

        private final MVStore store;
        private final LocalTransaction transaction;
        private int state = InDoubtTransaction.IN_DOUBT;

        MVInDoubtTransaction(MVStore store, LocalTransaction transaction) {
            this.store = store;
            this.transaction = transaction;
        }

        @Override
        public void setState(int state) {
            if (state == InDoubtTransaction.COMMIT) {
                transaction.commit();
            } else {
                transaction.rollback();
            }
            store.commit();
            this.state = state;
        }

        @Override
        public String getState() {
            switch (state) {
            case IN_DOUBT:
                return "IN_DOUBT";
            case COMMIT:
                return "COMMIT";
            case ROLLBACK:
                return "ROLLBACK";
            default:
                throw DbException.throwInternalError("state=" + state);
            }
        }

        @Override
        public String getTransactionName() {
            return transaction.getName();
        }

    }

}
